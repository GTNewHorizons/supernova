package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.util.CoordinateUtils;
import com.mitchej123.supernova.util.SnapshotChunkMap;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** cx != cz so a transposed chunk key misses. */
class SafeBlockAccessTest {

    private static final int CHUNK_X = 3;
    private static final int CHUNK_Z = -7;
    private static final int SECTION_Y = 4;

    private static final int WORLD_X = CHUNK_X * 16 + 5;
    private static final int WORLD_Y = SECTION_Y * 16 + 3;
    private static final int WORLD_Z = CHUNK_Z * 16 + 11;

    private static final int META = 9;

    private static Block probe;

    private Chunk chunk;

    @BeforeAll
    static void bootstrap() {
        probe = MCBootstrap.registerTestBlock(MCBootstrap.TestIds.SAFE_ACCESS_PROBE, "safe_access_probe", Block.class, 255, true, 0);
    }

    @BeforeEach
    void setup() {
        chunk = new Chunk(MCBootstrap.getServerWorld(), CHUNK_X, CHUNK_Z);
        final ExtendedBlockStorage section = new TestSection(SECTION_Y << 4, false);
        section.func_150818_a(WORLD_X & 15, WORLD_Y & 15, WORLD_Z & 15, probe);
        section.setExtBlockMetadata(WORLD_X & 15, WORLD_Y & 15, WORLD_Z & 15, META);
        chunk.getBlockStorageArray()[SECTION_Y] = section;
    }

    private SafeBlockAccess accessWithLoadedChunk() {
        final SnapshotChunkMap map = new SnapshotChunkMap();
        map.put(CoordinateUtils.getChunkKey(CHUNK_X, CHUNK_Z), chunk);
        return new SafeBlockAccess(map);
    }

    @Test
    void readsBlockAndMetadataFromLoadedChunk() {
        final SafeBlockAccess access = accessWithLoadedChunk();

        assertSame(probe, access.getBlock(WORLD_X, WORLD_Y, WORLD_Z), "block in loaded chunk");
        assertEquals(META, access.getBlockMetadata(WORLD_X, WORLD_Y, WORLD_Z), "metadata in loaded chunk");
        assertFalse(access.isAirBlock(WORLD_X, WORLD_Y, WORLD_Z), "occupied position is not air");
    }

    @Test
    void unloadedChunkReadsAsAir() {
        final SafeBlockAccess access = accessWithLoadedChunk();

        assertSame(Blocks.air, access.getBlock(8, WORLD_Y, 8), "unloaded chunk block");
        assertEquals(0, access.getBlockMetadata(8, WORLD_Y, 8), "unloaded chunk metadata");
        assertTrue(access.isAirBlock(8, WORLD_Y, 8), "unloaded chunk is air");
    }
}
