package com.treecapitator;

import java.util.Comparator;

import net.minecraft.core.BlockPos;

/**
 * Immutable result of a tree scan. The trunk and foliage arrays are built by
 * {@link TreeScanner} and must not be modified. The trunk array is sorted from
 * the bottom upwards so felling order is deterministic.
 */
public record TreePlan(
        TreeKind kind,
        BlockPos origin,
        BlockPos[] trunk,
        BlockPos[] foliage,
        boolean foliageTruncated,
        boolean tooLarge
) {
    public int trunkCount() {
        return trunk.length;
    }

    public boolean hasEnoughFoliage(int minimum) {
        return foliage.length >= minimum;
    }

    /** Bottom-up ordering shared by trunk and foliage sorting. */
    static final Comparator<BlockPos> BOTTOM_UP = Comparator
            .comparingInt((BlockPos pos) -> pos.getY())
            .thenComparingInt(pos -> pos.getX())
            .thenComparingInt(pos -> pos.getZ());
}
