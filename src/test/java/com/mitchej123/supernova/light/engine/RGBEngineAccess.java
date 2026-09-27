package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.light.SWMRNibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

interface RGBEngineAccess {

    SWMRNibbleArray[] getNibbleCacheR();

    SWMRNibbleArray[] getNibbleCacheG();

    SWMRNibbleArray[] getNibbleCacheB();

    SWMRNibbleArray[] getNibbleCache();

    ExtendedBlockStorage[] getSectionCache();

    int getChunkSectionIndexOffset();

    int getCoordinateOffset();

    void callSetupEncodeOffset(int centerX, int centerY, int centerZ);

    void callPerformLightIncrease();

    void callPerformLightDecrease();

    void enqueueIncrease(long value);

    void enqueueDecrease(long value);

    int getLightAt(int worldX, int worldY, int worldZ);

    void setLightAt(int worldX, int worldY, int worldZ, int packedRGB);
}
