package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.util.SnapshotChunkMap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.List;

class TestableBlockEngine extends SupernovaBlockEngine implements RGBEngineAccess {

    private final List<Integer> renderMarks = new ArrayList<>();

    TestableBlockEngine(World world) {
        super(world, new SnapshotChunkMap());
    }

    @Override
    protected void markRenderSection(int originX, int originY, int originZ) {
        this.renderMarks.add(originX);
        this.renderMarks.add(originY);
        this.renderMarks.add(originZ);
    }

    List<Integer> getRenderMarks() {
        return this.renderMarks;
    }

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

    boolean[] getNotifyUpdateCache() {
        return this.notifyUpdateCache;
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

    void callPropagateNeighbourLevels(Chunk chunk, int fromSection, int toSection) {
        this.propagateNeighbourLevels(chunk, fromSection, toSection);
    }

    void callCheckBlock(int worldX, int worldY, int worldZ) {
        this.checkBlock(worldX, worldY, worldZ);
    }

    void callLightChunk(Chunk chunk, boolean needsEdgeChecks) {
        this.lightChunk(chunk, needsEdgeChecks);
    }

    void putChunkInCache(int chunkX, int chunkZ, Chunk chunk) {
        this.setChunkInCache(chunkX, chunkZ, chunk);
    }

    void callUpdateVisible() {
        this.updateVisible();
    }

    int[] packSection(final int index) {
        this.packSectionToCache(index);
        return this.packedRGBCache[index];
    }

    void callCheckChunkEdge(final int chunkX, final int sectionY, final int chunkZ) {
        this.checkChunkEdge(chunkX, sectionY, chunkZ);
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
