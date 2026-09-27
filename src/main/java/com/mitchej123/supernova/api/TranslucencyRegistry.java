package com.mitchej123.supernova.api;

import com.mitchej123.supernova.Supernova;
import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Absorption priority: positional transmittance, metadata transmittance, registration, then
 * vanilla opacity (minimum 1). Registration takes transmittance (15 = clear); queries return
 * absorption (0 = clear). Directional faces resolve separately. Register before {@link #buildCache()}.
 */
public final class TranslucencyRegistry {

    private static final BlockIdIndex REGISTRY = new BlockIdIndex();

    /** Marked by external callers, e.g. FaceOcclusion for directional blocks. */
    private static final Set<Block> UNCACHEABLE_BLOCKS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile BitSet FORCE_UNCACHEABLE = new BitSet();

    private static volatile BlockIdIndex.Cache ABSORPTION_CACHE = BlockIdIndex.Cache.EMPTY;
    private static final int UNCACHEABLE = -1;

    private TranslucencyRegistry() {}

    /**
     * Register per-channel transmittance for all metas of a block.
     *
     * @param block target block
     * @param r     red transmittance (0-15, 15=fully transparent)
     * @param g     green transmittance
     * @param b     blue transmittance
     */
    public static void registerTransmittance(Block block, int r, int g, int b) {
        final int absorption = PackedColorLight.pack(15 - r, 15 - g, 15 - b);
        REGISTRY.put(block, new int[] { absorption + 1 });
    }

    /**
     * Register per-channel transmittance for a specific block + meta.
     *
     * @param block target block
     * @param meta  block metadata (&gt;= 0)
     * @param r     red transmittance (0-15, 15=fully transparent)
     * @param g     green transmittance
     * @param b     blue transmittance
     */
    public static void registerTransmittance(Block block, int meta, int r, int g, int b) {
        REGISTRY.putMeta(block, meta, PackedColorLight.pack(15 - r, 15 - g, 15 - b) + 1);
    }

    /**
     * Returns the packed absorption without checking the {@link ColoredTranslucency} interface. For blocks known not to implement it, or when coordinates are
     * unavailable.
     *
     * @param block block instance
     * @param meta  block metadata
     * @return packed absorption via {@link PackedColorLight#pack}
     */
    public static int getPackedAbsorptionNoInterface(Block block, int meta) {
        return getPackedAbsorptionNoInterface(Block.getIdFromBlock(block), block, meta);
    }

    /**
     * ID-accepting overload -- avoids redundant {@code Block.getIdFromBlock()} when caller already has the ID.
     */
    public static int getPackedAbsorptionNoInterface(int blockId, Block block, int meta) {
        final int registered = REGISTRY.lookup(blockId, meta);
        if (registered >= 0) return registered;
        // Vanilla minimum-1 attenuation: light decays by at least 1 per block, uniformly across channels.
        final int opacity = Math.max(1, block.getLightOpacity());
        return PackedColorLight.pack(opacity, opacity, opacity);
    }

    /**
     * Resolve the packed absorption for a block at a position. Full lookup chain:
     * {@link PositionalColoredTranslucency} -> {@link ColoredTranslucency} -> registry -> vanilla fallback.
     * Does not handle directional concerns (FaceLightOcclusion, sided transparency) -- use
     * {@code FaceOcclusion.resolveAbsorption()} for that.
     *
     * @param world block access for positional queries
     * @param block block instance
     * @param meta  block metadata
     * @param x     block x
     * @param y     block y
     * @param z     block z
     * @return packed absorption via {@link PackedColorLight#pack}
     */
    public static int getPackedAbsorption(IBlockAccess world, Block block, int meta, int x, int y, int z) {
        if (block instanceof PositionalColoredTranslucency) {
            return PackedColorLight.transmittanceToAbsorption(
                    ((PositionalColoredTranslucency) block).getColoredTransmittance(world, meta, x, y, z));
        }
        if (block instanceof ColoredTranslucency) {
            return PackedColorLight.transmittanceToAbsorption(
                    ((ColoredTranslucency) block).getColoredTransmittance(meta));
        }
        return getPackedAbsorptionNoInterface(block, meta);
    }

    /**
     * Looks up packed absorption without world or face context.
     *
     * @param blockId numeric block ID
     * @param meta block metadata
     * @return packed RGB absorption, or -1 if positional, directional, or outside the cache
     */
    public static int getPackedAbsorptionCached(int blockId, int meta) {
        return ABSORPTION_CACHE.lookup(blockId, meta, UNCACHEABLE);
    }

    /**
     * Excludes a block from the cache. Call before {@link #rebuildIdMappings()} and {@link #buildCache()}.
     *
     * @param blockId numeric block ID
     */
    public static void markUncacheable(int blockId) {
        if (blockId < 0) return;
        final Block block = Block.getBlockById(blockId);
        // getBlockById returns Blocks.air, not null, for an unmapped id.
        if (Block.getIdFromBlock(block) == blockId) UNCACHEABLE_BLOCKS.add(block);
    }

    /** Clears externally marked exclusions before rescanning directional blocks and rebuilding. */
    public static void clearUncacheable() {
        UNCACHEABLE_BLOCKS.clear();
    }

    /** Rebuild after registrations or ID remapping, on the main thread outside lighting queries. */
    @SuppressWarnings("unchecked")
    public static void buildCache() {
        int maxId = 0;
        for (final Block block : (Iterable<Block>) Block.blockRegistry) {
            final int bid = Block.getIdFromBlock(block);
            if (bid > maxId) maxId = bid;
        }

        final int[] absorptionCache = new int[maxId + 1];
        final int[][] absorptionCachePerMeta = new int[maxId + 1][];
        Arrays.fill(absorptionCache, UNCACHEABLE);

        int cachedUniform = 0, cachedPerMeta = 0;
        final BitSet forceUncacheable = FORCE_UNCACHEABLE;

        for (final Block block : (Iterable<Block>) Block.blockRegistry) {
            final int bid = Block.getIdFromBlock(block);
            if (bid < 0) continue;

            // uncacheable: needs world + position
            if (block instanceof PositionalColoredTranslucency) {
                absorptionCache[bid] = UNCACHEABLE;
                continue;
            }

            // uncacheable: FaceLightOcclusion or sided transparency
            if (forceUncacheable.get(bid)) {
                absorptionCache[bid] = UNCACHEABLE;
                continue;
            }

            if (block instanceof ColoredTranslucency ct) {
                boolean allSame = true;
                final int abs0 = PackedColorLight.transmittanceToAbsorption(ct.getColoredTransmittance(0));
                final int[] perMeta = new int[16];
                perMeta[0] = abs0;
                for (int m = 1; m < 16; m++) {
                    perMeta[m] = PackedColorLight.transmittanceToAbsorption(ct.getColoredTransmittance(m));
                    if (perMeta[m] != abs0) allSame = false;
                }
                if (allSame) {
                    absorptionCache[bid] = abs0;
                    cachedUniform++;
                } else {
                    absorptionCache[bid] = UNCACHEABLE;
                    absorptionCachePerMeta[bid] = perMeta;
                    cachedPerMeta++;
                }
                continue;
            }

            if (hasExplicitEntry(bid)) {
                // Metas past 15 are only reachable through the direct path.
                final boolean metasPast15 = REGISTRY.entryFor(bid).length > 16;
                boolean allSame = true;
                final int abs0 = getPackedAbsorptionNoInterface(bid, block, 0);
                final int[] perMeta = new int[16];
                perMeta[0] = abs0;
                for (int m = 1; m < 16; m++) {
                    perMeta[m] = getPackedAbsorptionNoInterface(bid, block, m);
                    if (perMeta[m] != abs0) allSame = false;
                }
                if (allSame && !metasPast15) {
                    absorptionCache[bid] = abs0;
                    cachedUniform++;
                } else {
                    absorptionCache[bid] = UNCACHEABLE;
                    absorptionCachePerMeta[bid] = perMeta;
                    cachedPerMeta++;
                }
                continue;
            }

            absorptionCache[bid] = getPackedAbsorptionNoInterface(bid, block, 0);
            cachedUniform++;
        }

        ABSORPTION_CACHE = new BlockIdIndex.Cache(absorptionCache, absorptionCachePerMeta);

        Supernova.LOG.info("TranslucencyRegistry: cached absorption for {} uniform + {} per-meta blocks", cachedUniform, cachedPerMeta);
    }

    /**
     * Returns {@code true} if the block has an explicit registry entry.
     */
    public static boolean hasExplicitEntry(Block block) {
        return hasExplicitEntry(Block.getIdFromBlock(block));
    }

    /**
     * Returns {@code true} if the block ID has an explicit registry entry.
     */
    public static boolean hasExplicitEntry(int blockId) {
        return REGISTRY.hasEntry(blockId);
    }

    /** Returns true only for explicit per-meta entries. */
    public static boolean isMetaSensitive(int blockId) {
        return REGISTRY.isMetaSensitive(blockId);
    }

    /**
     * Iterate all explicit registry entries. Raw entry array values are {@code packedAbsorption + 1};
     * 0 marks unregistered slots. Length 1 = wildcard, length N = per-meta.
     */
    public static void forEach(BiConsumer<Block, int[]> consumer) {
        REGISTRY.forEach(consumer);
    }

    /** Reindexes registrations and exclusions after block IDs change; follow with {@link #buildCache()}. */
    public static void rebuildIdMappings() {
        REGISTRY.rebuild();
        final BitSet forceUncacheable = new BitSet();
        for (final Block block : UNCACHEABLE_BLOCKS) {
            final int id = Block.getIdFromBlock(block);
            if (id >= 0) forceUncacheable.set(id);
        }
        FORCE_UNCACHEABLE = forceUncacheable;
    }

    static void clearForTest() {
        REGISTRY.clear();
        UNCACHEABLE_BLOCKS.clear();
        FORCE_UNCACHEABLE = new BitSet();
        ABSORPTION_CACHE = BlockIdIndex.Cache.EMPTY;
    }
}
