package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.PackedColorLight;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

final class BFSTestHelper {

    static final int RGB_DIR_SHIFT = 40;

    private BFSTestHelper() {}

    /** Center chunk (0,0), y=64. */
    static void setupCenter(RGBEngineAccess engine) {
        engine.callSetupEncodeOffset(7, 64, 7);
    }

    static <E extends RGBEngineAccess> E centeredWithAir(E engine) {
        setupCenter(engine);
        populateAirSection(engine, 0, 4, 0);
        return engine;
    }

    /** Chunks -1..1, sections 3..5: room for a full 15-level falloff. */
    static <E extends RGBEngineAccess> E centeredNeighborhood(E engine) {
        setupCenter(engine);
        populateNeighborhood(engine, -1, 1, -1, 1, 3, 5);
        return engine;
    }

    /** World chunk coords, not cache-relative. */
    static void populateAirSection(RGBEngineAccess engine, int chunkX, int sectionY, int chunkZ) {
        final int idx = sectionIndex(engine, chunkX, sectionY, chunkZ);
        engine.getSectionCache()[idx] = new TestSection(sectionY << 4, false);

        // Engine assumes nibbleCacheR aliases nibbleCache.
        final SWMRNibbleArray nibR = new SWMRNibbleArray();
        engine.getNibbleCache()[idx] = nibR;
        engine.getNibbleCacheR()[idx] = nibR;
        engine.getNibbleCacheG()[idx] = new SWMRNibbleArray();
        engine.getNibbleCacheB()[idx] = new SWMRNibbleArray();
    }

    static void populateNeighborhood(RGBEngineAccess engine, int cx0, int cx1, int cz0, int cz1, int sy0, int sy1) {
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (int sy = sy0; sy <= sy1; sy++) {
                    populateAirSection(engine, cx, sy, cz);
                }
            }
        }
    }

    static void setBlock(RGBEngineAccess engine, int worldX, int worldY, int worldZ, Block block) {
        final int idx = sectionIndex(engine, worldX >> 4, worldY >> 4, worldZ >> 4);
        final ExtendedBlockStorage section = engine.getSectionCache()[idx];
        if (section == null) {
            throw new IllegalStateException("Section not populated at chunk (" + (worldX >> 4) + ", " + (worldY >> 4) + ", " + (worldZ >> 4) + ")");
        }
        section.func_150818_a(worldX & 15, worldY & 15, worldZ & 15, block);
    }

    static void setLight(RGBEngineAccess engine, int worldX, int worldY, int worldZ, int r, int g, int b) {
        engine.setLightAt(worldX, worldY, worldZ, PackedColorLight.pack(r, g, b));
    }

    static int getLight(RGBEngineAccess engine, int worldX, int worldY, int worldZ) {
        return engine.getLightAt(worldX, worldY, worldZ);
    }

    /** Writes the level as well as enqueueing it. */
    static void enqueueIncrease(RGBEngineAccess engine, int worldX, int worldY, int worldZ, int r, int g, int b) {
        setLight(engine, worldX, worldY, worldZ, r, g, b);
        engine.enqueueIncrease(queueEntry(engine, worldX, worldY, worldZ, PackedColorLight.pack(r, g, b)));
    }

    static void enqueueDecrease(RGBEngineAccess engine, int worldX, int worldY, int worldZ, int r, int g, int b) {
        engine.enqueueDecrease(queueEntry(engine, worldX, worldY, worldZ, PackedColorLight.pack(r, g, b)));
    }

    private static long queueEntry(RGBEngineAccess engine, int worldX, int worldY, int worldZ, int packedRGB) {
        return SupernovaEngine.encodeCoords(worldX, worldZ, worldY, engine.getCoordinateOffset())
            | PackedColorLightQueue.encodeQueuePackedRGB(packedRGB)
            | (((long) SupernovaEngine.ALL_DIRECTIONS_BITSET) << RGB_DIR_SHIFT)
            | SupernovaEngine.sidedFlag(Block.getIdFromBlock(getBlockAt(engine, worldX, worldY, worldZ)));
    }

    private static Block getBlockAt(RGBEngineAccess engine, int worldX, int worldY, int worldZ) {
        final int idx = sectionIndex(engine, worldX >> 4, worldY >> 4, worldZ >> 4);
        final ExtendedBlockStorage section = engine.getSectionCache()[idx];
        if (section == null) return Blocks.air;
        return section.getBlockByExtId(worldX & 15, worldY & 15, worldZ & 15);
    }

    static int sectionIndex(RGBEngineAccess engine, int chunkX, int sectionY, int chunkZ) {
        return chunkX + 5 * chunkZ + (5 * 5) * sectionY + engine.getChunkSectionIndexOffset();
    }

    static int localIndex(int worldX, int worldY, int worldZ) {
        return (worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8);
    }
}
