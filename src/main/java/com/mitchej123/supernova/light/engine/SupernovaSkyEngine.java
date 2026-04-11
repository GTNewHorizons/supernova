package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.ColoredTranslucency;
import com.mitchej123.supernova.api.PackedColorLight;
import com.mitchej123.supernova.api.PositionalColoredTranslucency;
import com.mitchej123.supernova.api.TranslucencyRegistry;
import com.mitchej123.supernova.compat.cubicchunks.CubicChunksHelper;
import com.mitchej123.supernova.light.LightStats;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

public class SupernovaSkyEngine extends SupernovaRGBEngine {

    private final boolean[] nullPropagationCheckCache;

    public SupernovaSkyEngine(final World world) {
        super(true, world);
        this.nullPropagationCheckCache = new boolean[WorldUtil.getTotalLightSections()];
        this.increaseQueue = new long[1 << 18]; // 256K entries -- sky BFS covers far more blocks than block light
        this.decreaseQueue = new long[1 << 18]; // 256K entries -- avoids resize churn during initial lighting
    }

    @Override
    protected boolean isMaxLight(final int level) {return level == WHITE;}

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }
        // Init R (via base nibbleCache)
        SWMRNibbleArray nibR = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibR == null) {
            if (!initRemovedNibbles) {
                return;
            }
            this.setNibbleInCache(chunkX, chunkY, chunkZ, nibR = new SWMRNibbleArray(null, true));
        }
        this.initNibble(nibR, chunkX, chunkY, chunkZ, extrude);

        // Mirror R index into nibbleCacheR
        final int idx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
        this.nibbleCacheR[idx] = this.nibbleCache[idx];

        // Init G and B to match R state
        initSkyChannel(this.nibbleCacheG, idx, nibR, initRemovedNibbles);
        initSkyChannel(this.nibbleCacheB, idx, nibR, initRemovedNibbles);

        // Invalidate packed cache -- nibble data changed, needs repacking
        releasePackedArray(this.packedRGBCache[idx]);
        this.packedRGBCache[idx] = null;
        this.packedCacheDirty[idx] = false;
    }

    /**
     * Mirror the R channel's init state to a G or B channel nibble. Uses R's full/zero flags to set G/B to matching uniform state (full above terrain, zero
     * underground).
     */
    private static void initSkyChannel(final SWMRNibbleArray[] cache, final int idx, final SWMRNibbleArray rNibble, final boolean initRemovedNibbles) {
        SWMRNibbleArray nib = cache[idx];
        if (nib == null) {
            if (!initRemovedNibbles) return;
            cache[idx] = nib = new SWMRNibbleArray(null, true);
        }
        if (rNibble.isNullNibbleUpdating()) return;
        nib.setNonNull();
        // If R is full (all 15), set G/B full too. For extruded or zero-init sections,
        // G/B start at zero and the BFS will fill them.
        if (rNibble.isFullUpdating()) {
            nib.setFull();
        } else if (rNibble.isZeroUpdating()) {
            nib.setZero();
        }
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        final int idx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
        final SWMRNibbleArray nibR = this.nibbleCache[idx];
        if (nibR != null) nibR.setNull();
        final SWMRNibbleArray nibG = this.nibbleCacheG[idx];
        if (nibG != null) nibG.setNull();
        final SWMRNibbleArray nibB = this.nibbleCacheB[idx];
        if (nibB != null) nibB.setNull();
        releasePackedArray(this.packedRGBCache[idx]);
        this.packedRGBCache[idx] = null;
        this.packedCacheDirty[idx] = false;
    }

    private void initNibble(final SWMRNibbleArray currNibble, final int chunkX, final int chunkY, final int chunkZ, final boolean extrude) {
        if (!currNibble.isNullNibbleUpdating()) {
            return;
        }

        final boolean[] emptinessMap = this.getEmptinessMap(chunkX, chunkZ);

        int lowestY = this.minLightSection - 1;
        for (int currY = this.maxSection; currY >= this.minSection; --currY) {
            if (emptinessMap == null) {
                final ExtendedBlockStorage current = this.getChunkSection(chunkX, currY, chunkZ);
                if (current == null || current.isEmpty()) continue;
            } else if (emptinessMap[currY - this.minSection]) continue;
            lowestY = currY;
            break;
        }

        if (chunkY > lowestY) {
            final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
            nibble.setNonNull();
            nibble.setFull();
            return;
        }

        if (extrude) {
            for (int currY = chunkY + 1; currY <= this.maxLightSection; ++currY) {
                final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, currY, chunkZ);
                if (nibble != null && !nibble.isNullNibbleUpdating()) {
                    currNibble.setNonNull();
                    currNibble.extrudeLower(nibble);
                    break;
                }
            }
        } else {
            currNibble.setNonNull();
        }
    }

    @Override
    protected void saveChannelNibbles(final SupernovaChunk chunk, final SWMRNibbleArray[] g, final SWMRNibbleArray[] b) {
        chunk.setSkyNibblesG(g);
        chunk.setSkyNibblesB(b);
    }

    @Override
    protected void loadChannelNibbles(final SupernovaChunk chunk, final int cx, final int cz) {
        setChannelNibblesForChunkInCache(this.nibbleCacheR, cx, cz, chunk.getSkyNibblesR());
        setChannelNibblesForChunkInCache(this.nibbleCacheG, cx, cz, chunk.getSkyNibblesG());
        setChannelNibblesForChunkInCache(this.nibbleCacheB, cx, cz, chunk.getSkyNibblesB());
    }

    @Override
    protected void syncToVanilla(final ExtendedBlockStorage section, final SWMRNibbleArray nibR, final SWMRNibbleArray nibG, final SWMRNibbleArray nibB, final int minByte, final int maxByte) {
        final NibbleArray vanilla = section.getSkylightArray();
        if (vanilla == null) return;
        final byte[] rData = nibR != null ? nibR.getVisibleData() : null;
        final byte[] gData = nibG != null ? nibG.getVisibleData() : null;
        final byte[] bData = nibB != null ? nibB.getVisibleData() : null;
        for (int i = minByte; i <= maxByte; ++i) {
            final int rByte = rData != null ? rData[i] & 0xFF : 0xFF;
            final int gByte = gData != null ? gData[i] & 0xFF : 0xFF;
            final int bByte = bData != null ? bData[i] & 0xFF : 0xFF;
            final int rLo = rByte & 0xF, rHi = (rByte >>> 4) & 0xF;
            final int gLo = gByte & 0xF, gHi = (gByte >>> 4) & 0xF;
            final int bLo = bByte & 0xF, bHi = (bByte >>> 4) & 0xF;
            final int maxLo = Math.max(rLo, Math.max(gLo, bLo));
            final int maxHi = Math.max(rHi, Math.max(gHi, bHi));
            vanilla.data[i] = (byte) (maxLo | (maxHi << 4));
        }
    }

    @Override
    protected boolean[] getEmptinessMap(final Chunk chunk) {
        return ((SupernovaChunk) chunk).getSkyEmptinessMap();
    }

    @Override
    protected void setEmptinessMap(final Chunk chunk, final boolean[] to) {
        ((SupernovaChunk) chunk).setSkyEmptinessMap(to);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesOnChunk(final Chunk chunk) {
        return ((SupernovaChunk) chunk).getSkyNibbles();
    }

    @Override
    protected void setNibbles(final Chunk chunk, final SWMRNibbleArray[] to) {
        ((SupernovaChunk) chunk).setSkyNibbles(to);
    }

    @Override
    protected boolean canUseChunk(final Chunk chunk) {
        return ((SupernovaChunk) chunk).isLightReady();
    }

    private void rewriteNibbleCacheForSkylight() {
        for (int index = 0, max = this.nibbleCache.length; index < max; ++index) {
            final SWMRNibbleArray nibble = this.nibbleCache[index];
            if (nibble != null && nibble.isNullNibbleUpdating()) {
                this.nibbleCache[index] = null;
                this.nibbleCacheR[index] = null;
                nibble.updateVisible();
                // Invalidate packed cache for this section
                releasePackedArray(this.packedRGBCache[index]);
                this.packedRGBCache[index] = null;
                this.packedCacheDirty[index] = false;
            }
            final SWMRNibbleArray nibG = this.nibbleCacheG[index];
            if (nibG != null && nibG.isNullNibbleUpdating()) {
                this.nibbleCacheG[index] = null;
                nibG.updateVisible();
            }
            final SWMRNibbleArray nibB = this.nibbleCacheB[index];
            if (nibB != null && nibB.isNullNibbleUpdating()) {
                this.nibbleCacheB[index] = null;
                nibB.updateVisible();
            }
        }
    }

    private boolean checkNullSection(final int chunkX, final int chunkY, final int chunkZ, final boolean extrudeInitialised) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.nullPropagationCheckCache[chunkY - this.minLightSection]) {
            return false;
        }
        this.nullPropagationCheckCache[chunkY - this.minLightSection] = true;

        boolean needInitNeighbours = false;
        neighbour_search:
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                final SWMRNibbleArray nibble = this.getNibbleFromCache(dx + chunkX, chunkY, dz + chunkZ);
                if (nibble != null && !nibble.isNullNibbleUpdating()) {
                    needInitNeighbours = true;
                    break neighbour_search;
                }
            }
        }

        if (needInitNeighbours) {
            for (int dz = -1; dz <= 1; ++dz) {
                for (int dx = -1; dx <= 1; ++dx) {
                    this.initNibble(dx + chunkX, chunkY, dz + chunkZ, (dx | dz) == 0 ? extrudeInitialised : true, true);
                }
            }
        }

        return needInitNeighbours;
    }

    private int getLightLevelExtruded(final int worldX, final int worldY, final int worldZ) {
        final int chunkX = worldX >> 4;
        int chunkY = worldY >> 4;
        final int chunkZ = worldZ >> 4;

        final int idx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
        // Check packed cache first, fall back to nibble
        if (this.packedRGBCache[idx] != null || this.nibbleCacheR[idx] != null) {
            return getLightLevelFromCache(idx, (worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8));
        }

        while (++chunkY <= this.maxLightSection) {
            final int nextIdx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
            if (this.packedRGBCache[nextIdx] != null || this.nibbleCacheR[nextIdx] != null) {
                return getLightLevelFromCache(nextIdx, (worldX & 15) | ((worldZ & 15) << 4));
            }
        }
        return PackedColorLight.pack(15, 15, 15);
    }

    @Override
    protected void checkChunkEdges(final Chunk chunk, final int fromSection, final int toSection) {
        Arrays.fill(this.nullPropagationCheckCache, false);
        this.rewriteNibbleCacheForSkylight();
        final int chunkX = chunk.xPosition;
        final int chunkZ = chunk.zPosition;
        for (int y = toSection; y >= fromSection; --y) {
            if (!this.isLightSectionInRange(y)) {
                continue;
            }
            this.checkNullSection(chunkX, y, chunkZ, true);
        }
        super.checkChunkEdges(chunk, fromSection, toSection);
    }

    @Override
    protected void prepareBatchedEdgeChecks(final int chunkX, final int chunkZ) {
        Arrays.fill(this.nullPropagationCheckCache, false);
        this.rewriteNibbleCacheForSkylight();
        for (int y = this.maxLightSection; y >= this.minLightSection; --y) {
            this.checkNullSection(chunkX, y, chunkZ, true);
        }
    }

    @Override
    protected void checkChunkEdge(final int chunkX, final int chunkY, final int chunkZ) {
        final int currIdx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
        final SWMRNibbleArray currNibble = this.nibbleCache[currIdx];
        if (currNibble == null) return;

        final LightStats s = this.stats;

        for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
            final int neighbourOffX = direction.x;
            final int neighbourOffZ = direction.z;
            final int nIdx = (chunkX + neighbourOffX) + 5 * (chunkZ + neighbourOffZ) + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
            final SWMRNibbleArray neighbourNibble = this.nibbleCache[nIdx];
            if (neighbourNibble == null) continue;
            if (!currNibble.isInitialisedUpdating() && !neighbourNibble.isInitialisedUpdating()) continue;
            if (this.areBothEdgeSectionsFull(currIdx, nIdx)) {
                if (s != null) s.edgeSectionPairsSkippedFull.incrementAndGet();
                continue;
            }
            if (this.areBothEdgeSectionsZero(currIdx, nIdx)) {
                if (s != null) s.edgeSectionPairsSkippedZero.incrementAndGet();
                continue;
            }

            if (s != null) s.edgeSectionPairsChecked.incrementAndGet();

            // Ensure both sections have packed caches for fast inner loop access
            if (this.packedRGBCache[currIdx] == null) this.packSectionToCache(currIdx);
            if (this.packedRGBCache[nIdx] == null) this.packSectionToCache(nIdx);
            final int[] cPacked = this.packedRGBCache[currIdx];
            final int[] nPacked = this.packedRGBCache[nIdx];
            if (cPacked == null || nPacked == null) continue;

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
                    final int currentLevel = cPacked[currentIndex];

                    final int neighbourIndex = (neighbourX & 15) | ((neighbourZ & 15) << 4) | ((currY & 15) << 8);
                    final int neighbourLevel = nPacked[neighbourIndex];

                    if (currentLevel == 0 && neighbourLevel == 0) {
                        blocksTrivial++;
                        continue;
                    }
                    if (currentLevel == WHITE && neighbourLevel == WHITE) {
                        blocksTrivial++;
                        continue;
                    }

                    // Consistency early-out -- if attenuated neighbor can't exceed current level on any channel (and vice versa), this edge block is provably consistent
                    final int attN = PackedColorLight.packedSubRGB(neighbourLevel, MIN_ABSORPTION);
                    final int attC = PackedColorLight.packedSubRGB(currentLevel, MIN_ABSORPTION);
                    if (!PackedColorLight.anyComponentGreater(attN, currentLevel) && !PackedColorLight.anyComponentGreater(attC, neighbourLevel)) {
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

    @Override
    protected void checkBlock(final int worldX, final int worldY, final int worldZ) {
        final int encodeOffset = this.coordinateOffset;
        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);

        if (currentLevel == WHITE) {
            // WHITE is a sky source -- re-propagate but never decrease
            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | PackedColorLightQueue.encodeQueuePackedRGB(currentLevel)
                    | (((long) ALL_DIRECTIONS_BITSET) << RGB_DIR_SHIFT));
        } else {
            this.setLightLevel(worldX, worldY, worldZ, 0);
            this.appendToDecreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | PackedColorLightQueue.encodeQueuePackedRGB(currentLevel)
                    | (((long) ALL_DIRECTIONS_BITSET) << RGB_DIR_SHIFT));
        }
    }

    @Override
    protected int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect) {

        if (expect == WHITE) {
            return expect;
        }

        final int sectionOffset = this.chunkSectionIndexOffset;
        final Block centerBlock = this.getBlock(worldX, worldY, worldZ);
        final int rawOpacity = centerBlock.getLightOpacity();
        final int meta = this.getBlockMeta(worldX, worldY, worldZ);
        final boolean isSided = rawOpacity > 1 && FaceOcclusion.hasSidedTransparency(centerBlock);
        final int fixedAbsorption = isSided ? 0
                : TranslucencyRegistry.getPackedAbsorption(this.world, centerBlock, meta, worldX, worldY, worldZ);

        int level = 0;
        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
            final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

            final int neighbourRGB = this.getLightLevel(sectionIndex, localIndex);
            final int absorption = isSided ? FaceOcclusion.getDirectionalAbsorption(centerBlock, meta, rawOpacity, direction.ordinal()) : fixedAbsorption;
            final int attenuated = PackedColorLight.packedSubRGB(neighbourRGB, absorption);
            level = PackedColorLight.packedMax(attenuated, level);

            if (PackedColorLight.anyComponentGreater(level, expect)) {
                return level;
            }
        }

        return level;
    }

    @Override
    protected void propagateBlockChanges(final Chunk atChunk, final int blockX, final int blockY, final int blockZ) {
        this.rewriteNibbleCacheForSkylight();
        Arrays.fill(this.nullPropagationCheckCache, false);

        final int maxPropagationY = this.tryPropagateSkylight(blockX, blockY, blockZ, true, true);

        final long propagateDirection = AxisDirection.POSITIVE_Y.everythingButThisDirection;
        final int encodeOffset = this.coordinateOffset;

        final int extrudedLevel = this.getLightLevelExtruded(blockX, maxPropagationY, blockZ);
        if (extrudedLevel == WHITE) {
            this.checkNullSection(blockX >> 4, maxPropagationY >> 4, blockZ >> 4, true);

            for (int currY = maxPropagationY; currY >= (this.minLightSection << 4); --currY) {
                if ((currY & 15) == 15) {
                    this.checkNullSection(blockX >> 4, currY >> 4, blockZ >> 4, true);
                }

                final SWMRNibbleArray nibR = this.nibbleCacheR[(blockX >> 4) + 5 * (blockZ >> 4) + (5 * 5) * (currY >> 4) + this.chunkSectionIndexOffset];
                if (nibR == null) {
                    currY = currY & ~15;
                    continue;
                }

                final int currentRGB = this.getLightLevel(blockX, currY, blockZ);
                if (currentRGB != WHITE) {
                    break;
                }

                this.appendToDecreaseQueue(encodeCoords(blockX, blockZ, currY, encodeOffset) | PackedColorLightQueue.encodeQueuePackedRGB(WHITE) | (
                        propagateDirection
                                << RGB_DIR_SHIFT));
            }
        }

        this.applyDelayedQueue(this.increaseQueue, this.increaseQueueInitialLength, true);
        this.applyDelayedQueue(this.decreaseQueue, this.decreaseQueueInitialLength, false);

        this.checkBlock(blockX, blockY, blockZ);

        this.performLightDecrease();
    }

    @Override
    protected void lightChunk(final Chunk chunk, final boolean needsEdgeChecks) {
        this.rewriteNibbleCacheForSkylight();
        Arrays.fill(this.nullPropagationCheckCache, false);

        final int chunkX = chunk.xPosition;
        final int chunkZ = chunk.zPosition;

        int highestNonEmptySection = this.maxSection;
        ExtendedBlockStorage section = CubicChunksHelper.getBlockStorageArray(chunk, highestNonEmptySection);
        while (highestNonEmptySection >= this.minSection && (section == null || section.isEmpty())) {
            this.checkNullSection(chunkX, highestNonEmptySection, chunkZ, false);

            for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
                final int neighbourX = chunkX + direction.x;
                final int neighbourZ = chunkZ + direction.z;
                final SWMRNibbleArray neighbourNibble = this.getNibbleFromCache(neighbourX, highestNonEmptySection, neighbourZ);
                if (neighbourNibble == null) continue;

                final int incX, incZ, startX, startZ;
                if (direction.x != 0) {
                    incX = 0;
                    incZ = 1;
                    startX = direction.x < 0 ? chunkX << 4 : chunkX << 4 | 15;
                    startZ = chunkZ << 4;
                } else {
                    incX = 1;
                    incZ = 0;
                    startZ = direction.z < 0 ? chunkZ << 4 : chunkZ << 4 | 15;
                    startX = chunkX << 4;
                }

                final int encodeOffset = this.coordinateOffset;
                final long propagateDir = 1L << direction.ordinal();

                for (int currY = highestNonEmptySection << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                    for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                        this.appendToIncreaseQueue(encodeCoords(currX, currZ, currY, encodeOffset) | PackedColorLightQueue.encodeQueuePackedRGB(WHITE) | (
                                propagateDir
                                        << RGB_DIR_SHIFT));
                    }
                }
            }

            --highestNonEmptySection;
            section = CubicChunksHelper.getBlockStorageArray(chunk, highestNonEmptySection);
        }

        if (highestNonEmptySection >= this.minSection) {
            final int minX = chunkX << 4;
            final int maxX = chunkX << 4 | 15;
            final int minZ = chunkZ << 4;
            final int maxZ = chunkZ << 4 | 15;
            final int startY = highestNonEmptySection << 4 | 15;
            for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                for (int currX = minX; currX <= maxX; ++currX) {
                    this.tryPropagateSkylight(currX, startY + 1, currZ, false, false);
                }
            }
        }

        if (needsEdgeChecks) {
            this.performLightIncrease();
            for (int y = highestNonEmptySection; y >= this.minLightSection; --y) {
                this.checkNullSection(chunkX, y, chunkZ, false);
            }
            super.checkChunkEdges(chunk, this.minLightSection, highestNonEmptySection);
        } else {
            for (int y = highestNonEmptySection; y >= this.minLightSection; --y) {
                this.checkNullSection(chunkX, y, chunkZ, false);
            }
            this.propagateNeighbourLevels(chunk, this.minLightSection, highestNonEmptySection);
            this.performLightIncrease();
        }
    }

    private void applyDelayedQueue(final long[] queue, final int len, final boolean usePackedLevel) {
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;

        for (int i = 0; i < len; ++i) {
            final long queueValue = queue[i];
            final int posX = ((int) queueValue & 63) + decodeOffsetX;
            final int posZ = (((int) queueValue >>> 6) & 63) + decodeOffsetZ;
            final int posY = (((int) queueValue >>> 12) & 0xFFFF) + decodeOffsetY;
            final int level = usePackedLevel ? PackedColorLightQueue.decodeQueueRGB(queueValue) : 0;
            this.setLightLevel(posX, posY, posZ, level);
        }
    }

    private int tryPropagateSkylight(final int worldX, int startY, final int worldZ, final boolean extrudeInitialised, final boolean delayLightSet) {
        final int encodeOffset = this.coordinateOffset;
        final long propagateDirection = AxisDirection.POSITIVE_Y.everythingButThisDirection;

        final int extrudedLevel = this.getLightLevelExtruded(worldX, startY + 1, worldZ);
        if (PackedColorLight.maxComponent(extrudedLevel) == 0) {
            return startY;
        }

        this.checkNullSection(worldX >> 4, startY >> 4, worldZ >> 4, extrudeInitialised);

        int currentSkyRGB = extrudedLevel;

        // Cache the "above" block across iterations: each iteration's `current` becomes next iteration's `above`
        Block above = this.getBlock(worldX, startY + 1, worldZ);
        int aboveMeta = this.getBlockMeta(worldX, startY + 1, worldZ);

        for (; startY >= (this.minLightSection << 4); --startY) {
            if ((startY & 15) == 15) {
                this.checkNullSection(worldX >> 4, startY >> 4, worldZ >> 4, extrudeInitialised);
            }
            final Block current = this.getBlock(worldX, startY, worldZ);
            final int meta = this.getBlockMeta(worldX, startY, worldZ);

            // Check if light can pass DOWN through the above block
            final int aboveOpacity = above.getLightOpacity();
            if (aboveOpacity > 0) {
                if (!FaceOcclusion.hasSidedTransparency(above) || FaceOcclusion.isFaceSolid(above, aboveMeta, 5)) {
                    break;
                }
            }

            // Check if light can enter the current block from above
            final int currentOpacity = current.getLightOpacity();
            if (currentOpacity > 0) {
                if (!FaceOcclusion.hasSidedTransparency(current) || FaceOcclusion.isFaceSolid(current, meta, 4)) {
                    break;
                }
            }

            // Apply per-channel absorption for the current block
            if (current instanceof ColoredTranslucency || current instanceof PositionalColoredTranslucency || TranslucencyRegistry.hasExplicitEntry(current) || currentOpacity > 1) {
                currentSkyRGB = PackedColorLight.packedSubRGB(currentSkyRGB, TranslucencyRegistry.getPackedAbsorption(this.world, current, meta, worldX, startY, worldZ));
            }

            if (PackedColorLight.maxComponent(currentSkyRGB) == 0) {
                break;
            }

            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, startY, encodeOffset) | PackedColorLightQueue.encodeQueuePackedRGB(currentSkyRGB) | (
                    propagateDirection
                            << RGB_DIR_SHIFT));

            if (this.getNibbleFromCache(worldX >> 4, startY >> 4, worldZ >> 4) == null) {
                --this.increaseQueueInitialLength;
                startY = startY & ~15;
                above = Blocks.air;
                aboveMeta = 0;
            } else if (!delayLightSet) {
                this.setLightLevel(worldX, startY, worldZ, currentSkyRGB);
                above = current;
                aboveMeta = meta;
            } else {
                above = current;
                aboveMeta = meta;
            }
        }

        return startY;
    }

    @Override
    protected void performLightIncrease() {
        long[] queue = this.increaseQueue;
        int queueReadIndex = 0;
        int queueLength = this.increaseQueueInitialLength;
        this.increaseQueueInitialLength = 0;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;
        final int encodeOffset = this.coordinateOffset;
        final int sectionOffset = this.chunkSectionIndexOffset;

        while (queueReadIndex < queueLength) {
            final long queueValue = queue[queueReadIndex++];

            final int posX = ((int) queueValue & 63) + decodeOffsetX;
            final int posZ = (((int) queueValue >>> 6) & 63) + decodeOffsetZ;
            final int posY = (((int) queueValue >>> 12) & COORD_Y_MASK) + decodeOffsetY;
            final int propagatedRGB = PackedColorLightQueue.decodeQueueRGB(queueValue);
            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[(int) ((queueValue >>> RGB_DIR_SHIFT) & 63L)];

            final boolean hasSidedTransparent = (queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) != 0L;
            Block srcBlock = null;
            int srcMeta = 0;
            boolean checkSourceFaces = false;
            if (hasSidedTransparent) {
                final int srcIdx = (posX >> 4) + 5 * (posZ >> 4) + (5 * 5) * (posY >> 4) + sectionOffset;
                srcBlock = this.getBlockFast(srcIdx, posX & 15, posY & 15, posZ & 15);
                if (srcBlock != Blocks.air && FaceOcclusion.hasSidedTransparency(srcBlock)) {
                    final ExtendedBlockStorage srcSection = this.sectionCache[srcIdx];
                    if (srcSection != null) {
                        srcMeta = srcSection.getExtBlockMetadata(posX & 15, posY & 15, posZ & 15);
                    }
                    checkSourceFaces = true;
                }
            }

            if ((queueValue & FLAG_RECHECK_LEVEL) != 0L) {
                if (this.getLightLevel(posX, posY, posZ) != propagatedRGB) {
                    continue;
                }
            } else if ((queueValue & FLAG_WRITE_LEVEL) != 0L) {
                this.setLightLevel(posX, posY, posZ, propagatedRGB);
            }

            for (final AxisDirection propagate : checkDirections) {
                if (checkSourceFaces && FaceOcclusion.isFaceSolid(srcBlock, srcMeta, propagate.ordinal())) continue;

                final int offX = posX + propagate.x;
                final int offY = posY + propagate.y;
                final int offZ = posZ + propagate.z;

                final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                // Check R nibble exists (all 3 are init'd/null'd together)
                if (this.nibbleCacheR[sectionIndex] == null) {
                    continue;
                }

                final int currentRGB = this.getLightLevel(sectionIndex, localIndex);

                final Block destBlock = this.getBlockFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int absorption;
                if (destBlock == Blocks.air) {
                    absorption = MIN_ABSORPTION;
                } else {
                    final ExtendedBlockStorage section = this.sectionCache[sectionIndex];
                    final int destMeta = section != null ? section.getExtBlockMetadata(offX & 15, offY & 15, offZ & 15) : 0;
                    absorption = FaceOcclusion.resolveAbsorption(this.world, destBlock, destMeta, propagate.oppositeOrdinal, offX, offY, offZ);
                }

                final int targetRGB = PackedColorLight.packedSubRGB(propagatedRGB, absorption);
                if (!PackedColorLight.anyComponentGreater(targetRGB, currentRGB)) {
                    continue;
                }

                final int newRGB = PackedColorLight.packedMax(targetRGB, currentRGB);
                this.setLightLevelInCache(sectionIndex, localIndex, newRGB);
                this.postLightUpdate(sectionIndex);

                if (PackedColorLight.maxComponent(newRGB) > 1) {
                    if (queueLength >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        queue = this.resizeIncreaseQueue();
                    }
                    queue[queueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | PackedColorLightQueue.encodeQueuePackedRGB(newRGB)
                            | (propagate.everythingButTheOppositeDirection << RGB_DIR_SHIFT)
                            | sidedFlag(destBlock);
                }
            }
        }
        this.lastBfsIncreaseTotal += queueLength;
    }

    @Override
    protected void performLightDecrease() {
        long[] queue = this.decreaseQueue;
        long[] increaseQueue = this.increaseQueue;
        int queueReadIndex = 0;
        int queueLength = this.decreaseQueueInitialLength;
        this.decreaseQueueInitialLength = 0;
        int increaseQueueLength = this.increaseQueueInitialLength;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;
        final int encodeOffset = this.coordinateOffset;
        final int sectionOffset = this.chunkSectionIndexOffset;

        while (queueReadIndex < queueLength) {
            final long queueValue = queue[queueReadIndex++];

            final int posX = ((int) queueValue & 63) + decodeOffsetX;
            final int posZ = (((int) queueValue >>> 6) & 63) + decodeOffsetZ;
            final int posY = (((int) queueValue >>> 12) & COORD_Y_MASK) + decodeOffsetY;
            final int propagatedRGB = PackedColorLightQueue.decodeQueueRGB(queueValue);
            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[(int) ((queueValue >>> RGB_DIR_SHIFT) & 63)];

            final boolean hasSidedTransparent = (queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) != 0L;
            Block srcBlock = null;
            int srcMeta = 0;
            boolean checkSourceFaces = false;
            if (hasSidedTransparent) {
                final int srcIdx = (posX >> 4) + 5 * (posZ >> 4) + (5 * 5) * (posY >> 4) + sectionOffset;
                srcBlock = this.getBlockFast(srcIdx, posX & 15, posY & 15, posZ & 15);
                if (srcBlock != Blocks.air && FaceOcclusion.hasSidedTransparency(srcBlock)) {
                    final ExtendedBlockStorage srcSection = this.sectionCache[srcIdx];
                    if (srcSection != null) {
                        srcMeta = srcSection.getExtBlockMetadata(posX & 15, posY & 15, posZ & 15);
                    }
                    checkSourceFaces = true;
                }
            }

            for (final AxisDirection propagate : checkDirections) {
                if (checkSourceFaces && FaceOcclusion.isFaceSolid(srcBlock, srcMeta, propagate.ordinal())) continue;

                final int offX = posX + propagate.x;
                final int offY = posY + propagate.y;
                final int offZ = posZ + propagate.z;

                final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;

                if (this.nibbleCacheR[sectionIndex] == null) {
                    continue;
                }

                final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);
                final int currentRGB = this.getLightLevel(sectionIndex, localIndex);
                if (currentRGB == 0) {
                    continue;
                }
                // Full white sky light is a sky source -- immutable during decrease
                if (currentRGB == WHITE) {
                    continue;
                }

                final Block block = this.getBlockFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int absorption;
                if (block == Blocks.air) {
                    absorption = MIN_ABSORPTION;
                } else {
                    final ExtendedBlockStorage section = this.sectionCache[sectionIndex];
                    final int destMeta = section != null ? section.getExtBlockMetadata(offX & 15, offY & 15, offZ & 15) : 0;
                    absorption = FaceOcclusion.resolveAbsorption(this.world, block, destMeta, propagate.oppositeOrdinal, offX, offY, offZ);
                }

                final int targetRGB = PackedColorLight.packedSubRGB(propagatedRGB, absorption);
                final long sFlag = sidedFlag(block);

                // Per-channel: channels where current > target have another source
                final int keptMask = PackedColorLightQueue.channelMaskWhereGt(currentRGB, targetRGB);

                if (keptMask == PackedColorLight.ALL_CHANNELS) {
                    // All channels have another source -- re-propagate with RECHECK
                    if (increaseQueueLength >= increaseQueue.length) {
                        if (increaseQueue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        increaseQueue = this.resizeIncreaseQueue();
                    }
                    increaseQueue[increaseQueueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | PackedColorLightQueue.encodeQueuePackedRGB(currentRGB)
                            | (((long) ALL_DIRECTIONS_BITSET) << RGB_DIR_SHIFT)
                            | FLAG_RECHECK_LEVEL
                            | sFlag;
                    continue;
                }

                // At least some channels need clearing
                final int keptRGB = currentRGB & keptMask;

                // Sky light has no per-block emission
                this.setLightLevelInCache(sectionIndex, localIndex, keptRGB);
                this.postLightUpdate(sectionIndex);

                // Re-increase from remaining light
                if (keptRGB != 0) {
                    if (increaseQueueLength >= increaseQueue.length) {
                        if (increaseQueue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        increaseQueue = this.resizeIncreaseQueue();
                    }
                    increaseQueue[increaseQueueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | PackedColorLightQueue.encodeQueuePackedRGB(keptRGB)
                            | (((long) ALL_DIRECTIONS_BITSET) << RGB_DIR_SHIFT)
                            | FLAG_RECHECK_LEVEL
                            | sFlag;
                }

                // Continue decrease only for cleared channels that actually had light. Masking by channelPresenceMask prevents re-propagating channels already
                // zeroed by a prior BFS visit from a different direction.
                final int clearedMask = ~keptMask & PackedColorLight.ALL_CHANNELS;
                final int decreaseRGB = targetRGB & clearedMask & PackedColorLight.channelPresenceMask(currentRGB);
                if (PackedColorLight.anyNonZero(decreaseRGB)) {
                    if (queueLength >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        queue = this.resizeDecreaseQueue();
                    }
                    queue[queueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | PackedColorLightQueue.encodeQueuePackedRGB(decreaseRGB)
                            | (propagate.everythingButTheOppositeDirection << RGB_DIR_SHIFT)
                            | sFlag;
                }
            }
        }

        this.lastBfsDecreaseTotal += queueLength;
        this.increaseQueueInitialLength = increaseQueueLength;
        this.performLightIncrease();
    }

}
