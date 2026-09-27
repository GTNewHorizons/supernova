package com.mitchej123.supernova.storage;

import com.falsepattern.chunk.api.DataRegistry;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;

/**
 * ChunkAPI DataManager for RGB sky light nibble storage.
 */
public final class SupernovaSkyDataManager extends AbstractSupernovaDataManager {

    private static final SupernovaSkyDataManager INSTANCE = new SupernovaSkyDataManager();

    private SupernovaSkyDataManager() {
        super("rgb_skylight", "Supernova RGB sky light data will be lost. Vanilla sky light will be recalculated.", true);
    }

    public static void register() {
        DataRegistry.registerDataManager(INSTANCE, 0);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesR(SupernovaChunk chunk) {
        return chunk.getSkyNibblesR();
    }

    @Override
    protected SWMRNibbleArray[] getNibblesG(SupernovaChunk chunk) {
        return chunk.getSkyNibblesG();
    }

    @Override
    protected SWMRNibbleArray[] getNibblesB(SupernovaChunk chunk) {
        return chunk.getSkyNibblesB();
    }
}
