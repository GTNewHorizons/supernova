package com.mitchej123.supernova.api;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.config.SupernovaConfig;
import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Block RGB Registry
 * <p>
 * Lookup priority:
 * <ol>
 *   <li>{@link PositionalColoredLightSource} interface -- emission can vary by position and neighbors</li>
 *   <li>{@link ColoredLightSource} interface -- emission varies by meta only</li>
 *   <li>EasyColoredLights auto-detect -- {@code Block.getLightValue() > 15} decoded as ECL packed RGB</li>
 *   <li>Explicit registry entry (per-meta or wildcard)</li>
 *   <li>Vanilla fallback -- white at {@code Block.getLightValue()} intensity (precomputed table)</li>
 * </ol>
 * Register entries during {@code FMLInitializationEvent} or {@code FMLPostInitializationEvent}.
 */
public final class LightColorRegistry {

    private static final BlockIdIndex REGISTRY = new BlockIdIndex();
    private static final int[] WHITE_BY_LEVEL = new int[16];

    private static volatile BlockIdIndex.Cache EMISSION_CACHE = BlockIdIndex.Cache.EMPTY;
    private static volatile boolean[] POSITIONAL_CACHE = new boolean[0];
    private static final int UNCACHEABLE = -1;
    private static final Set<Class<?>> POSITIONAL_LIGHT_FAILED = ConcurrentHashMap.newKeySet();
    private static final ClassValue<Boolean> POSITIONAL_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            try {
                return type.getMethod("getLightValue", IBlockAccess.class, int.class, int.class, int.class).getDeclaringClass() != Block.class;
            } catch (final NoSuchMethodException e) {
                return false;
            } catch (final LinkageError e) {
                return true;
            }
        }
    };

    static {
        for (int i = 0; i < 16; i++) {
            WHITE_BY_LEVEL[i] = PackedColorLight.pack(i, i, i);
        }
    }

    private LightColorRegistry() {}

    /**
     * Returns {@code true} if the block has an explicit registry entry.
     *
     * @param block block to check
     */
    public static boolean hasExplicitEntry(Block block) {
        return REGISTRY.hasEntry(Block.getIdFromBlock(block));
    }

    /**
     * Returns {@code true} if the block ID has an explicit registry entry.
     *
     * @param blockId numeric block ID
     */
    public static boolean hasExplicitEntry(int blockId) {
        return REGISTRY.hasEntry(blockId);
    }

    /** Returns true only for explicit per-meta entries. */
    public static boolean isMetaSensitive(int blockId) {
        return REGISTRY.isMetaSensitive(blockId);
    }

    /** Includes overrides of positional {@code getLightValue}, not just {@link PositionalColoredLightSource}. */
    public static boolean isPositional(final Block block) {
        return block instanceof PositionalColoredLightSource || POSITIONAL_CLASS.get(block.getClass());
    }

    /** Positional status from the most recent {@link #buildCache()}. */
    public static boolean isPositional(final int blockId) {
        final boolean[] cache = POSITIONAL_CACHE;
        return blockId >= 0 && blockId < cache.length && cache[blockId];
    }

    /**
     * Register a colored emission for a specific block + meta.
     *
     * @param block       target block
     * @param meta        block metadata (>= 0)
     * @param packedColor packed RGB via {@link PackedColorLight#pack}
     */
    public static void register(Block block, int meta, int packedColor) {
        register(block, meta, PackedColorLight.red(packedColor), PackedColorLight.green(packedColor), PackedColorLight.blue(packedColor));
    }

    /**
     * Register a colored emission for all metas of a block.
     *
     * @param block       target block
     * @param packedColor packed RGB via {@link PackedColorLight#pack}
     */
    public static void register(Block block, int packedColor) {
        register(block, PackedColorLight.red(packedColor), PackedColorLight.green(packedColor), PackedColorLight.blue(packedColor));
    }

    /**
     * Register a colored emission for a specific block + meta.
     *
     * @param block target block
     * @param meta  block metadata (>= 0)
     * @param r     red channel (0-15)
     * @param g     green channel (0-15)
     * @param b     blue channel (0-15)
     */
    public static void register(Block block, int meta, int r, int g, int b) {
        REGISTRY.putMeta(block, meta, scaleToVanillaLight(PackedColorLight.pack(r, g, b), block.getLightValue()) + 1);
    }

    /**
     * Register a colored emission for all metas of a block.
     *
     * @param block target block
     * @param r     red channel (0-15)
     * @param g     green channel (0-15)
     * @param b     blue channel (0-15)
     */
    public static void register(Block block, int r, int g, int b) {
        REGISTRY.put(block, new int[] { scaleToVanillaLight(PackedColorLight.pack(r, g, b), block.getLightValue()) + 1 });
    }

    /**
     * Resolves emission at a position. Positional queries that throw fall back to metadata-only
     * emission or static light.
     *
     * @param world block access for positional queries
     * @param block block instance
     * @param meta block metadata
     * @param x block x
     * @param y block y
     * @param z block z
     * @return packed RGB, or 0 for no emission
     */
    public static int getPackedEmission(IBlockAccess world, Block block, int meta, int x, int y, int z) {
        if (block instanceof PositionalColoredLightSource) {
            if (!(world instanceof net.minecraft.world.World) && POSITIONAL_LIGHT_FAILED.contains(block.getClass())) {
                return ((ColoredLightSource) block).getColoredLightEmission(meta);
            }
            try {
                return ((PositionalColoredLightSource) block).getColoredLightEmission(world, meta, x, y, z);
            } catch (final RuntimeException e) {
                if (!(world instanceof net.minecraft.world.World) && POSITIONAL_LIGHT_FAILED.add(block.getClass())) {
                    Supernova.LOG.warn("{} positional colored emission threw off-thread; using non-positional emission", block.getClass().getName(), e);
                }
                return ((ColoredLightSource) block).getColoredLightEmission(meta);
            }
        }
        if (block instanceof ColoredLightSource) {
            return ((ColoredLightSource) block).getColoredLightEmission(meta);
        }

        // A worker's SafeBlockAccess has no tile entities; real-world samples still retry a class that failed there.
        int rawLight;
        if (!(world instanceof net.minecraft.world.World) && !POSITIONAL_LIGHT_FAILED.isEmpty() && POSITIONAL_LIGHT_FAILED.contains(block.getClass())) {
            rawLight = block.getLightValue();
        } else {
            try {
                rawLight = block.getLightValue(world, x, y, z);
            } catch (final RuntimeException e) {
                if (!(world instanceof net.minecraft.world.World) && POSITIONAL_LIGHT_FAILED.add(block.getClass())) {
                    Supernova.LOG.warn("{} getLightValue(world, x, y, z) threw off-thread; using static light value", block.getClass().getName(), e);
                }
                rawLight = block.getLightValue();
            }
        }
        return resolve(block, rawLight, meta);
    }

    private static int resolve(final Block block, final int rawLight, final int meta) {
        if (rawLight > 15) {
            final int ecl = decodeECL(rawLight);
            if (ecl != 0) return ecl;
        }
        final int registered = REGISTRY.lookup(Block.getIdFromBlock(block), meta);
        if (registered >= 0) return registered;
        final int vanillaLight = rawLight & 0xF;
        return vanillaLight > 0 ? WHITE_BY_LEVEL[vanillaLight] : 0;
    }

    /**
     * Cache miss falls back to {@link #getPackedEmissionNoWorld(Block, int)}, never positional lookup.
     *
     * @param blockId numeric block ID
     * @param meta block metadata
     * @return packed RGB, or 0 for no emission
     */
    public static int getPackedEmissionCached(int blockId, int meta) {
        final int cached = EMISSION_CACHE.lookup(blockId, meta, UNCACHEABLE);
        return cached != UNCACHEABLE ? cached : getPackedEmissionNoWorld(Block.getBlockById(blockId), meta);
    }

    /**
     * Resolve the packed RGB emission without world access. Skips {@link PositionalColoredLightSource} (requires world context) and positional
     * {@code getLightValue}. Checks static {@link ColoredLightSource} interface. Use during chunk generation or when world is unavailable.
     *
     * @param block block instance
     * @param meta  block metadata
     * @return packed RGB via {@link PackedColorLight#pack}, or 0 if no emission
     */
    public static int getPackedEmissionNoWorld(Block block, int meta) {
        if (block instanceof ColoredLightSource) {
            return ((ColoredLightSource) block).getColoredLightEmission(meta);
        }

        return resolve(block, block.getLightValue(), meta);
    }

    /**
     * Iterate all explicit registry entries (internal/debug use).
     * <p>
     * Raw entry array: length 1 = wildcard, length N = per-meta. Values are stored as {@code packed + 1} so 0 marks unregistered slots; decode with
     * {@code PackedColorLight.red/green/blue(value - 1)}.
     */
    public static void forEach(BiConsumer<Block, int[]> consumer) {
        REGISTRY.forEach(consumer);
    }

    /** Reindexes registrations after block IDs change; follow with {@link #buildCache()}. */
    public static void rebuildIdMappings() {
        REGISTRY.rebuild();
    }

    /** Rebuild after registrations or ID remapping, on the main thread outside lighting queries. */
    @SuppressWarnings("unchecked")
    public static void buildCache() {
        int maxId = 0;
        for (final Block block : (Iterable<Block>) Block.blockRegistry) {
            final int bid = Block.getIdFromBlock(block);
            if (bid > maxId) maxId = bid;
        }

        final int[] emissionCache = new int[maxId + 1];
        final int[][] emissionCachePerMeta = new int[maxId + 1][];
        final boolean[] positionalCache = new boolean[maxId + 1];
        Arrays.fill(emissionCache, UNCACHEABLE);

        int cachedUniform = 0, cachedPerMeta = 0;

        for (final Block block : (Iterable<Block>) Block.blockRegistry) {
            final int bid = Block.getIdFromBlock(block);
            if (bid < 0) continue;
            positionalCache[bid] = isPositional(block);

            // uncacheable: needs world + pos
            if (block instanceof PositionalColoredLightSource) {
                emissionCache[bid] = UNCACHEABLE;
                continue;
            }

            if (block instanceof ColoredLightSource src) {
                final int meta0 = src.getColoredLightEmission(0);
                boolean allSame = true;
                for (int m = 1; m < 16; m++) {
                    if (src.getColoredLightEmission(m) != meta0) {
                        allSame = false;
                        break;
                    }
                }
                if (allSame) {
                    emissionCache[bid] = meta0;
                    cachedUniform++;
                } else {
                    final int[] perMeta = new int[16];
                    perMeta[0] = meta0;
                    for (int m = 1; m < 16; m++) perMeta[m] = src.getColoredLightEmission(m);
                    emissionCache[bid] = UNCACHEABLE;
                    emissionCachePerMeta[bid] = perMeta;
                    cachedPerMeta++;
                }
                continue;
            }

            final int[] entry = REGISTRY.entryFor(bid);
            // Metas past 15 are only reachable through the direct path.
            final boolean metasPast15 = entry != null && entry.length > 16;
            final int first = getPackedEmissionNoWorld(block, 0);
            final int[] perMeta = new int[16];
            perMeta[0] = first;
            boolean allSame = true;
            for (int m = 1; m < 16; m++) {
                perMeta[m] = getPackedEmissionNoWorld(block, m);
                if (perMeta[m] != first) allSame = false;
            }
            if (allSame && !metasPast15) {
                emissionCache[bid] = first;
                cachedUniform++;
            } else {
                emissionCache[bid] = UNCACHEABLE;
                emissionCachePerMeta[bid] = perMeta;
                cachedPerMeta++;
            }
        }

        EMISSION_CACHE = new BlockIdIndex.Cache(emissionCache, emissionCachePerMeta);
        POSITIONAL_CACHE = positionalCache;

        Supernova.LOG.info("LightColorRegistry: cached emission for {} uniform + {} per-meta blocks", cachedUniform, cachedPerMeta);
    }

    /** ECL spacer bits (4, 9, 14, 19) must be zero in valid {@code 0RRRR 0GGGG 0BBBB 0LLLL} format. */
    private static final int ECL_SPACER_MASK = (1 << 4) | (1 << 9) | (1 << 14) | (1 << 19);

    /**
     * Decode an EasyColoredLights-format packed value ({@code 0RRRR 0GGGG 0BBBB 0LLLL}) into Supernova's
     * {@link PackedColorLight#pack} format. Returns 0 if the value is not valid ECL or has no RGB channels.
     */
    private static int decodeECL(int eclValue) {
        if ((eclValue & ECL_SPACER_MASK) != 0) return 0;
        final int r = (eclValue >>> 5) & 0xF;
        final int g = (eclValue >>> 10) & 0xF;
        final int b = (eclValue >>> 15) & 0xF;
        if ((r | g | b) == 0) return 0;
        return PackedColorLight.pack(r, g, b);
    }

    /**
     * Scale a packed RGB so its max channel is at least {@code vanillaLight}, preserving channel ratios.
     */
    static int scaleToVanillaLight(int packed, int vanillaLight) {
        if (!SupernovaConfig.scaleEmissionToVanillaLight) return packed;
        final int v = vanillaLight & 0xF;
        if (v <= 0) return packed;
        final int r = PackedColorLight.red(packed);
        final int g = PackedColorLight.green(packed);
        final int b = PackedColorLight.blue(packed);
        final int max = Math.max(r, Math.max(g, b));
        if (max <= 0 || max >= v) return packed;
        final int half = max >> 1;
        final int nr = Math.min(15, (r * v + half) / max);
        final int ng = Math.min(15, (g * v + half) / max);
        final int nb = Math.min(15, (b * v + half) / max);
        return PackedColorLight.pack(nr, ng, nb);
    }

    static void clearForTest() {
        REGISTRY.clear();
        EMISSION_CACHE = BlockIdIndex.Cache.EMPTY;
        POSITIONAL_CACHE = new boolean[0];
        POSITIONAL_LIGHT_FAILED.clear();
    }
}
