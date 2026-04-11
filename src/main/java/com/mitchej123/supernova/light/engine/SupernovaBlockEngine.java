package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.ColoredLightSource;
import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.PackedColorLight;
import com.mitchej123.supernova.api.TranslucencyRegistry;
import com.mitchej123.supernova.compat.cubicchunks.CubicChunksHelper;
import com.mitchej123.supernova.light.LightStats;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.util.SnapshotChunkMap;
import com.mitchej123.supernova.util.WorldUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public class SupernovaBlockEngine extends SupernovaRGBEngine {

    private final IBlockAccess safeBlockAccess;

    public SupernovaBlockEngine(final World world, final SnapshotChunkMap chunkMap) {
        super(false, world);
        this.safeBlockAccess = new SafeBlockAccess(chunkMap);
    }

    @Override
    protected boolean[] getEmptinessMap(final Chunk chunk) {
        return ((SupernovaChunk) chunk).getBlockEmptinessMap();
    }

    @Override
    protected void setEmptinessMap(final Chunk chunk, final boolean[] to) {
        ((SupernovaChunk) chunk).setBlockEmptinessMap(to);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesOnChunk(final Chunk chunk) {
        return ((SupernovaChunk) chunk).getBlockNibblesR();
    }

    @Override
    protected void setNibbles(final Chunk chunk, final SWMRNibbleArray[] to) {
        ((SupernovaChunk) chunk).setBlockNibblesR(to);
    }

    @Override
    protected boolean canUseChunk(final Chunk chunk) {
        return ((SupernovaChunk) chunk).isLightReady();
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        // Block light uses setHidden() instead of setNull() -- maintains data for decrease propagation
        final int idx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
        final SWMRNibbleArray nibR = this.nibbleCacheR[idx];
        if (nibR != null) nibR.setHidden();
        final SWMRNibbleArray nibG = this.nibbleCacheG[idx];
        if (nibG != null) nibG.setHidden();
        final SWMRNibbleArray nibB = this.nibbleCacheB[idx];
        if (nibB != null) nibB.setHidden();
        // Don't release packed cache for HIDDEN -- data is still valid for decrease propagation
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }
        final int idx = chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset;
        initSingleNibble(this.nibbleCache, idx, initRemovedNibbles);
        initSingleNibble(this.nibbleCacheR, idx, initRemovedNibbles);
        initSingleNibble(this.nibbleCacheG, idx, initRemovedNibbles);
        initSingleNibble(this.nibbleCacheB, idx, initRemovedNibbles);
        // Invalidate packed cache -- nibble data changed
        releasePackedArray(this.packedRGBCache[idx]);
        this.packedRGBCache[idx] = null;
        this.packedCacheDirty[idx] = false;
    }

    private static void initSingleNibble(final SWMRNibbleArray[] cache, final int idx, final boolean initRemovedNibbles) {
        final SWMRNibbleArray nibble = cache[idx];
        if (nibble == null) {
            if (!initRemovedNibbles) {
                return;
            }
            cache[idx] = new SWMRNibbleArray();
        } else {
            nibble.setNonNull();
        }
    }

    @Override
    protected void loadExtraNibblesToCache(final int cx, final int cz, final Chunk chunk) {
        final SupernovaChunk ext = (SupernovaChunk) chunk;
        setChannelNibblesForChunkInCache(this.nibbleCacheR, cx, cz, ext.getBlockNibblesR());
        setChannelNibblesForChunkInCache(this.nibbleCacheG, cx, cz, ext.getBlockNibblesG());
        setChannelNibblesForChunkInCache(this.nibbleCacheB, cx, cz, ext.getBlockNibblesB());
    }

    @Override
    protected void saveChannelNibbles(final SupernovaChunk chunk, final SWMRNibbleArray[] g, final SWMRNibbleArray[] b) {
        chunk.setBlockNibblesG(g);
        chunk.setBlockNibblesB(b);
    }

    @Override
    protected void loadChannelNibbles(final SupernovaChunk chunk, final int cx, final int cz) {
        setChannelNibblesForChunkInCache(this.nibbleCacheR, cx, cz, chunk.getBlockNibblesR());
        setChannelNibblesForChunkInCache(this.nibbleCacheG, cx, cz, chunk.getBlockNibblesG());
        setChannelNibblesForChunkInCache(this.nibbleCacheB, cx, cz, chunk.getBlockNibblesB());
    }

    @Override
    protected void syncToVanilla(final ExtendedBlockStorage section, final SWMRNibbleArray nibR, final SWMRNibbleArray nibG, final SWMRNibbleArray nibB, final int minByte, final int maxByte) {
        final NibbleArray vanilla = section.getBlocklightArray();
        if (vanilla == null) return;
        final byte[] rData = nibR != null ? nibR.getVisibleData() : null;
        final byte[] gData = nibG != null ? nibG.getVisibleData() : null;
        final byte[] bData = nibB != null ? nibB.getVisibleData() : null;
        for (int i = minByte; i <= maxByte; ++i) {
            final int rByte = rData != null ? rData[i] & 0xFF : 0;
            final int gByte = gData != null ? gData[i] & 0xFF : 0;
            final int bByte = bData != null ? bData[i] & 0xFF : 0;
            final int rLo = rByte & 0xF, rHi = (rByte >>> 4) & 0xF;
            final int gLo = gByte & 0xF, gHi = (gByte >>> 4) & 0xF;
            final int bLo = bByte & 0xF, bHi = (bByte >>> 4) & 0xF;
            final int maxLo = Math.max(rLo, Math.max(gLo, bLo));
            final int maxHi = Math.max(rHi, Math.max(gHi, bHi));
            vanilla.data[i] = (byte) (maxLo | (maxHi << 4));
        }
    }

    @Override
    protected void checkBlock(final int worldX, final int worldY, final int worldZ) {
        final int encodeOffset = this.coordinateOffset;
        final int currentRGB = this.getLightLevel(worldX, worldY, worldZ);

        final Block block = this.getBlock(worldX, worldY, worldZ);
        final int meta = this.getBlockMeta(worldX, worldY, worldZ);
        final int emittedRGB = LightColorRegistry.getPackedEmission(this.safeBlockAccess, block, meta, worldX, worldY, worldZ);

        final int calculatedRGB = this.calculateLightValueWithBlock(worldX, worldY, worldZ, PackedColorLight.ALL_CHANNELS, block, meta);
        // Early out: if current value already matches full expectation, nothing changed
        if (currentRGB == calculatedRGB) {
            return;
        }

        this.setLightLevel(worldX, worldY, worldZ, emittedRGB);

        final long sf = sidedFlag(block);

        if (emittedRGB != 0) {
            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | PackedColorLightQueue.encodeQueuePackedRGB(emittedRGB)
                    | (((long) ALL_DIRECTIONS_BITSET) << RGB_DIR_SHIFT)
                    | sf);
        }

        this.appendToDecreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                | PackedColorLightQueue.encodeQueuePackedRGB(currentRGB)
                | (((long) ALL_DIRECTIONS_BITSET) << RGB_DIR_SHIFT)
                | sf);
    }

    @Override
    protected int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect) {
        final Block block = this.getBlock(worldX, worldY, worldZ);
        final int meta = this.getBlockMeta(worldX, worldY, worldZ);
        return calculateLightValueWithBlock(worldX, worldY, worldZ, expect, block, meta);
    }

    private int calculateLightValueWithBlock(final int worldX, final int worldY, final int worldZ, final int expect, final Block block, final int meta) {
        int level = LightColorRegistry.getPackedEmission(this.safeBlockAccess, block, meta, worldX, worldY, worldZ);

        if (PackedColorLight.maxComponent(level) >= 14 || PackedColorLight.anyComponentGreater(level, expect)) {
            return level;
        }

        final int rawOpacity = block.getLightOpacity();
        final boolean isSided = rawOpacity > 1 && FaceOcclusion.hasSidedTransparency(block);
        final int fixedAbsorption = isSided ? 0
                : TranslucencyRegistry.getPackedAbsorption(this.safeBlockAccess, block, meta, worldX, worldY, worldZ);
        final int sectionOffset = this.chunkSectionIndexOffset;

        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
            final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

            final int neighbourRGB = this.getLightLevel(sectionIndex, localIndex);
            final int absorption = isSided
                    ? FaceOcclusion.getDirectionalAbsorption(block, meta, rawOpacity, direction.ordinal())
                    : fixedAbsorption;
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
        this.checkBlock(blockX, blockY, blockZ);
        this.performLightDecrease();
    }

    @Override
    protected void lightChunk(final Chunk chunk, final boolean needsEdgeChecks) {
        final int offX = chunk.xPosition << 4;
        final int offZ = chunk.zPosition << 4;
        int emitterCount = 0;

        for (int sectionY = this.minSection; sectionY <= this.maxSection; ++sectionY) {
            final ExtendedBlockStorage section = CubicChunksHelper.getBlockStorageArray(chunk, sectionY);
            if (section == null || section.isEmpty()) {
                continue;
            }

            final int offY = sectionY << 4;
            final int sectionIdx = chunk.xPosition + 5 * chunk.zPosition + (5 * 5) * sectionY + this.chunkSectionIndexOffset;

            for (int index = 0; index < (16 * 16 * 16); ++index) {
                final int lx = index & 15;
                final int ly = index >>> 8;
                final int lz = (index >>> 4) & 15;

                final int worldX = offX | lx;
                final int worldY = offY | ly;
                final int worldZ = offZ | lz;

                final Block block = this.getBlockFast(sectionIdx, lx, ly, lz);
                // Use non-positional getLightValue() -- during lightChunk we must not call
                // World.getBlock() as it can trigger recursive chunk loading/generation.
                if (block.getLightValue() <= 0 && !LightColorRegistry.hasExplicitEntry(block)) {
                    continue;
                }

                final int meta = section.getExtBlockMetadata(lx, ly, lz);

                // Use world-safe variant to avoid recursive chunk loading during generation.
                final int emittedRGB = LightColorRegistry.getPackedEmissionNoWorld(block, meta);
                if (emittedRGB == 0) {
                    continue;
                }

                final int currentRGB = this.getLightLevel(worldX, worldY, worldZ);
                if (!PackedColorLight.anyComponentGreater(emittedRGB, currentRGB)) {
                    continue;
                }

                final int newRGB = PackedColorLight.packedMax(emittedRGB, currentRGB);
                emitterCount++;
                this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, this.coordinateOffset)
                        | PackedColorLightQueue.encodeQueuePackedRGB(newRGB)
                        | (((long) ALL_DIRECTIONS_BITSET) << RGB_DIR_SHIFT)
                        | sidedFlag(block));

                this.setLightLevel(worldX, worldY, worldZ, newRGB);
            }
        }

        if (needsEdgeChecks) {
            this.performLightIncrease();
            this.checkChunkEdges(chunk, this.minLightSection, this.maxLightSection);
        } else {
            this.propagateNeighbourLevels(chunk, this.minLightSection, this.maxLightSection);
            this.performLightIncrease();
        }
    }

    @Override
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
            this.checkBlock(worldX, worldY, worldZ);  // enqueue only, no BFS yet
        }
        this.performLightDecrease();  // single batched pass for all positions
    }

    @Override
    protected boolean areBothEdgeSectionsFull(final int currIdx, final int nIdx) {
        return isEdgeSectionFull(currIdx) && isEdgeSectionFull(nIdx);
    }

    private boolean isEdgeSectionFull(final int idx) {
        if (this.packedRGBCache[idx] != null) {
            final int full = PackedColorLight.pack(15, 15, 15);
            return this.packedAnd[idx] == full && this.packedOr[idx] == full;
        }
        final SWMRNibbleArray r = this.nibbleCacheR[idx];
        final SWMRNibbleArray g = this.nibbleCacheG[idx];
        final SWMRNibbleArray b = this.nibbleCacheB[idx];
        return r != null && r.isFullUpdating() && g != null && g.isFullUpdating() && b != null && b.isFullUpdating();
    }

    @Override
    protected boolean areBothEdgeSectionsZero(final int currIdx, final int nIdx) {
        return isEdgeSectionZero(currIdx) && isEdgeSectionZero(nIdx);
    }

    private boolean isEdgeSectionZero(final int idx) {
        if (this.packedRGBCache[idx] != null) {
            return this.packedOr[idx] == 0;
        }
        final SWMRNibbleArray r = this.nibbleCacheR[idx];
        final SWMRNibbleArray g = this.nibbleCacheG[idx];
        final SWMRNibbleArray b = this.nibbleCacheB[idx];
        return (r == null || r.isZeroUpdating()) && (g == null || g.isZeroUpdating()) && (b == null || b.isZeroUpdating());
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

                    // RGB-aware consistency: if attenuated neighbor can't exceed current on any channel (and vice versa), this edge block is provably consistent
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
                    absorption = FaceOcclusion.resolveAbsorption(this.safeBlockAccess, destBlock, destMeta, propagate.oppositeOrdinal, offX, offY, offZ);
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

                final Block block = this.getBlockFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int absorption;
                int destMeta = 0;
                if (block == Blocks.air) {
                    absorption = MIN_ABSORPTION;
                } else {
                    final ExtendedBlockStorage section = this.sectionCache[sectionIndex];
                    destMeta = section != null ? section.getExtBlockMetadata(offX & 15, offY & 15, offZ & 15) : 0;
                    absorption = FaceOcclusion.resolveAbsorption(this.safeBlockAccess, block, destMeta, propagate.oppositeOrdinal, offX, offY, offZ);
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

                // Clear to kept-only during decrease; emission deferred to increase phase
                this.setLightLevelInCache(sectionIndex, localIndex, keptRGB);
                this.postLightUpdate(sectionIndex);

                // Fast path: field read avoids World.getBlock() for non-emitting blocks
                final int emittedRGB;
                if (block.getLightValue() <= 0 && !(block instanceof ColoredLightSource) && !LightColorRegistry.hasExplicitEntry(block)) {
                    emittedRGB = 0;
                } else {
                    emittedRGB = LightColorRegistry.getPackedEmission(this.safeBlockAccess, block, destMeta, offX, offY, offZ);
                }
                final int newRGB = PackedColorLight.packedMax(keptRGB, emittedRGB);

                // Re-increase from remaining light (kept + emission)
                if (newRGB != 0) {
                    final long flags = (newRGB != keptRGB) ? FLAG_WRITE_LEVEL : FLAG_RECHECK_LEVEL;
                    if (increaseQueueLength >= increaseQueue.length) {
                        if (increaseQueue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        increaseQueue = this.resizeIncreaseQueue();
                    }
                    increaseQueue[increaseQueueLength++] = encodeCoords(offX, offZ, offY, encodeOffset) | PackedColorLightQueue.encodeQueuePackedRGB(newRGB) | (
                            ((long) ALL_DIRECTIONS_BITSET)
                                    << RGB_DIR_SHIFT) | flags | sFlag;
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
