package com.mitchej123.supernova.api;

import net.minecraft.world.IBlockAccess;

/**
 * Optional capability for {@link IBlockAccess} implementations that can report their dimension id without an enclosing {@code World} reference.
 * <p>
 * Implemented by Supernova's worker-thread {@code SafeBlockAccess} so off-thread API consumers (e.g. {@link TileLightStore}) can resolve a dimension id without
 * cross-thread {@code World} access.
 */
public interface DimensionedBlockAccess {

    int getDimensionId();
}
