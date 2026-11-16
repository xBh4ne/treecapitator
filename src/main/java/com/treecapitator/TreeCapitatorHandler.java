package com.treecapitator;

import java.util.ArrayDeque;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-side felling logic.
 *
 * <p>The original block is broken by vanilla before this class runs, at the
 * normal vanilla speed. Additional trunk blocks are removed through
 * {@link ServerPlayerGameMode#destroyBlock} (via the player's game mode) so loot
 * tables, Fortune, Silk Touch, tool damage and Unbreaking all follow vanilla
 * behaviour. A boolean guard prevents the resulting break events from starting
 * another felling.</p>
 *
 * <p>Work is spread over server ticks by a single FIFO queue. Each felling
 * keeps its own cursor, so there is one small job object per tree instead of
 * one scheduled task (and one lambda) per block. Leaves and mushroom caps are
 * removed in small batches per tick, which keeps large canopies from causing a
 * single-tick spike.</p>
 */
public final class TreeCapitatorHandler {
    private static final int FOLIAGE_PER_TICK = 128;
    private static final ArrayDeque<FellingJob> JOBS = new ArrayDeque<>();

    /**
     * True while the handler itself is destroying a block. Everything runs on
     * the server thread, so a plain field is enough; no boxed ThreadLocal.
     */
    private static boolean felling;

    private TreeCapitatorHandler() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (JOBS.isEmpty()) {
                return;
            }

            long now = server.getTickCount();
            // Jobs always become ready in queue order, so the head is the only
            // candidate. The budget also protects against a job rescheduling
            // itself for the same tick.
            int budget = JOBS.size();
            while (budget-- > 0) {
                FellingJob job = JOBS.peek();
                if (job == null || job.readyTick > now) {
                    break;
                }

                JOBS.poll();
                try {
                    job.step(now);
                } catch (RuntimeException exception) {
                    TreeCapitatorMod.LOGGER.error("Could not finish felling a tree", exception);
                }
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> JOBS.clear());
    }

    public static void afterBlockBreak(Level level, Player player, BlockPos pos, BlockState state,
            BlockEntity blockEntity) {
        if (felling || !(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        // Cheap checks first: the break event fires for every block in the world.
        TreeKind kind = eligibleKind(serverPlayer, state);
        if (kind == null) {
            return;
        }

        TreePlan plan = TreeScanner.scan(serverLevel, pos, state, kind);
        if (plan == null || plan.tooLarge() || plan.trunkCount() <= 1
                || !plan.hasEnoughFoliage(TreeCapitatorConfig.minLeaves)) {
            return;
        }

        FellingJob job = new FellingJob(serverPlayer, serverLevel, plan);
        job.readyTick = serverLevel.getServer().getTickCount();
        JOBS.add(job);
    }

    private static TreeKind eligibleKind(ServerPlayer player, BlockState state) {
        if (player.isSpectator()) {
            return null;
        }
        if (TreeCapitatorConfig.sneakDisables && player.isShiftKeyDown()) {
            return null;
        }

        TreeKind kind = TreeScanner.kindOf(state);
        if (kind == null) {
            return null;
        }
        if (TreeCapitatorConfig.requireAxe && !player.getMainHandItem().is(ItemTags.AXES)) {
            return null;
        }

        return TreeCapitatorPreferences.isEnabled(player) ? kind : null;
    }

    /**
     * One felling in progress. Holds the plan and two cursors, so a tree of any
     * size only needs this single object for its whole lifetime.
     */
    private static final class FellingJob {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final TreePlan plan;
        private final BlockPos[] trunk;
        private final BlockPos[] foliage;
        private final long breakDelayTicks;
        private int trunkIndex;
        private int foliageIndex;
        private long readyTick;

        private FellingJob(ServerPlayer player, ServerLevel level, TreePlan plan) {
            this.player = player;
            this.level = level;
            this.plan = plan;
            this.trunk = plan.trunk();
            this.foliage = plan.foliage();
            this.breakDelayTicks = TreeCapitatorConfig.breakDelayTicks;
        }

        private void step(long now) {
            if (player.isRemoved() || player.level() != level || !player.isAlive() || player.isSpectator()) {
                return;
            }

            // Break at most one trunk block per step; the next one is scheduled
            // for a later tick to avoid removing a large tree in one tick.
            while (trunkIndex < trunk.length) {
                BlockPos pos = trunk[trunkIndex++];
                if (pos.equals(plan.origin())) {
                    continue;
                }
                if (TreeCapitatorConfig.requireAxe) {
                    ItemStack tool = player.getMainHandItem();
                    if (tool.isEmpty() || !tool.is(ItemTags.AXES)) {
                        return;
                    }
                }
                if (!TreeScanner.isTrunk(level.getBlockState(pos), plan.kind())) {
                    continue;
                }
                if (!level.mayInteract(player, pos)) {
                    return;
                }

                boolean broken;
                felling = true;
                try {
                    broken = player.gameMode.destroyBlock(pos);
                } finally {
                    felling = false;
                }
                if (!broken) {
                    return;
                }

                if (trunkIndex < trunk.length) {
                    schedule(now + breakDelayTicks);
                    return;
                }
                break;
            }

            removeFoliageBatch(now);
        }

        private void removeFoliageBatch(long now) {
            boolean enabled = plan.kind() == TreeKind.MUSHROOM
                    ? TreeCapitatorConfig.breakMushroomCaps
                    : TreeCapitatorConfig.breakLeaves;
            if (!enabled || plan.foliageTruncated()) {
                return;
            }

            int processed = 0;
            while (foliageIndex < foliage.length) {
                if (processed++ >= FOLIAGE_PER_TICK) {
                    schedule(now + 1);
                    return;
                }

                BlockPos pos = foliage[foliageIndex++];
                // Leaves and caps are removed without drops, matching natural decay.
                if (!TreeScanner.isFoliage(level.getBlockState(pos), plan.kind())) {
                    continue;
                }
                if (!level.mayInteract(player, pos)) {
                    continue;
                }
                level.destroyBlock(pos, false);
            }
        }

        private void schedule(long tick) {
            readyTick = tick;
            JOBS.add(this);
        }
    }
}
