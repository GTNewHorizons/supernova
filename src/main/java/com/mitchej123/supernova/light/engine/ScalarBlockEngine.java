package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.ExtendedSection;
import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.PackedColorLight;
import com.mitchej123.supernova.light.DynamicEmissionSnapshots;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.util.WorldUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public class ScalarBlockEngine extends SupernovaEngine {

    private final DynamicEmissionSnapshots dynamicEmission;

    public ScalarBlockEngine(final World world) {
        this(world, null);
    }

    public ScalarBlockEngine(final World world, final DynamicEmissionSnapshots dynamicEmission) {
        super(false, world);
        this.dynamicEmission = dynamicEmission;
    }

    private int emissionAt(final Block block, final int blockId, final int x, final int y, final int z) {
        if (this.dynamicEmission != null && LightColorRegistry.isPositional(blockId)) {
            final Integer sampled = this.dynamicEmission.get(x, y, z);
            if (sampled != null) return PackedColorLight.maxComponent(sampled);
        }
        return block.getLightValue() & 0xF;
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
        return ((SupernovaChunk) chunk).isLightUsable();
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }
        final SWMRNibbleArray nib = this.nibbleCache[chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
        if (nib == null) {
            if (!initRemovedNibbles) {
                return;
            }
            this.setNibbleInCache(chunkX, chunkY, chunkZ, new SWMRNibbleArray());
        } else {
            nib.setNonNull();
        }
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        // setHidden(), not setNull(): the data still feeds decrease propagation.
        final SWMRNibbleArray nib = this.nibbleCache[chunkX + 5 * chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
        if (nib != null) {
            nib.setHidden();
        }
    }

    @Override
    protected void checkBlock(final int worldX, final int worldY, final int worldZ) {
        final int encodeOffset = this.coordinateOffset;
        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);

        final Block block = this.getBlock(worldX, worldY, worldZ);
        final long sf = sidedFlag(this.lastBlockId);
        final int emission = emissionAt(block, this.lastBlockId, worldX, worldY, worldZ);

        final int calculatedLevel = this.calculateLightValueWithBlock(worldX, worldY, worldZ, 15, block);
        if (currentLevel == calculatedLevel) {
            return;
        }

        this.setLightLevel(worldX, worldY, worldZ, emission);

        if (emission > 0) {
            this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                    | this.encodeQueueLevel(emission)
                    | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                    | sf);
        }

        this.appendToDecreaseQueue(encodeCoords(worldX, worldZ, worldY, encodeOffset)
                | this.encodeQueueLevel(currentLevel)
                | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                | sf);
    }

    @Override
    protected int calculateLightValue(final int worldX, final int worldY, final int worldZ, final int expect) {
        final Block block = this.getBlock(worldX, worldY, worldZ);
        return calculateLightValueWithBlock(worldX, worldY, worldZ, expect, block);
    }

    private int calculateLightValueWithBlock(final int worldX, final int worldY, final int worldZ, final int expect, final Block block) {
        int level = emissionAt(block, this.lastBlockId, worldX, worldY, worldZ);

        if (level >= 14 || level > expect) {
            return level;
        }

        final int rawOpacity = block.getLightOpacity();
        final int meta = this.getBlockMeta(worldX, worldY, worldZ);
        final boolean sidedTransparent = rawOpacity > 1 && FaceOcclusion.hasSidedTransparency(block);
        final int uniformAbsorption = !sidedTransparent ? Math.max(1, rawOpacity) : 0;
        final int sectionOffset = this.chunkSectionIndexOffset;

        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
            final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

            final int neighbourLevel = this.getLightLevel(sectionIndex, localIndex);

            final int absorption = sidedTransparent ? (FaceOcclusion.isFaceSolid(block, meta, direction.ordinal()) ? rawOpacity : 1) : uniformAbsorption;
            final int attenuated = neighbourLevel - absorption;
            if (attenuated > level) {
                level = attenuated;
            }

            if (level > expect) {
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
            this.checkBlock(worldX, worldY, worldZ);
        }
        this.performLightDecrease();
    }

    @Override
    protected void lightChunk(final Chunk chunk, final boolean needsEdgeChecks) {
        final int offX = chunk.xPosition << 4;
        final int offZ = chunk.zPosition << 4;
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();

        for (int sectionY = this.minSection; sectionY <= this.maxSection; ++sectionY) {
            final ExtendedBlockStorage section = sections[sectionY - this.minSection];
            if (section == null || ((ExtendedSection) section).supernova$hasNoBlocks()) {
                continue;
            }

            final int offY = sectionY << 4;
            final int sectionIdx = chunk.xPosition + 5 * chunk.zPosition + (5 * 5) * sectionY + this.chunkSectionIndexOffset;

            for (int index = 0; index < (16 * 16 * 16); ++index) {
                final int lx = index & 15;
                final int ly = index >>> 8;
                final int lz = (index >>> 4) & 15;

                final Block block = this.getBlockFast(sectionIdx, lx, ly, lz);
                final int blockId = this.lastBlockId;
                final int worldX = offX | lx;
                final int worldY = offY | ly;
                final int worldZ = offZ | lz;
                final int emission = emissionAt(block, blockId, worldX, worldY, worldZ);
                if (emission <= 0) {
                    continue;
                }

                final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);
                if (emission <= currentLevel) {
                    continue;
                }

                this.appendToIncreaseQueue(encodeCoords(worldX, worldZ, worldY, this.coordinateOffset)
                        | this.encodeQueueLevel(emission)
                        | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                        | sidedFlag(blockId));

                this.setLightLevel(worldX, worldY, worldZ, emission);
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
            final int propagatedLevel = (int) ((queueValue >>> LIGHT_LEVEL_SHIFT) & 0xF);
            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[(int) ((queueValue >>> DIRECTION_SHIFT) & 63L)];

            final boolean checkSourceFaces = this.resolveSourceFaces(queueValue, posX, posY, posZ, sectionOffset);

            if ((queueValue & FLAG_RECHECK_LEVEL) != 0L) {
                if (this.getLightLevel(posX, posY, posZ) != propagatedLevel) {
                    continue;
                }
            } else if ((queueValue & FLAG_WRITE_LEVEL) != 0L) {
                this.setLightLevel(posX, posY, posZ, propagatedLevel);
            }

            for (final AxisDirection propagate : checkDirections) {
                if (checkSourceFaces && this.isSourceFaceSolid(propagate.ordinal())) continue;

                final int offX = posX + propagate.x;
                final int offY = posY + propagate.y;
                final int offZ = posZ + propagate.z;

                final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                if (this.nibbleCache[sectionIndex] == null) {
                    continue;
                }

                final int currentLevel = this.getLightLevel(sectionIndex, localIndex);

                final Block destBlock = this.getBlockFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int destId = this.lastBlockId;
                final int absorption;
                if (destBlock == Blocks.air) {
                    absorption = 1;
                } else {
                    final ExtendedBlockStorage section = this.sectionCache[sectionIndex];
                    final int destMeta = section != null ? section.getExtBlockMetadata(offX & 15, offY & 15, offZ & 15) : 0;
                    absorption = FaceOcclusion.resolveScalarAbsorption(destId, destBlock, destMeta, propagate.oppositeOrdinal, offX, offY, offZ);
                }

                final int targetLevel = propagatedLevel - absorption;
                if (targetLevel <= currentLevel) {
                    continue;
                }

                if (this.setLightLevelInCache(sectionIndex, localIndex, targetLevel)) this.postLightUpdate(sectionIndex, localIndex);

                if (targetLevel > 1) {
                    if (queueLength >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        queue = this.resizeIncreaseQueue();
                    }
                    queue[queueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(targetLevel)
                            | (propagate.everythingButTheOppositeDirection << DIRECTION_SHIFT)
                            | sidedFlag(destId);
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
            final int propagatedLevel = (int) ((queueValue >>> LIGHT_LEVEL_SHIFT) & 0xF);
            final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[(int) ((queueValue >>> DIRECTION_SHIFT) & 63)];

            final boolean checkSourceFaces = this.resolveSourceFaces(queueValue, posX, posY, posZ, sectionOffset);

            for (final AxisDirection propagate : checkDirections) {
                if (checkSourceFaces && this.isSourceFaceSolid(propagate.ordinal())) continue;

                final int offX = posX + propagate.x;
                final int offY = posY + propagate.y;
                final int offZ = posZ + propagate.z;

                final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;

                if (this.nibbleCache[sectionIndex] == null) {
                    continue;
                }

                final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);
                final int currentLevel = this.getLightLevel(sectionIndex, localIndex);
                if (currentLevel == 0) {
                    continue;
                }

                final Block block = this.getBlockFast(sectionIndex, offX & 15, offY & 15, offZ & 15);
                final int destId = this.lastBlockId;
                final int absorption;
                if (block == Blocks.air) {
                    absorption = 1;
                } else {
                    final ExtendedBlockStorage section = this.sectionCache[sectionIndex];
                    final int destMeta = section != null ? section.getExtBlockMetadata(offX & 15, offY & 15, offZ & 15) : 0;
                    absorption = FaceOcclusion.resolveScalarAbsorption(destId, block, destMeta, propagate.oppositeOrdinal, offX, offY, offZ);
                }

                final int targetLevel = propagatedLevel - absorption;
                final long sFlag = sidedFlag(destId);

                if (currentLevel > targetLevel) {
                    // This block has another source -- re-propagate with RECHECK
                    if (increaseQueueLength >= increaseQueue.length) {
                        if (increaseQueue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        increaseQueue = this.resizeIncreaseQueue();
                    }
                    increaseQueue[increaseQueueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(currentLevel)
                            | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                            | FLAG_RECHECK_LEVEL
                            | sFlag;
                    continue;
                }

                if (this.setLightLevelInCache(sectionIndex, localIndex, 0)) this.postLightUpdate(sectionIndex, localIndex);

                final int emission = emissionAt(block, destId, offX, offY, offZ);
                if (emission > 0) {
                    if (increaseQueueLength >= increaseQueue.length) {
                        if (increaseQueue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        increaseQueue = this.resizeIncreaseQueue();
                    }
                    if (this.setLightLevelInCache(sectionIndex, localIndex, emission)) this.postLightUpdate(sectionIndex, localIndex);
                    increaseQueue[increaseQueueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(emission)
                            | (((long) ALL_DIRECTIONS_BITSET) << DIRECTION_SHIFT)
                            | FLAG_WRITE_LEVEL
                            | sFlag;
                } else if (currentLevel > 1) {
                    if (queueLength >= queue.length) {
                        if (queue.length >= MAX_QUEUE_SIZE) {
                            this.queueOverflowed = true;
                            continue;
                        }
                        queue = this.resizeDecreaseQueue();
                    }
                    queue[queueLength++] = encodeCoords(offX, offZ, offY, encodeOffset)
                            | this.encodeQueueLevel(currentLevel)
                            | (propagate.everythingButTheOppositeDirection << DIRECTION_SHIFT)
                            | sFlag;
                }
            }
        }

        this.lastBfsDecreaseTotal += queueLength;
        this.increaseQueueInitialLength = increaseQueueLength;
        this.performLightIncrease();
    }
}
