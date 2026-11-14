package com.treecapitator;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Immutable result of a tree scan. The trunk list is sorted from the bottom
 * upwards so felling order is deterministic.
 */
public record TreePlan(
        TreeKind kind,
        BlockPos origin,
        List<BlockPos> trunk,
        List<BlockPos> foliage,
        boolean foliageTruncated,
        boolean tooLarge
) {
    public int trunkCount() {
        return trunk.size();
    }

    public boolean hasEnoughFoliage(int minimum) {
        return foliage.size() >= minimum;
    }
}
