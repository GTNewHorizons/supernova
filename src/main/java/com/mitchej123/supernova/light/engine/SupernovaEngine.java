package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.api.ExtendedWorld;
import com.mitchej123.supernova.compat.cubicchunks.CubicChunksHelper;
import com.mitchej123.supernova.compat.endlessids.EndlessIDsCompat;
import com.mitchej123.supernova.light.LightStats;
import com.mitchej123.supernova.light.RenderUpdateQueue;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.util.WorldUtil;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class SupernovaEngine {

    protected static final AxisDirection[] AXIS_DIRECTIONS = AxisDirection.values();
    protected static final AxisDirection[] ONLY_HORIZONTAL_DIRECTIONS = new AxisDirection[] { AxisDirection.POSITIVE_X, AxisDirection.NEGATIVE_X,
            AxisDirection.POSITIVE_Z, AxisDirection.NEGATIVE_Z };

    protected enum AxisDirection {
        POSITIVE_X(1, 0, 0),
        NEGATIVE_X(-1, 0, 0),
        POSITIVE_Z(0, 0, 1),
        NEGATIVE_Z(0, 0, -1),
        POSITIVE_Y(0, 1, 0),
        NEGATIVE_Y(0, -1, 0);

        static {
            POSITIVE_X.opposite = NEGATIVE_X;
            NEGATIVE_X.opposite = POSITIVE_X;
            POSITIVE_Z.opposite = NEGATIVE_Z;
            NEGATIVE_Z.opposite = POSITIVE_Z;
            POSITIVE_Y.opposite = NEGATIVE_Y;
            NEGATIVE_Y.opposite = POSITIVE_Y;
        }

        protected AxisDirection opposite;

        protected final int x;
        protected final int y;
        protected final int z;
        protected final int oppositeOrdinal;
        protected final long everythingButTheOppositeDirection;
        protected final long everythingButThisDirection;

        AxisDirection(final int x, final int y, final int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.oppositeOrdinal = this.ordinal() ^ 1;
            final int allBits = (1 << 6) - 1;
            this.everythingButTheOppositeDirection = allBits ^ (1L << (this.ordinal() ^ 1));
            this.everythingButThisDirection = allBits ^ (1L << this.ordinal());
        }

        protected AxisDirection getOpposite() {
            return this.opposite;
        }
    }

    // index = x + (z * 5) + (y * 25), where x,z are [-2,2] offset from center chunk, y is light section
    protected final ExtendedBlockStorage[] sectionCache;
    protected final SWMRNibbleArray[] nibbleCache;
    protected final boolean[] notifyUpdateCache;
    protected final Chunk[] chunkCache = new Chunk[5 * 5];
    protected final boolean[][] emptinessMapCache = new boolean[5 * 5][];
    private int[] dirtyIndicesBuffer = new int[64];

    protected byte[][] blockB1Cache;          // [cacheSize] -> byte[4096] (block ID bits 0-7)
    protected NibbleArray[] blockB2LowCache;  // [cacheSize] -> NibbleArray (block ID bits 8-11)
    protected int[] blockMaskCache;           // [cacheSize] -> eid$getBlockMask() (0=8bit, 1=12bit, ≥2=fallback)

    // Pool for int[4096] arrays -- avoids allocation churn during BFS
    protected static final ThreadLocal<ArrayDeque<int[]>> PACKED_ARRAY_POOL = ThreadLocal.withInitial(ArrayDeque::new);

    protected static int[] acquirePackedArray() {
        final int[] pooled = PACKED_ARRAY_POOL.get().pollFirst();
        return pooled != null ? pooled : new int[4096];
    }

    protected static void releasePackedArray(final int[] arr) {
        if (arr != null) {
            PACKED_ARRAY_POOL.get().addFirst(arr);
        }
    }

    protected int encodeOffsetX;
    protected int encodeOffsetY;
    protected int encodeOffsetZ;
    protected int coordinateOffset;
    protected int chunkOffsetX;
    protected int chunkOffsetY;
    protected int chunkOffsetZ;
    protected int chunkIndexOffset;
    protected int chunkSectionIndexOffset;

    protected final boolean skylightPropagator;
    protected final boolean isClientSide;
    protected final World world;
    protected LightStats stats;

    // Diagnostic counters -- accumulated across propagateBlockChanges calls within one blocksChangedInChunk invocation.
    public int lastBfsIncreaseTotal;
    public int lastBfsDecreaseTotal;
    public int lastPositionsProcessed;
    public boolean suppressRenderNotify;
    public RenderUpdateQueue pendingRenderTarget;
    protected final int minLightSection;
    protected final int maxLightSection;
    protected final int minSection;
    protected final int maxSection;

    // Queue entry bit layout:
    // [0..5] X (6 bits), [6..11] Z (6 bits), [12..27] Y (16 bits), [28..31] light level (4 bits),
    // [32..37] direction bitset (6 bits), [61] FLAG_WRITE_LEVEL, [62] FLAG_RECHECK_LEVEL, [63] FLAG_HAS_SIDED_TRANSPARENT
    protected static final int COORD_X_BITS = 6;
    protected static final int COORD_Z_BITS = 6;
    protected static final int COORD_Y_BITS = 16;
    protected static final int COORD_Y_MASK = (1 << COORD_Y_BITS) - 1;
    protected static final int LIGHT_LEVEL_SHIFT = COORD_X_BITS + COORD_Z_BITS + COORD_Y_BITS; // 28
    protected static final int DIRECTION_SHIFT = LIGHT_LEVEL_SHIFT + 4; // 32
    protected static final long COORD_MASK = (1L << LIGHT_LEVEL_SHIFT) - 1;

    protected static long encodeCoords(final int x, final int z, final int y, final int encodeOffset) {
        return (x + ((long) z << COORD_X_BITS) + ((long) y << (COORD_X_BITS + COORD_Z_BITS)) + encodeOffset) & COORD_MASK;
    }

    protected static long sidedFlag(final Block block) {
        return FaceOcclusion.hasSidedTransparency(block) ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0L;
    }

    public void setStats(final LightStats stats) {
        this.stats = stats;
    }

    protected SupernovaEngine(final boolean skylightPropagator, final World world) {
        this.skylightPropagator = skylightPropagator;
        this.isClientSide = world.isRemote;
        this.world = world;
        this.minLightSection = WorldUtil.getMinLightSection();
        this.maxLightSection = WorldUtil.getMaxLightSection();
        this.minSection = WorldUtil.getMinSection();
        this.maxSection = WorldUtil.getMaxSection();

        final int totalLightSections = (this.maxLightSection - this.minLightSection + 1) + 2; // buffer
        final int cacheSize = 5 * 5 * totalLightSections;
        this.sectionCache = new ExtendedBlockStorage[cacheSize];
        this.nibbleCache = new SWMRNibbleArray[cacheSize];
        this.notifyUpdateCache = new boolean[cacheSize];
        this.blockB1Cache = new byte[cacheSize][];
        this.blockB2LowCache = new NibbleArray[cacheSize];
        this.blockMaskCache = new int[cacheSize];
    }

    protected final void setupEncodeOffset(final int centerX, final int centerY, final int centerZ) {
        this.encodeOffsetX = 31 - centerX;
        this.encodeOffsetY = (-(this.minLightSection - 1) << 4);
        this.encodeOffsetZ = 31 - centerZ;
        this.coordinateOffset = this.encodeOffsetX + (this.encodeOffsetZ << 6) + (this.encodeOffsetY << 12);
        this.chunkOffsetX = 2 - (centerX >> 4);
        this.chunkOffsetY = -(this.minLightSection - 1);
        this.chunkOffsetZ = 2 - (centerZ >> 4);
        this.chunkIndexOffset = this.chunkOffsetX + (5 * this.chunkOffsetZ);
        this.chunkSectionIndexOffset = this.chunkIndexOffset + ((5 * 5) * this.chunkOffsetY);
    }

    protected final void setupCaches(final int centerX, final int centerY, final int centerZ, final boolean relaxed, final boolean tryToLoadChunksFor2Radius) {
        final int centerChunkX = centerX >> 4;
        final int centerChunkZ = centerZ >> 4;

        this.setupEncodeOffset(centerChunkX * 16 + 7, centerY, centerChunkZ * 16 + 7);

        final int radius = tryToLoadChunksFor2Radius ? 2 : 1;

        for (int dz = -radius; dz <= radius; ++dz) {
            for (int dx = -radius; dx <= radius; ++dx) {
                final int cx = centerChunkX + dx;
                final int cz = centerChunkZ + dz;
                final boolean isTwoRadius = Math.max(Math.abs(dx), Math.abs(dz)) == 2;
                final Chunk chunk = ((ExtendedWorld) this.world).supernova$getAnyChunkImmediately(cx, cz);

                if (chunk == null) {
                    if (relaxed | isTwoRadius) {
                        continue;
                    }
                    throw new IllegalArgumentException("Trying to propagate light update before 1 radius neighbours ready");
                }

                if (!this.canUseChunk(chunk)) {
                    continue;
                }

                this.setChunkInCache(cx, cz, chunk);
                this.setEmptinessMapCache(cx, cz, this.getEmptinessMap(chunk));
                if (!isTwoRadius) {
                    this.setBlocksForChunkInCache(cx, cz, CubicChunksHelper.getBlockStorageArrays(chunk));
                    this.setNibblesForChunkInCache(cx, cz, this.getNibblesOnChunk(chunk));
                    this.loadExtraNibblesToCache(cx, cz, chunk);
                }
            }
        }
    }

    protected final Chunk getChunkInCache(final int chunkX, final int chunkZ) {
        return this.chunkCache[chunkX + 5 * chunkZ + this.chunkIndexOffset];
    }

    protected final void setChunkInCache(final int chunkX, final int chunkZ, final Chunk chunk) {
        this.chunkCache[chunkX + 5 * chunkZ + this.chunkIndexOffset] = chunk;
    }

    protected final ExtendedBlockStorage getChunkSection(final int chunkX, final int chunkY, final int chunkZ) {
        return this.sectionCache[chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
    }

    protected final void setChunkSectionInCache(final int chunkX, final int chunkY, final int chunkZ, final ExtendedBlockStorage section) {
        this.sectionCache[chunkX + 5 * chunkZ + 5 * 5 * chunkY + this.chunkSectionIndexOffset] = section;
    }

    protected final void setBlocksForChunkInCache(final int chunkX, final int chunkZ, final ExtendedBlockStorage[] sections) {
        for (int cy = this.minLightSection; cy <= this.maxLightSection; ++cy) {
            final ExtendedBlockStorage section = sections == null
                    ? null
                    : (cy >= this.minSection && cy <= this.maxSection ? sections[cy - this.minSection] : null);
            final int idx = chunkX + 5 * chunkZ + 5 * 5 * cy + this.chunkSectionIndexOffset;
            this.sectionCache[idx] = section;
            EndlessIDsCompat.populateCache(section, idx, this.blockB1Cache, this.blockB2LowCache, this.blockMaskCache);
        }
    }

    protected final SWMRNibbleArray getNibbleFromCache(final int chunkX, final int chunkY, final int chunkZ) {
        return this.nibbleCache[chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
    }

    protected final void setNibbleInCache(final int chunkX, final int chunkY, final int chunkZ, final SWMRNibbleArray nibble) {
        this.nibbleCache[chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset] = nibble;
    }

    protected final void setNibblesForChunkInCache(final int chunkX, final int chunkZ, final SWMRNibbleArray[] nibbles) {
        for (int cy = this.minLightSection; cy <= this.maxLightSection; ++cy) {
            this.setNibbleInCache(chunkX, cy, chunkZ, nibbles == null ? null : nibbles[cy - this.minLightSection]);
        }
    }

    protected final boolean[] getEmptinessMap(final int chunkX, final int chunkZ) {
        return this.emptinessMapCache[chunkX + 5 * chunkZ + this.chunkIndexOffset];
    }

    protected final void setEmptinessMapCache(final int chunkX, final int chunkZ, final boolean[] emptinessMap) {
        this.emptinessMapCache[chunkX + 5 * chunkZ + this.chunkIndexOffset] = emptinessMap;
    }

    protected final void updateVisible() {
        this.expandDirtyNotifications();
        for (int index = 0, max = this.nibbleCache.length; index < max; ++index) {
            final SWMRNibbleArray nibble = this.nibbleCache[index];
            if (!this.notifyUpdateCache[index] && (nibble == null || !nibble.isDirty())) {
                continue;
            }
            if (nibble != null) {
                nibble.updateVisible();
            }
            this.onNibbleVisible(index, nibble);
            if (this.notifyUpdateCache[index] && this.isClientSide) {
                final int cxLocal = index % 5;
                final int czLocal = (index / 5) % 5;
                final int cyLocal = index / 25;
                if (!this.suppressRenderNotify) {
                    final int sectionX = (cxLocal - this.chunkOffsetX) << 4;
                    final int sectionY = (cyLocal - this.chunkOffsetY) << 4;
                    final int sectionZ = (czLocal - this.chunkOffsetZ) << 4;
                    this.world.markBlockRangeForRenderUpdate(sectionX, sectionY, sectionZ, sectionX + 15, sectionY + 15, sectionZ + 15);
                    LightStats.engineRenderMarks++;
                } else if (this.pendingRenderTarget != null) {
                    final int cx = cxLocal - this.chunkOffsetX;
                    final int cz = czLocal - this.chunkOffsetZ;
                    final int cy = cyLocal - this.chunkOffsetY;
                    this.pendingRenderTarget.offer(((long) cx << 32) | ((long) (cz & 0xFFFF) << 16) | (cy & 0xFFFFL));
                }
            }
        }
        this.updateVisibleExtra();
    }

    protected final void destroyCaches() {
        Arrays.fill(this.sectionCache, null);
        Arrays.fill(this.nibbleCache, null);
        Arrays.fill(this.chunkCache, null);
        Arrays.fill(this.emptinessMapCache, null);
        Arrays.fill(this.blockB1Cache, null);
        Arrays.fill(this.blockB2LowCache, null);
        Arrays.fill(this.blockMaskCache, 0);
        if (this.isClientSide) {
            Arrays.fill(this.notifyUpdateCache, false);
        }
        this.queueOverflowWarned = false;
        this.queueOverflowed = false;
        this.destroyExtraCaches();
    }

    protected final Block getBlock(final int worldX, final int worldY, final int worldZ) {
        final int sectionIndex = (worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset;
        return this.getBlockFast(sectionIndex, worldX & 15, worldY & 15, worldZ & 15);
    }

    /**
     * Inline block ID decode bypassing EndlessIDs dispatch. Falls back to getBlockByExtId when EID is not present or for rare 16/24-bit IDs.
     */
    protected final Block getBlockFast(final int sectionIndex, final int x, final int y, final int z) {
        final byte[] b1 = this.blockB1Cache[sectionIndex];
        if (b1 == null) {
            // No cached B1 -- either section is null or EID not present, fall back
            final ExtendedBlockStorage section = this.sectionCache[sectionIndex];
            return section != null ? section.getBlockByExtId(x, y, z) : Blocks.air;
        }
        final int index = y << 8 | z << 4 | x;
        int id = b1[index] & 0xFF;
        final int mask = this.blockMaskCache[sectionIndex];
        if (mask >= 1) {
            final NibbleArray b2Low = this.blockB2LowCache[sectionIndex];
            if (b2Low != null) {
                id |= b2Low.get(x, y, z) << 8;
            }
            if (mask >= 2) {
                // Rare: 16/24-bit IDs -- fall back to full EID path
                return this.sectionCache[sectionIndex].getBlockByExtId(x, y, z);
            }
        }
        return Block.getBlockById(id);
    }

    protected final int getBlockMeta(final int worldX, final int worldY, final int worldZ) {
        final ExtendedBlockStorage section = this.sectionCache[(worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset];
        return section != null ? section.getExtBlockMetadata(worldX & 15, worldY & 15, worldZ & 15) : 0;
    }

    protected int getLightLevel(final int worldX, final int worldY, final int worldZ) {
        final SWMRNibbleArray nibble = this.nibbleCache[(worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset];
        return nibble == null ? 0 : nibble.getUpdating((worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8));
    }

    protected int getLightLevel(final int sectionIndex, final int localIndex) {
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];
        return nibble == null ? 0 : nibble.getUpdating(localIndex);
    }

    protected long encodeQueueLevel(final int level) {
        return (level & 0xFL) << LIGHT_LEVEL_SHIFT;
    }

    protected int getDirectionShift() {
        return DIRECTION_SHIFT;
    }

    protected boolean isBelowPropagationThreshold(final int level) {
        return level <= 1;
    }

    protected void setLightLevel(final int worldX, final int worldY, final int worldZ, final int level) {
        final int sectionIndex = (worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset;
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];
        if (nibble != null) {
            nibble.set((worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8), level);
            this.postLightUpdate(sectionIndex);
        }
    }

    protected final void postLightUpdate(final int sectionIndex) {
        if (this.isClientSide & (!this.suppressRenderNotify | this.pendingRenderTarget != null)) {
            this.notifyUpdateCache[sectionIndex] = true;
        }
    }

    /**
     * Expand dirty notify flags to neighbouring sections. A light change near a section boundary affects rendering in the adjacent section, so we mark a 3x3x3
     * neighbourhood around each dirty section. Called once in {@link #updateVisible()} before processing.
     */
    private void expandDirtyNotifications() {
        final boolean[] cache = this.notifyUpdateCache;
        final int len = cache.length;

        // Collect dirty indices first to avoid cascading expansion
        int dirtyCount = 0;
        for (boolean dirty : cache) {
            if (dirty) dirtyCount++;
        }
        if (dirtyCount == 0) return;

        if (dirtyCount > this.dirtyIndicesBuffer.length) this.dirtyIndicesBuffer = new int[dirtyCount];
        final int[] dirtyIndices = this.dirtyIndicesBuffer;
        int idx = 0;
        for (int i = 0; i < len; ++i) {
            if (cache[i]) dirtyIndices[idx++] = i;
        }

        // Max sections per Y layer = 5*5 = 25, totalY = len/25
        final int totalY = len / 25;
        for (int d = 0; d < dirtyCount; ++d) {
            final int index = dirtyIndices[d];
            final int cx = index % 5;
            final int cz = (index / 5) % 5;
            final int cy = index / 25;
            for (int dy = -1; dy <= 1; ++dy) {
                final int ny = cy + dy;
                if (ny < 0 || ny >= totalY) continue;
                for (int dz = -1; dz <= 1; ++dz) {
                    final int nz = cz + dz;
                    if (nz < 0 || nz >= 5) continue;
                    for (int dx = -1; dx <= 1; ++dx) {
                        final int nx = cx + dx;
                        if (nx < 0 || nx >= 5) continue;
                        cache[nx + 5 * nz + 25 * ny] = true;
                    }
                }
            }
        }
    }

    public static SWMRNibbleArray[] getFilledEmptyLight() {
        final int totalLightSections = WorldUtil.getTotalLightSections();
        final SWMRNibbleArray[] ret = new SWMRNibbleArray[totalLightSections];
        for (int i = 0, len = ret.length; i < len; ++i) {
            ret[i] = new SWMRNibbleArray(null, true);
        }
        return ret;
    }

    public static Boolean[] getEmptySectionsForChunk(final Chunk chunk) {
        final ExtendedBlockStorage[] sections = CubicChunksHelper.getBlockStorageArrays(chunk);
        final Boolean[] ret = new Boolean[sections.length];
        for (int i = 0; i < sections.length; ++i) {
            ret[i] = (sections[i] == null || sections[i].isEmpty()) ? Boolean.TRUE : Boolean.FALSE;
        }
        return ret;
    }

    protected abstract boolean[] getEmptinessMap(final Chunk chunk);

    protected abstract void setEmptinessMap(final Chunk chunk, final boolean[] to);

    protected abstract SWMRNibbleArray[] getNibblesOnChunk(final Chunk chunk);

    protected abstract void setNibbles(final Chunk chunk, final SWMRNibbleArray[] to);

    protected abstract boolean canUseChunk(final Chunk chunk);

    protected abstract void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles);

    protected abstract void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ);

    protected abstract void propagateBlockChanges(final Chunk atChunk, final int blockX, final int blockY, final int blockZ);

    protected abstract void checkBlock(final int worldX, final int worldY, final int worldZ);

    protected abstract int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect);

    protected abstract void lightChunk(final Chunk chunk, final boolean needsEdgeChecks);

    public final void blockChanged(final int blockX, final int blockY, final int blockZ) {
        if (blockY < WorldUtil.getMinBlockY() || blockY > WorldUtil.getMaxBlockY()) {
            return;
        }
        final int chunkX = blockX >> 4;
        final int chunkZ = blockZ >> 4;
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final Chunk chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            this.propagateBlockChanges(chunk, blockX, blockY, blockZ);
            this.updateVisible();
        } finally {
            this.destroyCaches();
        }
    }

    public final void blocksChangedInChunk(final int chunkX, final int chunkZ, final IntOpenHashSet changedPositions, final Boolean[] changedSections) {
        this.lastBfsIncreaseTotal = 0;
        this.lastBfsDecreaseTotal = 0;
        this.lastPositionsProcessed = 0;
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final Chunk chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            // 1. Section changes first (creates/removes nibbles)
            if (changedSections != null) {
                final boolean[] ret = this.handleEmptySectionChanges(chunk, changedSections, false);
                if (ret != null) {
                    this.setEmptinessMap(chunk, ret);
                }
            }
            // 2. Block changes (uses now-updated nibbles)
            if (changedPositions != null && !changedPositions.isEmpty()) {
                this.processBlockPositionChanges(chunk, chunkX, chunkZ, changedPositions);
            }
            this.updateVisible();
        } finally {
            this.destroyCaches();
        }
    }

    protected void processBlockPositionChanges(final Chunk chunk, final int chunkX, final int chunkZ, final IntOpenHashSet changedPositions) {
        final int minBlockY = WorldUtil.getMinBlockY();
        final int maxBlockY = WorldUtil.getMaxBlockY();
        final it.unimi.dsi.fastutil.ints.IntIterator it = changedPositions.iterator();
        while (it.hasNext()) {
            final int packed = it.nextInt();
            final int worldY = packed >> 8;
            if (worldY < minBlockY || worldY > maxBlockY) {
                continue;
            }
            final int worldX = (chunkX << 4) | (packed & 15);
            final int worldZ = (chunkZ << 4) | ((packed >> 4) & 15);
            this.lastPositionsProcessed++;
            this.propagateBlockChanges(chunk, worldX, worldY, worldZ);
        }
    }

    public final void light(final Chunk chunk, final Boolean[] emptySections, final boolean checkEdges) {
        final int chunkX = chunk.xPosition;
        final int chunkZ = chunk.zPosition;
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final SWMRNibbleArray[] nibbles = getFilledEmptyLight();
            this.setChunkInCache(chunkX, chunkZ, chunk);
            this.setBlocksForChunkInCache(chunkX, chunkZ, CubicChunksHelper.getBlockStorageArrays(chunk));
            this.setNibblesForChunkInCache(chunkX, chunkZ, nibbles);
            this.setupExtraLightNibbles(chunkX, chunkZ);
            this.setEmptinessMapCache(chunkX, chunkZ, this.getEmptinessMap(chunk));

            final boolean[] ret = this.handleEmptySectionChanges(chunk, emptySections, true);
            if (ret != null) {
                this.setEmptinessMap(chunk, ret);
            }
            this.lightChunk(chunk, checkEdges);
            this.updateVisible();
            this.setNibbles(chunk, this.getNibblesFromCache(chunk));
            this.saveExtraLightNibbles(chunk);
        } finally {
            this.destroyCaches();
        }
    }

    private SWMRNibbleArray[] getNibblesFromCache(final Chunk chunk) {
        final int chunkX = chunk.xPosition;
        final int chunkZ = chunk.zPosition;
        final SWMRNibbleArray[] nibbles = new SWMRNibbleArray[this.maxLightSection - this.minLightSection + 1];
        for (int cy = this.minLightSection; cy <= this.maxLightSection; ++cy) {
            nibbles[cy - this.minLightSection] = this.getNibbleFromCache(chunkX, cy, chunkZ);
        }
        return nibbles;
    }

    public final void checkChunkEdges(final int chunkX, final int chunkZ, final IntOpenHashSet sections) {
        this.setupCaches(chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, false);
        try {
            final Chunk chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            this.prepareBatchedEdgeChecks(chunkX, chunkZ);
            final IntIterator it = sections.iterator();
            while (it.hasNext()) {
                final int sectionY = it.nextInt();
                if (!this.isLightSectionInRange(sectionY)) {
                    continue;
                }
                this.checkChunkEdge(chunkX, sectionY, chunkZ);
                this.performLightDecrease();  // drain BFS per-section so later sections see earlier corrections
            }
            this.updateVisible();
        } finally {
            this.destroyCaches();
        }
    }

    protected void prepareBatchedEdgeChecks(final int chunkX, final int chunkZ) {}

    protected final boolean isLightSectionInRange(final int sectionY) {
        return sectionY >= this.minLightSection && sectionY <= this.maxLightSection;
    }

    /**
     * Process per-section emptiness changes.
     * {@code emptinessChanges} is a tri-state {@code Boolean[]} where {@code null} means "no change" for that section
     * index -- this sparse representation avoids recomputing unchanged sections.
     */
    protected final boolean[] handleEmptySectionChanges(final Chunk chunk, final Boolean[] emptinessChanges, final boolean unlit) {
        final int chunkX = chunk.xPosition;
        final int chunkZ = chunk.zPosition;

        boolean[] chunkEmptinessMap = this.getEmptinessMap(chunkX, chunkZ);
        boolean[] ret = null;
        final boolean needsInit = unlit || chunkEmptinessMap == null;
        if (needsInit) {
            this.setEmptinessMapCache(chunkX, chunkZ, ret = chunkEmptinessMap = new boolean[WorldUtil.getTotalSections()]);
        }

        // update emptiness map
        for (int sectionIndex = (emptinessChanges.length - 1); sectionIndex >= 0; --sectionIndex) {
            Boolean valueBoxed = emptinessChanges[sectionIndex];
            if (valueBoxed == null) {
                if (!needsInit) {
                    continue;
                }
                final ExtendedBlockStorage section = this.getChunkSection(chunkX, sectionIndex + this.minSection, chunkZ);
                emptinessChanges[sectionIndex] = valueBoxed = section == null || section.isEmpty() ? Boolean.TRUE : Boolean.FALSE;
            }
            chunkEmptinessMap[sectionIndex] = valueBoxed;
        }

        // init neighbour nibbles for non-empty sections
        for (int sectionIndex = (emptinessChanges.length - 1); sectionIndex >= 0; --sectionIndex) {
            final Boolean valueBoxed = emptinessChanges[sectionIndex];
            final int sectionY = sectionIndex + this.minSection;
            if (valueBoxed == null || valueBoxed) {
                continue;
            }
            for (int dz = -1; dz <= 1; ++dz) {
                for (int dx = -1; dx <= 1; ++dx) {
                    final boolean extrude = (dx | dz) != 0 || !unlit;
                    for (int dy = 1; dy >= -1; --dy) {
                        this.initNibble(dx + chunkX, dy + sectionY, dz + chunkZ, extrude, false);
                    }
                }
            }
        }

        // check for de-init and lazy-init
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                boolean neighboursLoaded = true;
                neighbour_loaded_search:
                for (int dz2 = -1; dz2 <= 1; ++dz2) {
                    for (int dx2 = -1; dx2 <= 1; ++dx2) {
                        if (this.getEmptinessMap(dx + dx2 + chunkX, dz + dz2 + chunkZ) == null) {
                            neighboursLoaded = false;
                            break neighbour_loaded_search;
                        }
                    }
                }

                for (int sectionY = this.maxLightSection; sectionY >= this.minLightSection; --sectionY) {
                    boolean allEmpty = true;
                    neighbour_search:
                    for (int dy2 = -1; dy2 <= 1; ++dy2) {
                        for (int dz2 = -1; dz2 <= 1; ++dz2) {
                            for (int dx2 = -1; dx2 <= 1; ++dx2) {
                                final int y = sectionY + dy2;
                                if (y < this.minSection || y > this.maxSection) {
                                    continue;
                                }
                                final boolean[] emptinessMap = this.getEmptinessMap(dx + dx2 + chunkX, dz + dz2 + chunkZ);
                                if (emptinessMap != null) {
                                    if (!emptinessMap[y - this.minSection]) {
                                        allEmpty = false;
                                        break neighbour_search;
                                    }
                                } else {
                                    final ExtendedBlockStorage section = this.getChunkSection(dx + dx2 + chunkX, y, dz + dz2 + chunkZ);
                                    if (section != null && !section.isEmpty()) {
                                        allEmpty = false;
                                        break neighbour_search;
                                    }
                                }
                            }
                        }
                    }

                    if (allEmpty & neighboursLoaded) {
                        this.setNibbleNull(dx + chunkX, sectionY, dz + chunkZ);
                    } else if (!allEmpty) {
                        final boolean extrude = (dx | dz) != 0 || !unlit;
                        this.initNibble(dx + chunkX, sectionY, dz + chunkZ, extrude, false);
                    }
                }
            }
        }

        return ret;
    }

    protected void checkChunkEdge(final int chunkX, final int chunkY, final int chunkZ) {
        if (!this.isLightSectionInRange(chunkY)) {
            return;
        }
        final int currIdx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
        final SWMRNibbleArray currNibble = this.nibbleCache[currIdx];
        if (currNibble == null) {
            return;
        }

        final LightStats s = this.stats;

        for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
            final int neighbourOffX = direction.x;
            final int neighbourOffZ = direction.z;

            final int nIdx = (chunkX + neighbourOffX) + 5 * (chunkZ + neighbourOffZ) + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
            final SWMRNibbleArray neighbourNibble = this.nibbleCache[nIdx];
            if (neighbourNibble == null) {
                continue;
            }
            if (!currNibble.isInitialisedUpdating() && !neighbourNibble.isInitialisedUpdating()) {
                continue;
            }
            // Both sides identically full or both zero -- no edge correction needed
            if (this.areBothEdgeSectionsFull(currIdx, nIdx)) {
                if (s != null) s.edgeSectionPairsSkippedFull.incrementAndGet();
                continue;
            }
            if (this.areBothEdgeSectionsZero(currIdx, nIdx)) {
                if (s != null) s.edgeSectionPairsSkippedZero.incrementAndGet();
                continue;
            }

            if (s != null) s.edgeSectionPairsChecked.incrementAndGet();

            final int incX, incZ, startX, startZ;
            if (neighbourOffX != 0) {
                incX = 0;
                incZ = 1;
                startX = direction.x < 0 ? chunkX << 4 : chunkX << 4 | 15;
                startZ = chunkZ << 4;
            } else {
                incX = 1;
                incZ = 0;
                startZ = neighbourOffZ < 0 ? chunkZ << 4 : chunkZ << 4 | 15;
                startX = chunkX << 4;
            }

            int centerDelayedChecks = 0;
            int neighbourDelayedChecks = 0;
            int blocksTrivial = 0;
            int blocksConsistency = 0;
            int blocksRecalc = 0;
            int blocksMismatch = 0;
            for (int currY = chunkY << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                    final int neighbourX = currX + neighbourOffX;
                    final int neighbourZ = currZ + neighbourOffZ;

                    final int currentIndex = (currX & 15) | ((currZ & 15) << 4) | ((currY & 15) << 8);
                    final int currentLevel = this.getLightLevel(currIdx, currentIndex);

                    final int neighbourIndex = (neighbourX & 15) | ((neighbourZ & 15) << 4) | ((currY & 15) << 8);
                    final int neighbourLevel = this.getLightLevel(nIdx, neighbourIndex);

                    // Both sides dark -- no emission or propagation possible
                    if (currentLevel == 0 && neighbourLevel == 0) {
                        blocksTrivial++;
                        continue;
                    }
                    // Phase 2: consistency early-out -- if levels differ by at most 1 (min absorption)
                    // and both are nonzero, neither side can be a better source across this edge
                    if (currentLevel > 0 && neighbourLevel > 0 && Math.abs(currentLevel - neighbourLevel) <= 1) {
                        blocksConsistency++;
                        continue;
                    }

                    blocksRecalc += 2;
                    if (this.calculateLightValue(currX, currY, currZ, currentLevel) != currentLevel) {
                        this.chunkCheckDelayedUpdatesCenter[centerDelayedChecks++] = currentIndex;
                        blocksMismatch++;
                    }
                    if (this.calculateLightValue(neighbourX, currY, neighbourZ, neighbourLevel) != neighbourLevel) {
                        this.chunkCheckDelayedUpdatesNeighbour[neighbourDelayedChecks++] = neighbourIndex;
                        blocksMismatch++;
                    }
                }
            }

            if (s != null) {
                s.edgeBlocksTotal.addAndGet(256);
                s.edgeBlocksSkippedTrivial.addAndGet(blocksTrivial);
                s.edgeBlocksSkippedConsistency.addAndGet(blocksConsistency);
                s.edgeBlocksRecalculated.addAndGet(blocksRecalc);
                s.edgeBlocksMismatched.addAndGet(blocksMismatch);
            }

            final int currentChunkOffX = chunkX << 4;
            final int currentChunkOffZ = chunkZ << 4;
            final int neighbourChunkOffX = (chunkX + direction.x) << 4;
            final int neighbourChunkOffZ = (chunkZ + direction.z) << 4;
            final int chunkOffY = chunkY << 4;
            for (int i = 0, len = Math.max(centerDelayedChecks, neighbourDelayedChecks); i < len; ++i) {
                if (i < centerDelayedChecks) {
                    final int value = this.chunkCheckDelayedUpdatesCenter[i];
                    this.checkBlock(currentChunkOffX | (value & 15), chunkOffY | (value >>> 8), currentChunkOffZ | ((value >>> 4) & 0xF));
                }
                if (i < neighbourDelayedChecks) {
                    final int value = this.chunkCheckDelayedUpdatesNeighbour[i];
                    this.checkBlock(neighbourChunkOffX | (value & 15), chunkOffY | (value >>> 8), neighbourChunkOffZ | ((value >>> 4) & 0xF));
                }
            }
        }
    }

    protected boolean areBothEdgeSectionsFull(final int currIdx, final int nIdx) {
        return this.nibbleCache[currIdx].isFullUpdating() && this.nibbleCache[nIdx].isFullUpdating();
    }

    protected boolean areBothEdgeSectionsZero(final int currIdx, final int nIdx) {
        return this.nibbleCache[currIdx].isZeroUpdating() && this.nibbleCache[nIdx].isZeroUpdating();
    }

    protected void checkChunkEdges(final Chunk chunk, final int fromSection, final int toSection) {
        for (int currSectionY = toSection; currSectionY >= fromSection; --currSectionY) {
            this.checkChunkEdge(chunk.xPosition, currSectionY, chunk.zPosition);
        }
        this.performLightDecrease();
    }

    protected void propagateNeighbourLevels(final Chunk chunk, final int fromSection, final int toSection) {
        final int chunkX = chunk.xPosition;
        final int chunkZ = chunk.zPosition;
        final int dirShift = this.getDirectionShift();

        for (int currSectionY = toSection; currSectionY >= fromSection; --currSectionY) {
            final SWMRNibbleArray currNibble = this.getNibbleFromCache(chunkX, currSectionY, chunkZ);
            if (currNibble == null) {
                continue;
            }
            for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
                final int neighbourOffX = direction.x;
                final int neighbourOffZ = direction.z;

                final int nIdx = (chunkX + neighbourOffX) + 5 * (chunkZ + neighbourOffZ) + (5 * 5) * currSectionY + this.chunkSectionIndexOffset;
                if (this.nibbleCache[nIdx] == null || !this.nibbleCache[nIdx].isInitialisedUpdating()) {
                    continue;
                }

                final int incX, incZ, startX, startZ;
                if (neighbourOffX != 0) {
                    incX = 0;
                    incZ = 1;
                    startX = direction.x < 0 ? (chunkX << 4) - 1 : (chunkX << 4) + 16;
                    startZ = chunkZ << 4;
                } else {
                    incX = 1;
                    incZ = 0;
                    startZ = neighbourOffZ < 0 ? (chunkZ << 4) - 1 : (chunkZ << 4) + 16;
                    startX = chunkX << 4;
                }

                final long propagateDirection = 1L << direction.oppositeOrdinal;
                final int encodeOffset = this.coordinateOffset;

                for (int currY = currSectionY << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                    for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                        final int localIndex = (currX & 15) | ((currZ & 15) << 4) | ((currY & 15) << 8);
                        final int level = this.getLightLevel(nIdx, localIndex);
                        if (this.isBelowPropagationThreshold(level)) {
                            continue;
                        }
                        final int edgeIdx = (currX >> 4) + 5 * (currZ >> 4) + (5 * 5) * (currY >> 4) + this.chunkSectionIndexOffset;
                        final Block edgeBlock = this.getBlockFast(edgeIdx, currX & 15, currY & 15, currZ & 15);
                        this.appendToIncreaseQueue(encodeCoords(currX, currZ, currY, encodeOffset) | this.encodeQueueLevel(level) | (propagateDirection
                                << dirShift) | sidedFlag(edgeBlock));
                    }
                }
            }
        }
    }

    protected static final long FLAG_WRITE_LEVEL = Long.MIN_VALUE >>> 2;
    protected static final long FLAG_RECHECK_LEVEL = Long.MIN_VALUE >>> 1;
    protected static final long FLAG_HAS_SIDED_TRANSPARENT_BLOCKS = Long.MIN_VALUE; // bit 63

    protected static final int INITIAL_QUEUE_SIZE = 1 << 15; // 32768
    protected static final int MAX_QUEUE_SIZE = 1 << 20; // ~8MB per queue

    /** Whether this light level is the maximum possible value (15 for scalar, WHITE for RGB sky). */
    protected boolean isMaxLight(final int level) {return level == 15;}

    protected long[] increaseQueue = new long[INITIAL_QUEUE_SIZE];
    protected int increaseQueueInitialLength;
    protected long[] decreaseQueue = new long[INITIAL_QUEUE_SIZE];
    protected int decreaseQueueInitialLength;
    protected boolean queueOverflowWarned;
    protected boolean queueOverflowed;

    protected final int[] chunkCheckDelayedUpdatesCenter = new int[16 * 16];
    protected final int[] chunkCheckDelayedUpdatesNeighbour = new int[16 * 16];

    protected final long[] resizeIncreaseQueue() {
        return this.increaseQueue = Arrays.copyOf(this.increaseQueue, Math.min(this.increaseQueue.length * 2, MAX_QUEUE_SIZE));
    }

    protected final long[] resizeDecreaseQueue() {
        return this.decreaseQueue = Arrays.copyOf(this.decreaseQueue, Math.min(this.decreaseQueue.length * 2, MAX_QUEUE_SIZE));
    }

    protected final void appendToIncreaseQueue(final long value) {
        long[] queue = this.increaseQueue;
        final int idx = this.increaseQueueInitialLength;
        if (idx >= queue.length) {
            if (queue.length >= MAX_QUEUE_SIZE) {
                warnQueueOverflow();
                return;
            }
            queue = this.resizeIncreaseQueue();
        }
        queue[idx] = value;
        this.increaseQueueInitialLength = idx + 1;
    }

    protected final void appendToDecreaseQueue(final long value) {
        long[] queue = this.decreaseQueue;
        final int idx = this.decreaseQueueInitialLength;
        if (idx >= queue.length) {
            if (queue.length >= MAX_QUEUE_SIZE) {
                warnQueueOverflow();
                return;
            }
            queue = this.resizeDecreaseQueue();
        }
        queue[idx] = value;
        this.decreaseQueueInitialLength = idx + 1;
    }

    private void warnQueueOverflow() {
        this.queueOverflowed = true;
        if (!this.queueOverflowWarned) {
            this.queueOverflowWarned = true;
            Supernova.LOG.warn(
                    "Supernova light queue overflow near chunk ({}, {}). Some blocks may remain dark. Chunk will be re-lit.",
                    2 - this.chunkOffsetX,
                    2 - this.chunkOffsetZ);
        }
    }

    public boolean wasQueueOverflowed() {
        return this.queueOverflowed;
    }

    protected static final AxisDirection[][] OLD_CHECK_DIRECTIONS = new AxisDirection[1 << 6][];
    protected static final int ALL_DIRECTIONS_BITSET = (1 << 6) - 1;

    static {
        for (int i = 0; i < OLD_CHECK_DIRECTIONS.length; ++i) {
            final List<AxisDirection> directions = new ArrayList<>();
            for (int bitset = i, len = Integer.bitCount(i), index = 0; index < len; ++index, bitset ^= (-bitset & bitset)) {
                directions.add(AXIS_DIRECTIONS[Integer.numberOfTrailingZeros(bitset)]);
            }
            OLD_CHECK_DIRECTIONS[i] = directions.toArray(new AxisDirection[0]);
        }
    }

    protected void loadExtraNibblesToCache(final int cx, final int cz, final Chunk chunk) {}

    protected void setupExtraLightNibbles(final int chunkX, final int chunkZ) {}

    protected void saveExtraLightNibbles(final Chunk chunk) {}

    /**
     * Called for each dirty nibble after its data is published but before the render update is triggered. Override to sync data to vanilla structures before
     * Celeritas reads them.
     */
    protected void onNibbleVisible(final int cacheIndex, final SWMRNibbleArray nibble) {}

    protected void updateVisibleExtra() {}

    protected void destroyExtraCaches() {}

    protected abstract void performLightIncrease();

    protected abstract void performLightDecrease();
}
