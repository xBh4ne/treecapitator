package com.treecapitator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

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
 * behaviour. A thread-local guard prevents the resulting break events from
 * starting another felling.</p>
 */
public final class TreeCapitatorHandler {
    private static final ThreadLocal<Boolean> FELLING = ThreadLocal.withInitial(() -> false);
    private static final Queue<PendingStep> PENDING_STEPS =
            new PriorityQueue<>(Comparator.comparingLong(PendingStep::readyTick));

    private TreeCapitatorHandler() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.getTickCount();
            while (!PENDING_STEPS.isEmpty() && PENDING_STEPS.peek().readyTick() <= now) {
                PendingStep step = PENDING_STEPS.poll();
                try {
                    step.action().run();
                } catch (RuntimeException exception) {
                    TreeCapitatorMod.LOGGER.error("Could not finish felling a tree", exception);
                }
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING_STEPS.clear());
    }

    public static void afterBlockBreak(Level level, Player player, BlockPos pos, BlockState state,
            BlockEntity blockEntity) {
        if (FELLING.get()) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (!isEligible(serverPlayer, serverLevel, state)) {
            return;
        }

        TreePlan plan = TreeScanner.scan(serverLevel, pos, state);
        if (plan == null || plan.tooLarge() || plan.trunkCount() <= 1
                || !plan.hasEnoughFoliage(TreeCapitatorConfig.minLeaves)) {
            return;
        }

        List<BlockPos> trunkPositions = new ArrayList<>();
        for (BlockPos trunkPos : plan.trunk()) {
            if (!trunkPos.equals(plan.origin())) {
                trunkPositions.add(trunkPos);
            }
        }
        if (trunkPositions.isEmpty()) {
            breakFoliage(serverPlayer, serverLevel, plan);
            return;
        }

        long readyTick = serverLevel.getServer().getTickCount();
        PENDING_STEPS.add(new PendingStep(readyTick,
                () -> fellNextTrunk(serverPlayer, serverLevel, plan, trunkPositions, 0)));
    }

    private static boolean isEligible(ServerPlayer player, ServerLevel level, BlockState state) {
        if (player.isSpectator() || !TreeCapitatorPreferences.isEnabled(player)) {
            return false;
        }
        if (TreeCapitatorConfig.sneakDisables && player.isShiftKeyDown()) {
            return false;
        }
        if (TreeScanner.kindOf(state) == null) {
            return false;
        }
        ItemStack tool = player.getMainHandItem();
        return !TreeCapitatorConfig.requireAxe || tool.is(ItemTags.AXES);
    }

    /**
     * Breaks one additional trunk block and schedules the next one. Waiting at
     * least one tick between blocks keeps a large tree from being removed in a
     * single server tick and makes {@code break-delay-ms} behave as documented.
     */
    private static void fellNextTrunk(ServerPlayer player, ServerLevel level, TreePlan plan,
            List<BlockPos> trunkPositions, int index) {
        if (player.isRemoved() || player.level() != level || !player.isAlive() || player.isSpectator()) {
            return;
        }

        while (index < trunkPositions.size()) {
            if (TreeCapitatorConfig.requireAxe) {
                ItemStack tool = player.getMainHandItem();
                if (tool.isEmpty() || !tool.is(ItemTags.AXES)) {
                    return;
                }
            }

            BlockPos pos = trunkPositions.get(index);
            BlockState current = level.getBlockState(pos);
            if (!TreeScanner.isTrunk(current, plan.kind())) {
                index++;
                continue;
            }
            if (!level.mayInteract(player, pos)) {
                return;
            }

            boolean broken;
            FELLING.set(true);
            try {
                broken = player.gameMode.destroyBlock(pos);
            } finally {
                FELLING.set(false);
            }
            if (!broken) {
                return;
            }

            int nextIndex = index + 1;
            if (nextIndex >= trunkPositions.size()) {
                breakFoliage(player, level, plan);
                return;
            }

            long readyTick = level.getServer().getTickCount() + breakDelayTicks();
            PENDING_STEPS.add(new PendingStep(readyTick,
                    () -> fellNextTrunk(player, level, plan, trunkPositions, nextIndex)));
            return;
        }

        // Every remaining position was already empty or no longer part of the trunk.
        breakFoliage(player, level, plan);
    }

    private static int breakDelayTicks() {
        if (TreeCapitatorConfig.breakDelayMs <= 0) {
            return 1;
        }
        return Math.max(1, (TreeCapitatorConfig.breakDelayMs + 49) / 50);
    }

    private static void breakFoliage(ServerPlayer player, ServerLevel level, TreePlan plan) {
        boolean enabled = plan.kind() == TreeKind.MUSHROOM
                ? TreeCapitatorConfig.breakMushroomCaps
                : TreeCapitatorConfig.breakLeaves;

        if (!enabled || plan.foliageTruncated()) {
            return;
        }

        for (BlockPos pos : plan.foliage()) {
            if (!level.isLoaded(pos)) {
                continue;
            }

            BlockState state = level.getBlockState(pos);
            if (!TreeScanner.isFoliage(state, plan.kind())) {
                continue;
            }
            if (!level.mayInteract(player, pos)) {
                continue;
            }

            // Leaves and caps are removed without drops, matching natural decay.
            level.destroyBlock(pos, false);
        }
    }

    private record PendingStep(long readyTick, Runnable action) {
    }
}
