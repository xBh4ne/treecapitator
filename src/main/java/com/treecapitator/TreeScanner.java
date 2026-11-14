package com.treecapitator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Scans connected trunk blocks and the foliage that belongs to them.
 *
 * <p>The trunk search uses face neighbours by default and also edge/corner
 * neighbours when {@code allow-diagonal} is enabled. Foliage is searched from
 * blocks touching the trunk, so unrelated leaf structures are not collected.</p>
 */
public final class TreeScanner {
    private static final int MAX_FOLIAGE = 4096;
    private static final int[][] FACE_NEIGHBOURS = buildNeighbours(false);
    private static final int[][] ALL_NEIGHBOURS = buildNeighbours(true);

    private TreeScanner() {
    }

    public static TreeKind kindOf(BlockState state) {
        if (state.is(BlockTags.LOGS) && !state.is(BlockTags.BAMBOO_BLOCKS)) {
            return TreeKind.WOOD;
        }
        if (TreeCapitatorConfig.includeMushrooms && state.is(Blocks.MUSHROOM_STEM)) {
            return TreeKind.MUSHROOM;
        }
        return null;
    }

    public static boolean isTrunk(BlockState state, TreeKind kind) {
        if (kind == TreeKind.WOOD) {
            return state.is(BlockTags.LOGS) && !state.is(BlockTags.BAMBOO_BLOCKS);
        }
        if (kind == TreeKind.MUSHROOM) {
            return state.is(Blocks.MUSHROOM_STEM);
        }
        return false;
    }

    public static boolean isFoliage(BlockState state, TreeKind kind) {
        if (kind == TreeKind.WOOD) {
            if (state.is(BlockTags.LEAVES)) {
                // Player-placed persistent leaves are never treated as natural canopy.
                return !state.hasProperty(LeavesBlock.PERSISTENT)
                        || !state.getValue(LeavesBlock.PERSISTENT);
            }
            // Huge crimson and warped fungi use wart blocks and shroomlight as canopy.
            return state.is(Blocks.NETHER_WART_BLOCK)
                    || state.is(Blocks.WARPED_WART_BLOCK)
                    || state.is(Blocks.SHROOMLIGHT);
        }
        if (kind == TreeKind.MUSHROOM) {
            return state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.RED_MUSHROOM_BLOCK);
        }
        return false;
    }

    public static TreePlan scan(ServerLevel level, BlockPos origin, BlockState originState) {
        TreeKind kind = kindOf(originState);
        if (kind == null) {
            return null;
        }

        BlockPos immutableOrigin = origin.immutable();
        int[][] neighbours = TreeCapitatorConfig.allowDiagonal ? ALL_NEIGHBOURS : FACE_NEIGHBOURS;
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> trunk = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        visited.add(immutableOrigin);
        queue.add(immutableOrigin);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            BlockState state = pos.equals(immutableOrigin) ? originState : getState(level, pos);

            if (state == null || !isTrunk(state, kind)) {
                continue;
            }

            trunk.add(pos.immutable());
            if (trunk.size() > TreeCapitatorConfig.maxLogs) {
                return new TreePlan(kind, immutableOrigin, List.copyOf(trunk), List.of(), false, true);
            }

            for (int[] offset : neighbours) {
                BlockPos next = pos.offset(offset[0], offset[1], offset[2]);
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        trunk.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                .thenComparingInt((BlockPos pos) -> pos.getX())
                .thenComparingInt((BlockPos pos) -> pos.getZ()));

        if (trunk.size() <= 1) {
            return new TreePlan(kind, immutableOrigin, List.copyOf(trunk), List.of(), false, false);
        }

        Set<BlockPos> trunkSet = new HashSet<>(trunk);
        FoliageResult foliage = scanFoliage(level, kind, trunk, trunkSet);
        return new TreePlan(kind, immutableOrigin, List.copyOf(trunk), foliage.positions(),
                foliage.truncated(), false);
    }

    private static FoliageResult scanFoliage(ServerLevel level, TreeKind kind, List<BlockPos> trunk,
            Set<BlockPos> trunkSet) {
        int radius = kind == TreeKind.MUSHROOM ? Integer.MAX_VALUE : TreeCapitatorConfig.leafRadius;
        int[][] neighbours = TreeCapitatorConfig.allowDiagonal ? ALL_NEIGHBOURS : FACE_NEIGHBOURS;

        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> foliage = new HashSet<>();
        ArrayDeque<FoliageNode> queue = new ArrayDeque<>();

        for (BlockPos trunkPos : trunk) {
            for (int[] offset : neighbours) {
                BlockPos candidate = trunkPos.offset(offset[0], offset[1], offset[2]);
                if (trunkSet.contains(candidate) || !visited.add(candidate)) {
                    continue;
                }

                BlockState state = getState(level, candidate);
                if (state != null && isFoliage(state, kind)) {
                    queue.add(new FoliageNode(candidate.immutable(), 1));
                }
            }
        }

        boolean truncated = false;
        while (!queue.isEmpty()) {
            FoliageNode node = queue.poll();
            if (node.distance() > radius) {
                continue;
            }

            BlockState state = getState(level, node.pos());
            if (state == null || !isFoliage(state, kind)) {
                continue;
            }

            foliage.add(node.pos());
            if (foliage.size() >= MAX_FOLIAGE) {
                truncated = true;
                break;
            }

            int nextDistance = node.distance() + 1;
            if (nextDistance > radius) {
                continue;
            }

            for (int[] offset : neighbours) {
                BlockPos next = node.pos().offset(offset[0], offset[1], offset[2]);
                if (trunkSet.contains(next) || !visited.add(next)) {
                    continue;
                }

                BlockState nextState = getState(level, next);
                if (nextState != null && isFoliage(nextState, kind)) {
                    queue.add(new FoliageNode(next.immutable(), nextDistance));
                }
            }
        }

        List<BlockPos> sorted = new ArrayList<>(foliage);
        sorted.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                .thenComparingInt((BlockPos pos) -> pos.getX())
                .thenComparingInt((BlockPos pos) -> pos.getZ()));
        return new FoliageResult(List.copyOf(sorted), truncated);
    }

    private static BlockState getState(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockState(pos);
    }

    private static int[][] buildNeighbours(boolean includeDiagonal) {
        List<int[]> offsets = new ArrayList<>();
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    int nonZero = (x == 0 ? 0 : 1) + (y == 0 ? 0 : 1) + (z == 0 ? 0 : 1);
                    if (nonZero == 0 || (!includeDiagonal && nonZero != 1)) {
                        continue;
                    }
                    offsets.add(new int[] { x, y, z });
                }
            }
        }
        return offsets.toArray(int[][]::new);
    }

    private record FoliageNode(BlockPos pos, int distance) {
    }

    private record FoliageResult(List<BlockPos> positions, boolean truncated) {
    }
}
