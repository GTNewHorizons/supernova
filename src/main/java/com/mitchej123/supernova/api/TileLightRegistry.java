package com.mitchej123.supernova.api;

import net.minecraft.block.Block;

import java.util.BitSet;

/**
 * Registry of blocks whose light emission is published to {@link TileLightStore} from a tile entity, but which cannot (or do not) implement
 * {@link PositionalColoredLightSource} directly.
 * <p>
 * Registered blocks are forced UNCACHEABLE in {@link LightColorRegistry}'s flat emission cache and routed through {@link TileLightStore} for every lookup.
 * <p>
 * Use this when you control the tile entity (and so can publish to the store) but do not control the block class -- e.g. a third-party mod whose block you
 * cannot modify. If you do control the block, prefer implementing {@link PositionalColoredLightSource} on it directly.
 *
 * @see TileLightStore
 */
public final class TileLightRegistry {

    private static final BitSet REGISTERED = new BitSet();

    private TileLightRegistry() {}

    /**
     * Register a block as tile-light-sourced. Call during {@code FMLInitializationEvent} or {@code FMLPostInitializationEvent} (before
     * {@link LightColorRegistry#buildCache()} runs).
     */
    public static void register(final Block block) {
        final int id = Block.getIdFromBlock(block);
        if (id >= 0) REGISTERED.set(id);
    }

    public static boolean contains(final Block block) {
        final int id = Block.getIdFromBlock(block);
        return id >= 0 && REGISTERED.get(id);
    }

    public static boolean contains(final int blockId) {
        return blockId >= 0 && REGISTERED.get(blockId);
    }
}
