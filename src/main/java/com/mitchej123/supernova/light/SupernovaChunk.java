package com.mitchej123.supernova.light;

import com.mitchej123.supernova.api.ExtendedChunk;

/**
 * Internal extension of {@link ExtendedChunk} exposing nibble storage. Mixed into {@code net.minecraft.world.chunk.Chunk}.
 */
public interface SupernovaChunk extends ExtendedChunk {

    /** Copies SWMR visible data into the vanilla nibbles so chunk packets carry correct values. */
    void syncLightToVanilla();
    void setLightReady(boolean ready);
    void setLightUsable(boolean usable);

    /** Light changed since the last successful save. Owned by Supernova because vanilla's isModified is read-then-cleared by the save loop. */
    void markLightDirty(boolean sky);

    void clearLightDirty(boolean sky);

    static void markLightDirty(final net.minecraft.world.chunk.Chunk chunk, final boolean sky) {
        if (chunk instanceof SupernovaChunk) {
            ((SupernovaChunk) chunk).markLightDirty(sky);
        } else {
            chunk.setChunkModified();
        }
    }

    static void markLightDirty(final net.minecraft.world.chunk.Chunk chunk) {
        markLightDirty(chunk, false);
        markLightDirty(chunk, true);
    }

    // Sky light
    SWMRNibbleArray[] getSkyNibbles();
    void setSkyNibbles(SWMRNibbleArray[] nibbles);

    // RGB sky light -- R aliases getSkyNibbles()
    SWMRNibbleArray[] getSkyNibblesR();
    SWMRNibbleArray[] getSkyNibblesG();
    SWMRNibbleArray[] getSkyNibblesB();

    void setSkyNibblesG(SWMRNibbleArray[] nibbles);
    void setSkyNibblesB(SWMRNibbleArray[] nibbles);

    boolean[] getSkyEmptinessMap();
    void setSkyEmptinessMap(boolean[] emptinessMap);

    // RGB block light -- one nibble array per channel per section
    SWMRNibbleArray[] getBlockNibblesR();
    SWMRNibbleArray[] getBlockNibblesG();
    SWMRNibbleArray[] getBlockNibblesB();

    void setBlockNibblesR(SWMRNibbleArray[] nibbles);
    void setBlockNibblesG(SWMRNibbleArray[] nibbles);
    void setBlockNibblesB(SWMRNibbleArray[] nibbles);

    boolean[] getBlockEmptinessMap();
    void setBlockEmptinessMap(boolean[] emptinessMap);
}
