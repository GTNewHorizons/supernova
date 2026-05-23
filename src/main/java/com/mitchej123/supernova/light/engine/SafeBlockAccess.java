package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.DimensionedBlockAccess;
import com.mitchej123.supernova.util.SnapshotChunkMap;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Thread-safe {@link IBlockAccess} that routes chunk lookups through Supernova's copy-on-write chunk map instead of {@code ChunkProviderServer}. This prevents
 * off-thread access to the chunk provider's internal data structures.
 * <p>
 * Any access to an unloaded chunk returns air/0/null -- no chunk loading is triggered.
 */
public final class SafeBlockAccess implements IBlockAccess, DimensionedBlockAccess {

    private final SnapshotChunkMap chunkMap;
    private final int dimensionId;

    public SafeBlockAccess(final SnapshotChunkMap chunkMap, final int dimensionId) {
        this.chunkMap = chunkMap;
        this.dimensionId = dimensionId;
    }

    @Override
    public int getDimensionId() {
        return this.dimensionId;
    }

    private Chunk getChunk(final int cx, final int cz) {
        return this.chunkMap.get(((long) cx << 32) | (cz & 0xFFFFFFFFL));
    }

    @Override
    public Block getBlock(final int x, final int y, final int z) {
        final Chunk chunk = this.getChunk(x >> 4, z >> 4);
        if (chunk == null) return Blocks.air;
        return chunk.getBlock(x & 15, y, z & 15);
    }

    @Override
    public int getBlockMetadata(final int x, final int y, final int z) {
        final Chunk chunk = this.getChunk(x >> 4, z >> 4);
        if (chunk == null) return 0;
        return chunk.getBlockMetadata(x & 15, y, z & 15);
    }

    @Override
    public TileEntity getTileEntity(final int x, final int y, final int z) {
        return null;
    }

    @Override
    public int getLightBrightnessForSkyBlocks(final int x, final int y, final int z, final int lightValue) {
        return lightValue;
    }

    @Override
    public int isBlockProvidingPowerTo(final int x, final int y, final int z, final int direction) {
        return 0;
    }

    @Override
    public boolean isAirBlock(final int x, final int y, final int z) {
        return this.getBlock(x, y, z) == Blocks.air;
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(final int x, final int z) {
        return BiomeGenBase.plains;
    }

    @Override
    public int getHeight() {
        return 256;
    }

    @Override
    public boolean extendedLevelsInChunkCache() {
        return false;
    }

    @Override
    public boolean isSideSolid(final int x, final int y, final int z, final ForgeDirection side, final boolean _default) {
        final Chunk chunk = this.getChunk(x >> 4, z >> 4);
        if (chunk == null) return _default;
        return this.getBlock(x, y, z).isSideSolid(this, x, y, z, side);
    }
}
