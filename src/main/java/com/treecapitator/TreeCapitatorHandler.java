package com.treecapitator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-side felling logic.
 *
 * <p>The original block is broken by vanilla before this class runs. Additional
 * trunk blocks are removed through {@link ServerPlayerGameMode#destroyBlock}
 * (via the player's game mode) so loot tables, Fortune, Silk Touch, tool
 * damage and Unbreaking all follow vanilla behaviour. A thread-local guard
 * prevents the resulting break events from starting another felling.</p>
 */
public final class TreeCapitatorHandler {
    private static final int PLAN_CACHE_TICKS = 1200;
    private static final ThreadLocal<Boolean> FELLING = ThreadLocal.withInitial(() -> false);
    private static final Map<UUID, CachedPlan> PLAN_CACHE = new HashMap<>();

    private TreeCapitatorHandler() {
    }

    /**
     * Called from the BlockState mixin. It slows down the block that starts the
     * felling so larger trees take longer to commit to.
     */
    public static float adjustDestroyProgress(BlockState state, Player player, BlockGetter level,
            BlockPos pos, float original) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return original;
        }
        if (player.isCreative() || player.isSpectator() || FELLING.get()) {
            return original;
        }
        if (!isEligible(serverPlayer, serverLevel, state)) {
            return original;
        }

        TreePlan plan = getOrScan(serverPlayer, serverLevel, pos, state);
        if (plan == null || plan.tooLarge() || plan.trunkCount() <= 1
                || !plan.hasEnoughFoliage(TreeCapitatorConfig.minLeaves)) {
            return original;
        }

        double extraLogs = plan.trunkCount() - 1;
        double multiplier = 1.0D + extraLogs * TreeCapitatorConfig.breakTimePerLog;
        multiplier = Math.min(multiplier, TreeCapitatorConfig.maxBreakTimeMultiplier);
        if (multiplier <= 1.0D) {
            return original;
        }

        return (float) (original / multiplier);
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

        TreePlan plan = takeOrScan(serverPlayer, serverLevel, pos, state);
        if (plan == null || plan.tooLarge() || plan.trunkCount() <= 1
                || !plan.hasEnoughFoliage(TreeCapitatorConfig.minLeaves)) {
            return;
        }

        FELLING.set(true);
        try {
            if (fellTrunks(serverPlayer, serverLevel, plan)) {
                breakFoliage(serverPlayer, serverLevel, plan);
            }
        } finally {
            FELLING.set(false);
        }
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

    private static boolean fellTrunks(ServerPlayer player, ServerLevel level, TreePlan plan) {
        List<BlockPos> trunkPositions = new ArrayList<>();
        for (BlockPos pos : plan.trunk()) {
            if (!pos.equals(plan.origin())) {
                trunkPositions.add(pos);
            }
        }

        return fellTrunksFrom(player, level, plan, trunkPositions, 0);
    }

    private static boolean fellTrunksFrom(ServerPlayer player, ServerLevel level, TreePlan plan,
            List<BlockPos> trunkPositions, int index) {
        if (index >= trunkPositions.size()) {
            return true;
        }

        BlockPos pos = trunkPositions.get(index);
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }

        ItemStack tool = player.getMainHandItem();
        if (tool.isEmpty()) {
            return false;
        }
        if (TreeCapitatorConfig.requireAxe && !tool.is(ItemTags.AXES)) {
            return false;
        }

        BlockState current = level.getBlockState(pos);
        if (!TreeScanner.isTrunk(current, plan.kind())) {
            return fellTrunksFrom(player, level, plan, trunkPositions, index + 1);
        }
        if (!level.mayInteract(player, pos)) {
            return false;
        }
        if (!player.gameMode.destroyBlock(pos)) {
            return false;
        }

        if (index + 1 < trunkPositions.size() && TreeCapitatorConfig.breakDelayMs > 0) {
            level.getServer().execute(() -> fellTrunksFrom(player, level, plan, trunkPositions, index + 1));
            return true;
        }

        return fellTrunksFrom(player, level, plan, trunkPositions, index + 1);
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

            // Leaves and caps are removed without drops, matching natural decay.
            level.destroyBlock(pos, false);
        }
    }

    private static TreePlan getOrScan(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        long now = level.getGameTime();
        CachedPlan cached = PLAN_CACHE.get(player.getUUID());

        if (cached != null
                && cached.dimension().equals(level.dimension())
                && cached.pos().equals(pos)
                && cached.expiresAt() >= now
                && cached.plan().kind() == TreeScanner.kindOf(state)) {
            return cached.plan();
        }

        TreePlan plan = TreeScanner.scan(level, pos, state);
        if (plan != null) {
            PLAN_CACHE.put(player.getUUID(), new CachedPlan(level.dimension(), pos.immutable(), plan,
                    now + PLAN_CACHE_TICKS));
        }
        return plan;
    }

    private static TreePlan takeOrScan(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        long now = level.getGameTime();
        CachedPlan cached = PLAN_CACHE.get(player.getUUID());

        if (cached != null
                && cached.dimension().equals(level.dimension())
                && cached.pos().equals(pos)
                && cached.expiresAt() >= now
                && cached.plan().kind() == TreeScanner.kindOf(state)) {
            PLAN_CACHE.remove(player.getUUID());
            return cached.plan();
        }

        PLAN_CACHE.remove(player.getUUID());
        return TreeScanner.scan(level, pos, state);
    }

    private record CachedPlan(ResourceKey<Level> dimension, BlockPos pos, TreePlan plan, long expiresAt) {
    }
}
