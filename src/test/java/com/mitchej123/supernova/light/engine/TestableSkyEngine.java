package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.util.SnapshotChunkMap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

class TestableSkyEngine extends SupernovaSkyEngine implements RGBEngineAccess {

    TestableSkyEngine(World world) {
        super(world, new SnapshotChunkMap());
    }

    @Override
    protected void markRenderSection(int originX, int originY, int originZ) {}

    @Override
    public SWMRNibbleArray[] getNibbleCacheR() {
        return this.nibbleCacheR;
    }

    @Override
    public SWMRNibbleArray[] getNibbleCacheG() {
        return this.nibbleCacheG;
    }

    @Override
    public SWMRNibbleArray[] getNibbleCacheB() {
        return this.nibbleCacheB;
    }

    @Override
    public ExtendedBlockStorage[] getSectionCache() {
        return this.sectionCache;
    }

    @Override
    public SWMRNibbleArray[] getNibbleCache() {
        return this.nibbleCache;
    }

    @Override
    public int getChunkSectionIndexOffset() {
        return this.chunkSectionIndexOffset;
    }

    @Override
    public int getCoordinateOffset() {
        return this.coordinateOffset;
    }

    @Override
    public void callSetupEncodeOffset(int centerX, int centerY, int centerZ) {
        this.setupEncodeOffset(centerX, centerY, centerZ);
    }

    @Override
    public void callPerformLightIncrease() {
        this.performLightIncrease();
    }

    @Override
    public void callPerformLightDecrease() {
        this.performLightDecrease();
    }

    @Override
    public void enqueueIncrease(long value) {
        this.appendToIncreaseQueue(value);
    }

    @Override
    public void enqueueDecrease(long value) {
        this.appendToDecreaseQueue(value);
    }

    @Override
    public int getLightAt(int worldX, int worldY, int worldZ) {
        return this.getLightLevel(worldX, worldY, worldZ);
    }

    @Override
    public void setLightAt(int worldX, int worldY, int worldZ, int packedRGB) {
        this.setLightLevel(worldX, worldY, worldZ, packedRGB);
    }
}
