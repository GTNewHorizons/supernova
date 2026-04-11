package com.mitchej123.supernova.light;

import com.mitchej123.supernova.compat.cubicchunks.CubicChunksHelper;
import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

public final class ChunkLightHelper {

    private ChunkLightHelper() {}

    public static boolean hasSavedBlockData(SWMRNibbleArray[] blockNibblesR, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < blockNibblesR.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY >= storageArrays.length || storageArrays[sectionY] == null) {
                continue;
            }
            if (!blockNibblesR[i].isNullNibbleVisible()) {
                return true;
            }
        }
        return false;
    }

    public static void importVanillaSky(SWMRNibbleArray[] skyR, SWMRNibbleArray[] skyG, SWMRNibbleArray[] skyB,
        ExtendedBlockStorage[] storageArrays, boolean onlyWhereNull) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < skyR.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY >= storageArrays.length || storageArrays[sectionY] == null) continue;
            final NibbleArray vanillaSky = storageArrays[sectionY].getSkylightArray();
            if (vanillaSky == null) continue;
            if (onlyWhereNull && !skyR[i].isNullNibbleVisible()) continue;
            skyR[i] = SWMRNibbleArray.fromVanilla(vanillaSky);
            if (skyG != null) skyG[i] = SWMRNibbleArray.fromVanilla(vanillaSky);
            if (skyB != null) skyB[i] = SWMRNibbleArray.fromVanilla(vanillaSky);
        }
    }

    public static void importVanillaBlock(SWMRNibbleArray[] blockR, SWMRNibbleArray[] blockG, SWMRNibbleArray[] blockB,
        ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < blockR.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY >= storageArrays.length || storageArrays[sectionY] == null) continue;
            final NibbleArray vanillaBlock = storageArrays[sectionY].getBlocklightArray();
            if (vanillaBlock == null) continue;
            blockR[i] = SWMRNibbleArray.fromVanilla(vanillaBlock);
            if (blockG != null) blockG[i] = SWMRNibbleArray.fromVanilla(vanillaBlock);
            if (blockB != null) blockB[i] = SWMRNibbleArray.fromVanilla(vanillaBlock);
        }
    }

    public static void syncSkyToVanilla(SWMRNibbleArray[] skyNibbles, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < skyNibbles.length; ++i) {
            final SWMRNibbleArray skyNib = skyNibbles[i];
            if (skyNib == null) continue;

            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY >= storageArrays.length || storageArrays[sectionY] == null) continue;

            final NibbleArray vanilla = storageArrays[sectionY].getSkylightArray();
            if (vanilla == null) continue;

            final byte[] data = skyNib.getVisibleData();
            if (data != null) {
                System.arraycopy(data, 0, vanilla.data, 0, SWMRNibbleArray.ARRAY_SIZE);
            } else {
                Arrays.fill(vanilla.data, (byte) 0xFF);
            }
        }
    }

    public static void syncBlockToVanilla(SWMRNibbleArray[] blockR, SWMRNibbleArray[] blockG, SWMRNibbleArray[] blockB,
        ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < blockR.length; ++i) {
            final SWMRNibbleArray rNib = blockR[i];
            if (rNib == null) continue;

            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY >= storageArrays.length || storageArrays[sectionY] == null) continue;

            final NibbleArray vanilla = storageArrays[sectionY].getBlocklightArray();
            if (vanilla == null) continue;

            final byte[] rData = rNib.getVisibleData();
            final byte[] gData = blockG != null && blockG[i] != null ? blockG[i].getVisibleData() : null;
            final byte[] bData = blockB != null && blockB[i] != null ? blockB[i].getVisibleData() : null;

            if (rData == null && gData == null && bData == null) {
                Arrays.fill(vanilla.data, (byte) 0);
                continue;
            }

            for (int j = 0; j < SWMRNibbleArray.ARRAY_SIZE; ++j) {
                final int r = rData != null ? (rData[j] & 0xFF) : 0;
                final int g = gData != null ? (gData[j] & 0xFF) : 0;
                final int b = bData != null ? (bData[j] & 0xFF) : 0;
                final int lo = Math.max(Math.max(r & 0x0F, g & 0x0F), b & 0x0F);
                final int hi = Math.max(Math.max((r >> 4) & 0x0F, (g >> 4) & 0x0F), (b >> 4) & 0x0F);
                vanilla.data[j] = (byte) ((hi << 4) | lo);
            }
        }
    }

    public static int getBlockLight(SWMRNibbleArray[] blockR, SWMRNibbleArray[] blockG, SWMRNibbleArray[] blockB, int x, int y, int z) {
        final int sectionY = y >> 4;
        final int minLightSection = WorldUtil.getMinLightSection();
        final int maxLightSection = WorldUtil.getMaxLightSection();

        if (sectionY > maxLightSection || sectionY < minLightSection) {
            return 0;
        }

        final int idx = sectionY - minLightSection;
        final int localIndex = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);

        if (blockG == null) {
            if (blockR != null) {
                final SWMRNibbleArray nib = blockR[idx];
                if (nib != null) return nib.getVisible(localIndex);
            }
            return 0;
        }

        int r = 0, g = 0, b = 0;
        if (blockR != null) {
            final SWMRNibbleArray nibR = blockR[idx];
            if (nibR != null) r = nibR.getVisible(localIndex);
        }
        final SWMRNibbleArray nibG = blockG[idx];
        if (nibG != null) g = nibG.getVisible(localIndex);
        if (blockB != null) {
            final SWMRNibbleArray nibB = blockB[idx];
            if (nibB != null) b = nibB.getVisible(localIndex);
        }
        return Math.max(r, Math.max(g, b));
    }

    public static int getSkyLight(SWMRNibbleArray[] skyR, SWMRNibbleArray[] skyG, SWMRNibbleArray[] skyB, int x, int y, int z) {
        final int sectionY = y >> 4;
        final int minLightSection = WorldUtil.getMinLightSection();
        final int maxLightSection = WorldUtil.getMaxLightSection();

        if (sectionY > maxLightSection) {
            return 15;
        }
        if (sectionY < minLightSection) {
            return 0;
        }

        if (skyR == null) {
            return 15;
        }

        final int idx = sectionY - minLightSection;
        final SWMRNibbleArray nibR = skyR[idx];
        if (nibR == null || nibR.isNullNibbleVisible() || nibR.isUninitialisedVisible()) {
            return 15;
        }
        final int r = nibR.getVisible(x, y, z);

        if (skyG == null) {
            return r;
        }

        int g = r, b = r;
        final SWMRNibbleArray nibG = skyG[idx];
        if (nibG != null && !nibG.isNullNibbleVisible()) g = nibG.getVisible(x, y, z);
        if (skyB != null) {
            final SWMRNibbleArray nibB = skyB[idx];
            if (nibB != null && !nibB.isNullNibbleVisible()) b = nibB.getVisible(x, y, z);
        }
        return Math.max(r, Math.max(g, b));
    }
}
