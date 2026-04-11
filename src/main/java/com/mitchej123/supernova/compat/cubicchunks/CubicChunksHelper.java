package com.mitchej123.supernova.compat.cubicchunks;

import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public class CubicChunksHelper {
    public static ExtendedBlockStorage getBlockStorageArray(final Chunk chunk, int sectionY) {
        if (CubicChunksCompat.isLoaded) {
            return CubicChunksCompat.getBlockStorageArray(chunk, sectionY);
        } else {
            return chunk.getBlockStorageArray()[sectionY];
        }
    }

    public static ExtendedBlockStorage[] getBlockStorageArrays(final Chunk chunk) {
        if (CubicChunksCompat.isLoaded) {
            final int minSection = WorldUtil.getMinSection();
            final int maxSection = WorldUtil.getMaxSection();
            final ExtendedBlockStorage[] sections = new ExtendedBlockStorage[maxSection - minSection + 1];
            for (int sectionY = minSection; sectionY <= maxSection; ++sectionY) {
                sections[sectionY - minSection] = CubicChunksCompat.getBlockStorageArray(chunk, sectionY);
            }
            return sections;
        } else {
            return chunk.getBlockStorageArray();
        }
    }
}
