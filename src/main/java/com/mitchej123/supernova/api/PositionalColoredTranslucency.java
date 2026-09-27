package com.mitchej123.supernova.api;

import net.minecraft.world.IBlockAccess;

/**
 * Extension of {@link ColoredTranslucency} for blocks whose per-channel transmission varies by position.
 *
 * @see TranslucencyRegistry
 */
public interface PositionalColoredTranslucency extends ColoredTranslucency {

    /**
     * Returns per-channel transmittance at the given position as packed RGB (0-15 per channel, 15=fully transparent, 0=fully opaque).
     *
     * @param world block access
     * @param meta  block metadata
     * @param x     block x
     * @param y     block y
     * @param z     block z
     * @return packed RGB transmittance via {@link PackedColorLight#pack}
     */
    int getColoredTransmittance(IBlockAccess world, int meta, int x, int y, int z);
}
