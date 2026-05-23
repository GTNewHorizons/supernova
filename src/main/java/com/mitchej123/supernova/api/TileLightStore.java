package com.mitchej123.supernova.api;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Thread-safe side store of packed RGB light values for positions whose emission is sourced from a {@link net.minecraft.tileentity.TileEntity}.
 * <p>
 * Tile-driven emission cannot be resolved by the Supernova worker thread via {@code World.getTileEntity}: the chunk's tile-entity map is mutated on the main
 * thread and is not safe to read off-thread. Instead, mod code on the main thread publishes the current packed RGB into this store from tile lifecycle hooks
 * (validate, invalidate, chunk load, state change); the worker reads from the store via {@link PositionalColoredLightSource} implementations (or via
 * {@link TileLightRegistry} registration for blocks that cannot implement the interface directly).
 *
 * @see TileLightRegistry
 * @see DimensionedBlockAccess
 */
public final class TileLightStore {

    private static final Int2ObjectMap<Long2IntMap> BY_DIM = Int2ObjectMaps.synchronize(new Int2ObjectOpenHashMap<>());

    private TileLightStore() {}

    private static long key(final int x, final int y, final int z) {
        return ((long) (x & 0x3FFFFFF) << 34) | ((long) (z & 0x3FFFFFF) << 8) | (y & 0xFF);
    }

    private static Long2IntMap getOrCreate(final int dimId) {
        synchronized (BY_DIM) {
            Long2IntMap inner = BY_DIM.get(dimId);
            if (inner == null) {
                final Long2IntOpenHashMap raw = new Long2IntOpenHashMap();
                raw.defaultReturnValue(0);
                inner = Long2IntMaps.synchronize(raw);
                BY_DIM.put(dimId, inner);
            }
            return inner;
        }
    }

    /**
     * Publish a packed RGB value for a position. A value of 0 removes the entry.
     *
     * @param dimId     dimension id
     * @param x         block x
     * @param y         block y
     * @param z         block z
     * @param packedRGB packed RGB via {@link PackedColorLight#pack(int, int, int)}
     */
    public static void put(final int dimId, final int x, final int y, final int z, final int packedRGB) {
        if (packedRGB == 0) {
            remove(dimId, x, y, z);
            return;
        }
        getOrCreate(dimId).put(key(x, y, z), packedRGB);
    }

    /**
     * @return packed RGB for the position, or 0 if absent
     */
    public static int get(final int dimId, final int x, final int y, final int z) {
        final Long2IntMap m = BY_DIM.get(dimId);
        return m == null ? 0 : m.get(key(x, y, z));
    }

    public static void remove(final int dimId, final int x, final int y, final int z) {
        final Long2IntMap m = BY_DIM.get(dimId);
        if (m != null) m.remove(key(x, y, z));
    }

    /**
     * Clear all entries for a dimension. Called by Supernova on {@code WorldEvent.Unload}.
     */
    public static void clearDim(final int dimId) {
        final Long2IntMap m;
        synchronized (BY_DIM) {
            m = BY_DIM.remove(dimId);
        }
        if (m != null) {
            synchronized (m) {
                m.clear();
            }
        }
    }

    /**
     * Resolve a dimension id from an {@link IBlockAccess}. Supports vanilla {@link World} and any access implementing {@link DimensionedBlockAccess} (e.g.
     * Supernova's worker-side {@code SafeBlockAccess}).
     *
     * @return the dimension id, or {@link Integer#MIN_VALUE} if it cannot be resolved
     */
    public static int dimensionOf(final IBlockAccess world) {
        if (world instanceof DimensionedBlockAccess) return ((DimensionedBlockAccess) world).getDimensionId();
        if (world instanceof World) return ((World) world).provider.dimensionId;
        return Integer.MIN_VALUE;
    }
}
