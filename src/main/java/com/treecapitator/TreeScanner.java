package com.treecapitator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

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
 *
 * <p>Positions are tracked as packed {@code long} values in primitive hash sets
 * and lists, and a single reusable {@link BlockPos.MutableBlockPos} is used for
 * every world lookup. Scanning therefore allocates no objects per visited
 * neighbour and reads each candidate block state exactly once.</p>
 */
public final class TreeScanner {
    private static final int MAX_FOLIAGE = 4096;
    private static final BlockPos[] NO_POSITIONS = new BlockPos[0];
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

    public static TreePlan scan(ServerLevel level, BlockPos origin, BlockState originState, TreeKind kind) {
        if (kind == null) {
            return null;
        }

        BlockPos immutableOrigin = origin.immutable();
        long originKey = immutableOrigin.asLong();
        int[][] neighbours = TreeCapitatorConfig.allowDiagonal ? ALL_NEIGHBOURS : FACE_NEIGHBOURS;
        int maxLogs = TreeCapitatorConfig.maxLogs;

        LongOpenHashSet visited = new LongOpenHashSet();
        LongArrayList trunkKeys = new LongArrayList();
        LongArrayList queue = new LongArrayList();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        visited.add(originKey);
        queue.add(originKey);

        // Breadth-first search over packed positions; 'queue' doubles as the
        // visited work list, so no iterator or per-node object is allocated.
        for (int head = 0; head < queue.size(); head++) {
            long key = queue.getLong(head);
            BlockState state = key == originKey ? originState : level.getBlockState(cursor.set(key));

            if (!isTrunk(state, kind)) {
                continue;
            }

            trunkKeys.add(key);
            if (trunkKeys.size() > maxLogs) {
                return new TreePlan(kind, immutableOrigin, NO_POSITIONS, NO_POSITIONS, false, true);
            }

            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            for (int[] offset : neighbours) {
                long nextKey = cursor.set(x + offset[0], y + offset[1], z + offset[2]).asLong();
                if (visited.add(nextKey)) {
                    queue.add(nextKey);
                }
            }
        }

        if (trunkKeys.size() <= 1) {
            return new TreePlan(kind, immutableOrigin, toSortedPositions(trunkKeys), NO_POSITIONS, false, false);
        }

        // Foliage data is only needed for the leaf threshold or for removal.
        boolean needFoliage = TreeCapitatorConfig.minLeaves > 0
                || (kind == TreeKind.MUSHROOM
                        ? TreeCapitatorConfig.breakMushroomCaps
                        : TreeCapitatorConfig.breakLeaves);
        if (!needFoliage) {
            return new TreePlan(kind, immutableOrigin, toSortedPositions(trunkKeys), NO_POSITIONS, false, false);
        }

        LongOpenHashSet trunkSet = new LongOpenHashSet(trunkKeys.size());
        for (int i = 0; i < trunkKeys.size(); i++) {
            trunkSet.add(trunkKeys.getLong(i));
        }

        FoliageResult foliage = scanFoliage(level, kind, trunkKeys, trunkSet);
        BlockPos[] trunk = toSortedPositions(trunkKeys);

        // Trees below the leaf threshold are rejected by the caller, so avoid
        // materialising and sorting foliage that will never be used.
        if (foliage.keys().size() < TreeCapitatorConfig.minLeaves) {
            return new TreePlan(kind, immutableOrigin, trunk, NO_POSITIONS, foliage.truncated(), false);
        }

        return new TreePlan(kind, immutableOrigin, trunk, toSortedPositions(foliage.keys()),
                foliage.truncated(), false);
    }

    private static FoliageResult scanFoliage(ServerLevel level, TreeKind kind, LongArrayList trunkKeys,
            LongOpenHashSet trunkSet) {
        int radius = kind == TreeKind.MUSHROOM ? Integer.MAX_VALUE : TreeCapitatorConfig.leafRadius;
        int[][] neighbours = TreeCapitatorConfig.allowDiagonal ? ALL_NEIGHBOURS : FACE_NEIGHBOURS;

        LongOpenHashSet visited = new LongOpenHashSet();
        LongArrayList foliage = new LongArrayList();
        LongArrayList current = new LongArrayList();
        LongArrayList next = new LongArrayList();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // Seed the search from every block touching the trunk.
        for (int i = 0; i < trunkKeys.size(); i++) {
            long trunkKey = trunkKeys.getLong(i);
            int x = BlockPos.getX(trunkKey);
            int y = BlockPos.getY(trunkKey);
            int z = BlockPos.getZ(trunkKey);
            for (int[] offset : neighbours) {
                long key = cursor.set(x + offset[0], y + offset[1], z + offset[2]).asLong();
                if (trunkSet.contains(key) || !visited.add(key)) {
                    continue;
                }
                if (isFoliage(level.getBlockState(cursor), kind)) {
                    current.add(key);
                }
            }
        }

        // Level-order BFS: distance grows by one per layer, so no per-node
        // distance field is needed. Expansion stops at the configured radius.
        boolean truncated = false;
        for (int distance = 1; !current.isEmpty() && distance <= radius; distance++) {
            for (int i = 0; i < current.size(); i++) {
                long key = current.getLong(i);
                foliage.add(key);
                if (foliage.size() >= MAX_FOLIAGE) {
                    truncated = true;
                    break;
                }

                if (distance >= radius) {
                    continue;
                }

                int x = BlockPos.getX(key);
                int y = BlockPos.getY(key);
                int z = BlockPos.getZ(key);
                for (int[] offset : neighbours) {
                    long nextKey = cursor.set(x + offset[0], y + offset[1], z + offset[2]).asLong();
                    if (trunkSet.contains(nextKey) || !visited.add(nextKey)) {
                        continue;
                    }
                    if (isFoliage(level.getBlockState(cursor), kind)) {
                        next.add(nextKey);
                    }
                }
            }

            if (truncated) {
                break;
            }

            LongArrayList swap = current;
            current = next;
            next = swap;
            next.clear();
        }

        return new FoliageResult(foliage, truncated);
    }

    private static BlockPos[] toSortedPositions(LongArrayList keys) {
        BlockPos[] positions = new BlockPos[keys.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = BlockPos.of(keys.getLong(i));
        }
        Arrays.sort(positions, TreePlan.BOTTOM_UP);
        return positions;
    }

    private static int[][] buildNeighbours(boolean includeDiagonal) {
        List<int[]> result = new ArrayList<>();
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    int nonZero = (x == 0 ? 0 : 1) + (y == 0 ? 0 : 1) + (z == 0 ? 0 : 1);
                    if (nonZero == 0 || (!includeDiagonal && nonZero != 1)) {
                        continue;
                    }
                    result.add(new int[] { x, y, z });
                }
            }
        }
        return result.toArray(int[][]::new);
    }

    private record FoliageResult(LongArrayList keys, boolean truncated) {
    }
}
