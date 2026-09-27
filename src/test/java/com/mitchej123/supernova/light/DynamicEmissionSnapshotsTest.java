package com.mitchej123.supernova.light;

import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.PackedColorLight;
import com.mitchej123.supernova.light.engine.MCBootstrap;
import com.mitchej123.supernova.light.engine.SafeBlockAccess;
import com.mitchej123.supernova.light.engine.TestSection;
import com.mitchej123.supernova.util.SnapshotChunkMap;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicEmissionSnapshotsTest {

    public static class TileLamp extends Block {
        static int level;

        public TileLamp() {super(net.minecraft.block.material.Material.rock);}

        @Override
        public boolean hasTileEntity(final int meta) {return true;}

        @Override
        public int getLightValue(final IBlockAccess access, final int x, final int y, final int z) {
            if (!(access instanceof World)) throw new IllegalStateException("tile entities are unavailable to light workers");
            return level;
        }
    }

    @Test
    void mainThreadSnapshotsTrackTileEmissionWithoutWorkerTileAccess() {
        final World world = MCBootstrap.createStubWorld(true);
        final Block lamp = MCBootstrap.registerTestBlock(MCBootstrap.TestIds.TILE_LAMP, "tile_lamp", TileLamp.class, 0, false, 0);
        final Chunk chunk = new Chunk(world, 0, 0);
        final TestSection section = new TestSection(64, false);
        section.func_150818_a(1, 0, 1, lamp);
        chunk.getBlockStorageArray()[4] = section;
        final TileEntity tile = MCBootstrap.allocate(TileEntity.class);
        tile.xCoord = 1;
        tile.yCoord = 64;
        tile.zCoord = 1;
        chunk.chunkTileEntityMap.put(new ChunkPosition(1, 64, 1), tile);

        final WorldLightManager manager = new WorldLightManager(world, false, true);
        final DynamicEmissionSnapshots snapshots = new DynamicEmissionSnapshots(world);
        snapshots.setManager(manager);
        try {
            TileLamp.level = 6;
            snapshots.registerChunk(chunk);
            assertEquals(PackedColorLight.pack(6, 6, 6), snapshots.get(1, 64, 1));
            assertEquals(0, LightColorRegistry.getPackedEmission(new SafeBlockAccess(new SnapshotChunkMap()), lamp, 0, 1, 64, 1));

            TileLamp.level = 11;
            snapshots.tick();
            assertEquals(PackedColorLight.pack(11, 11, 11), snapshots.get(1, 64, 1));
            assertTrue(manager.hasChunkPendingLight(0, 0));

            chunk.chunkTileEntityMap.remove(new ChunkPosition(1, 64, 1));
            snapshots.tileEntityChanged(1, 64, 1);
            assertNull(snapshots.get(1, 64, 1));
        } finally {
            manager.shutdown();
        }
    }
}
