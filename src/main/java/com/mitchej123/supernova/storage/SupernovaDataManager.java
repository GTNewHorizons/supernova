package com.mitchej123.supernova.storage;

import com.falsepattern.chunk.api.DataRegistry;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;

/**
 * ChunkAPI DataManager for RGB block light nibble storage.
 */
public final class SupernovaDataManager extends AbstractSupernovaDataManager {

    private static final SupernovaDataManager INSTANCE = new SupernovaDataManager();

    private SupernovaDataManager() {
        super("rgb_blocklight", "Supernova RGB block light data will be lost. Vanilla block light will be recalculated.", false);
    }

    public static void register() {
        DataRegistry.registerDataManager(INSTANCE, 0);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesR(SupernovaChunk chunk) {
        return chunk.getBlockNibblesR();
    }

    @Override
    protected SWMRNibbleArray[] getNibblesG(SupernovaChunk chunk) {
        return chunk.getBlockNibblesG();
    }

    @Override
    protected SWMRNibbleArray[] getNibblesB(SupernovaChunk chunk) {
        return chunk.getBlockNibblesB();
    }

}
