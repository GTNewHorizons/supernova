package com.mitchej123.supernova.api;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.config.SupernovaConfig;
import cpw.mods.fml.common.registry.GameData;
import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

import java.util.Arrays;
import java.util.BitSet;
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

    // packed+1 per meta (0 = unregistered). length==1 -> wildcard for all metas. Indexed by block ID.
    private static int[][] REGISTRY_BY_ID = new int[0][];
    private static final BitSet HAS_ENTRY = new BitSet();
    private static final int[] WHITE_BY_LEVEL = new int[16];

    /** Flat emission cache indexed by blockId. UNCACHEABLE for positional/interface blocks. */
    private static int[] EMISSION_CACHE = new int[0];
    /** Per-meta emission cache. Non-null entries hold meta 0-15 packed values. */
    private static int[][] EMISSION_CACHE_PER_META = new int[0][];
    private static final int UNCACHEABLE = -1;

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
        final int id = Block.getIdFromBlock(block);
        return id >= 0 && HAS_ENTRY.get(id);
    }

    /**
     * Returns {@code true} if the block ID has an explicit registry entry.
     *
     * @param blockId numeric block ID
     */
    public static boolean hasExplicitEntry(int blockId) {
        return blockId >= 0 && HAS_ENTRY.get(blockId);
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
        if (meta < 0) {
            throw new IllegalArgumentException("meta must be >= 0, got " + meta);
        }
        final int id = Block.getIdFromBlock(block);
        ensureCapacity(id);
        int[] entry = REGISTRY_BY_ID[id];
        if (entry == null) {
            entry = new int[Math.max(meta + 1, 16)];
        } else if (entry.length == 1) {
            // Expand wildcard to per-meta array
            final int wildcard = entry[0];
            entry = new int[Math.max(meta + 1, 16)];
            Arrays.fill(entry, wildcard);
        } else if (meta >= entry.length) {
            entry = Arrays.copyOf(entry, meta + 1);
        }
        entry[meta] = scaleToVanillaLight(PackedColorLight.pack(r, g, b), block.getLightValue()) + 1; // +1 so 0 = unregistered
        REGISTRY_BY_ID[id] = entry;
        markEntry(block);
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
        final int id = Block.getIdFromBlock(block);
        ensureCapacity(id);
        REGISTRY_BY_ID[id] = new int[] { scaleToVanillaLight(PackedColorLight.pack(r, g, b), block.getLightValue()) + 1 };
        markEntry(block);
    }

    /**
     * Resolve the packed RGB emission for a block at a position.
     *
     * @param world block access for positional queries
     * @param block block instance
     * @param meta  block metadata
     * @param x     block x
     * @param y     block y
     * @param z     block z
     * @return packed RGB via {@link PackedColorLight#pack}, or 0 if no emission
     */
    public static int getPackedEmission(IBlockAccess world, Block block, int meta, int x, int y, int z) {
        if (block instanceof PositionalColoredLightSource) {
            return ((PositionalColoredLightSource) block).getColoredLightEmission(world, meta, x, y, z);
        }
        if (block instanceof ColoredLightSource) {
            return ((ColoredLightSource) block).getColoredLightEmission(meta);
        }

        // Tile-light registry: emission published from a tile entity via TileLightStore.
        if (TileLightRegistry.contains(block)) {
            final int dim = TileLightStore.dimensionOf(world);
            return dim == Integer.MIN_VALUE ? 0 : TileLightStore.get(dim, x, y, z);
        }

        // EasyColoredLights auto-detect
        final int rawLight = block.getLightValue(world, x, y, z);
        if (rawLight > 15) {
            final int ecl = decodeECL(rawLight);
            if (ecl != 0) return ecl;
        }

        final int blockId = Block.getIdFromBlock(block);
        if (blockId >= 0 && blockId < REGISTRY_BY_ID.length) {
            final int[] entry = REGISTRY_BY_ID[blockId];
            if (entry != null) {
                final int result = lookupRegistry(entry, meta);
                if (result >= 0) return result;
            }
        }

        // Vanilla fallback
        final int vanillaLight = rawLight & 0xF;
        return vanillaLight > 0 ? WHITE_BY_LEVEL[vanillaLight] : 0;
    }

    /**
     * Fast-path emission lookup using the flat cache. Falls back to full lookup on UNCACHEABLE.
     *
     * @param blockId numeric block ID
     * @param block   block instance
     * @param meta    block metadata
     * @return packed RGB via {@link PackedColorLight#pack}, or 0 if no emission
     */
    public static int getPackedEmissionCached(int blockId, Block block, int meta) {
        if (blockId >= 0 && blockId < EMISSION_CACHE.length) {
            final int cached = EMISSION_CACHE[blockId];
            if (cached != UNCACHEABLE) return cached;
            final int[] perMeta = EMISSION_CACHE_PER_META[blockId];
            if (perMeta != null && meta >= 0 && meta < perMeta.length) return perMeta[meta];
        }
        return getPackedEmissionNoWorld(block, meta);
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

        // EasyColoredLights auto-detect
        final int rawLight = block.getLightValue();
        if (rawLight > 15) {
            final int ecl = decodeECL(rawLight);
            if (ecl != 0) return ecl;
        }

        final int blockId = Block.getIdFromBlock(block);
        if (blockId >= 0 && blockId < REGISTRY_BY_ID.length) {
            final int[] entry = REGISTRY_BY_ID[blockId];
            if (entry != null) {
                final int result = lookupRegistry(entry, meta);
                if (result >= 0) return result;
            }
        }

        final int vanillaLight = rawLight & 0xF;
        return vanillaLight > 0 ? WHITE_BY_LEVEL[vanillaLight] : 0;
    }

    // Values stored as packed+1 so 0 can represent "unregistered".

    /** @return packed emission, or -1 if not found */
    private static int lookupRegistry(final int[] entry, final int meta) {
        if (entry.length == 1) return entry[0] - 1;
        if (meta >= 0 && meta < entry.length) {
            final int v = entry[meta];
            if (v != 0) return v - 1;
        }
        return -1;
    }

    /**
     * Iterate all explicit registry entries (internal/debug use).
     * <p>
     * Raw entry array: length 1 = wildcard, length N = per-meta. Values are stored as {@code packed + 1} so 0 marks unregistered slots; decode with
     * {@code PackedColorLight.red/green/blue(value - 1)}.
     */
    public static void forEach(BiConsumer<Block, int[]> consumer) {
        for (int id = HAS_ENTRY.nextSetBit(0); id >= 0; id = HAS_ENTRY.nextSetBit(id + 1)) {
            if (id >= REGISTRY_BY_ID.length) break;
            final int[] entry = REGISTRY_BY_ID[id];
            if (entry == null) continue;
            final Block block = Block.getBlockById(id);
            if (block != null) consumer.accept(block, entry);
        }
    }

    /**
     * Build flat emission caches. Call at PostInit after all registrations.
     */
    @SuppressWarnings("unchecked")
    public static void buildCache() {
        int maxId = 0;
        for (final Block block : (Iterable<Block>) GameData.getBlockRegistry()) {
            final int bid = Block.getIdFromBlock(block);
            if (bid > maxId) maxId = bid;
        }

        EMISSION_CACHE = new int[maxId + 1];
        EMISSION_CACHE_PER_META = new int[maxId + 1][];
        Arrays.fill(EMISSION_CACHE, UNCACHEABLE);

        int cachedUniform = 0, cachedPerMeta = 0;

        for (final Block block : (Iterable<Block>) GameData.getBlockRegistry()) {
            final int bid = Block.getIdFromBlock(block);
            if (bid < 0) continue;

            // PositionalColoredLightSource -- uncacheable (needs world + pos)
            if (block instanceof PositionalColoredLightSource) {
                EMISSION_CACHE[bid] = UNCACHEABLE;
                continue;
            }

            // TileLightRegistry -- uncacheable (sourced from TileLightStore at position)
            if (TileLightRegistry.contains(bid)) {
                EMISSION_CACHE[bid] = UNCACHEABLE;
                continue;
            }

            // Static ColoredLightSource -- probe meta 0-15, cache if possible
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
                    EMISSION_CACHE[bid] = meta0;
                    cachedUniform++;
                } else {
                    final int[] perMeta = new int[16];
                    perMeta[0] = meta0;
                    for (int m = 1; m < 16; m++) perMeta[m] = src.getColoredLightEmission(m);
                    EMISSION_CACHE[bid] = UNCACHEABLE;
                    EMISSION_CACHE_PER_META[bid] = perMeta;
                    cachedPerMeta++;
                }
                continue;
            }

            // Explicit registry entry
            final int[] entry = (bid < REGISTRY_BY_ID.length) ? REGISTRY_BY_ID[bid] : null;
            if (entry != null) {
                if (entry.length == 1) {
                    // Wildcard -- uniform
                    EMISSION_CACHE[bid] = entry[0] - 1;
                    cachedUniform++;
                } else {
                    // Per-meta registry -- probe for uniformity first
                    final int firstVal = (entry.length > 0 && entry[0] != 0) ? entry[0] - 1 : 0;
                    boolean allSame = true;
                    boolean hasPerMeta = firstVal != 0;
                    for (int m = 1; m < 16; m++) {
                        final int result = (m < entry.length && entry[m] != 0) ? entry[m] - 1 : 0;
                        if (result != firstVal) {
                            allSame = false;
                            break;
                        }
                        if (result != 0) hasPerMeta = true;
                    }
                    if (allSame) {
                        EMISSION_CACHE[bid] = firstVal;
                        cachedUniform++;
                    } else {
                        final int[] perMeta = new int[16];
                        perMeta[0] = firstVal;
                        for (int m = 1; m < 16; m++) {
                            perMeta[m] = (m < entry.length && entry[m] != 0) ? entry[m] - 1 : 0;
                        }
                        EMISSION_CACHE[bid] = UNCACHEABLE;
                        EMISSION_CACHE_PER_META[bid] = perMeta;
                        cachedPerMeta++;
                    }
                }
                continue;
            }

            // EasyColoredLights auto-detect / vanilla fallback
            final int rawLight = block.getLightValue();
            if (rawLight > 15) {
                final int ecl = decodeECL(rawLight);
                if (ecl != 0) {
                    EMISSION_CACHE[bid] = ecl;
                    cachedUniform++;
                    continue;
                }
            }
            final int vanillaLight = rawLight & 0xF;
            EMISSION_CACHE[bid] = vanillaLight > 0 ? WHITE_BY_LEVEL[vanillaLight] : 0;
            cachedUniform++;
        }

        Supernova.LOG.info("LightColorRegistry: cached emission for {} uniform + {} per-meta blocks", cachedUniform, cachedPerMeta);
    }

    /** ECL spacer bits (4, 9, 14, 19) must be zero in valid {@code 0RRRR 0GGGG 0BBBB 0LLLL} format. */
    private static final int ECL_SPACER_MASK = (1 << 4) | (1 << 9) | (1 << 14) | (1 << 19);

    /**
     * Decode an EasyColoredLights-format packed value ({@code 0RRRR 0GGGG 0BBBB 0LLLL}) into Supernova's
     * {@link PackedColorLight#pack} format. Returns 0 if the value is not valid ECL or has no RGB channels.
     */
    private static int decodeECL(int eclValue) {
        if ((eclValue & ECL_SPACER_MASK) != 0) return 0; // not valid ECL format
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

    private static void markEntry(Block block) {
        final int id = Block.getIdFromBlock(block);
        if (id >= 0) HAS_ENTRY.set(id);
    }

    private static void ensureCapacity(int id) {
        if (id >= 0 && id >= REGISTRY_BY_ID.length) {
            REGISTRY_BY_ID = Arrays.copyOf(REGISTRY_BY_ID, Math.max(id + 1, REGISTRY_BY_ID.length * 2));
        }
    }
}
