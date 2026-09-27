package com.mitchej123.supernova.compat.angelica;

import com.gtnewhorizons.angelica.api.BlockLightProvider;
import com.gtnewhorizons.angelica.api.SectionLightData;
import com.gtnewhorizons.angelica.rendering.celeritas.world.WorldSlice;
import com.mitchej123.supernova.light.ChunkLightHelper;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

public class SupernovaBlockLightProvider implements BlockLightProvider {

    @Override
    public SectionLightData prepareSectionData(Chunk chunk, int sectionY) {
        final int minLight = WorldUtil.getMinLightSection();
        final int maxLight = WorldUtil.getMaxLightSection();
        if (sectionY < minLight || sectionY > maxLight) return null;

        final SupernovaChunk ext = (SupernovaChunk) chunk;
        final int idx = sectionY - minLight;

        final SWMRNibbleArray r = getChannel(ext.getBlockNibblesR(), idx);
        final SWMRNibbleArray g = getChannel(ext.getBlockNibblesG(), idx);
        final SWMRNibbleArray b = getChannel(ext.getBlockNibblesB(), idx);

        final SWMRNibbleArray skyR = getChannel(ext.getSkyNibblesR(), idx);
        final SWMRNibbleArray skyG = getChannel(ext.getSkyNibblesG(), idx);
        final SWMRNibbleArray skyB = getChannel(ext.getSkyNibblesB(), idx);

        final boolean hasSky = chunk.worldObj != null && !chunk.worldObj.provider.hasNoSky;
        if (r == null && g == null && b == null && skyR == null && skyG == null && skyB == null) {
            // An absent sky nibble is full daylight, not "no data": sections above the terrain carry no ExtendedBlockStorage and are never sent.
            return hasSky ? SupernovaSectionLightData.ZERO_BLOCK_FULL_SKY : null;
        }
        return new SupernovaSectionLightData(r, g, b, skyR, skyG, skyB, hasSky);
    }

    @Override
    public int getBlockLightRGB(IBlockAccess blockAccess, int x, int y, int z) {
        if (blockAccess instanceof WorldSlice ws) {
            final SectionLightData data = ws.getSectionLightData(x, y, z);
            if (data == null) return -1;
            return data.getRGB(x & 15, y & 15, z & 15);
        }

        // Main-thread fallback: no WorldSlice off the render path.
        final SupernovaChunk ext = resolveChunk(blockAccess, x, z);
        if (ext == null) return -1;
        final int idx = sectionIndex(y);
        if (idx < 0) return -1;

        final int r = readNibble(ext.getBlockNibblesR(), idx, x, y, z);
        final int g = readNibble(ext.getBlockNibblesG(), idx, x, y, z);
        final int b = readNibble(ext.getBlockNibblesB(), idx, x, y, z);

        return (r << 8) | (g << 4) | b;
    }

    @Override
    public int getSkyLightRGB(IBlockAccess blockAccess, int x, int y, int z) {
        if (blockAccess instanceof WorldSlice ws) {
            final SectionLightData data = ws.getSectionLightData(x, y, z);
            if (data == null) return -1;
            return data.getSkyRGB(x & 15, y & 15, z & 15);
        }

        final SupernovaChunk ext = resolveChunk(blockAccess, x, z);
        if (ext == null) return -1;
        final int idx = sectionIndex(y);
        if (idx < 0) return -1;

        final int r = ChunkLightHelper.readSkyChannel(ext.getSkyNibblesR(), idx, x, y, z, 15);
        final int g = ChunkLightHelper.readSkyChannel(ext.getSkyNibblesG(), idx, x, y, z, r);
        final int b = ChunkLightHelper.readSkyChannel(ext.getSkyNibblesB(), idx, x, y, z, r);

        return (r << 8) | (g << 4) | b;
    }

    private static SupernovaChunk resolveChunk(IBlockAccess blockAccess, int x, int z) {
        if (!(blockAccess instanceof World world)) return null;
        final int cx = x >> 4;
        final int cz = z >> 4;
        if (!world.getChunkProvider().chunkExists(cx, cz)) return null;
        final Chunk chunk = world.getChunkProvider().provideChunk(cx, cz);
        return chunk == null ? null : (SupernovaChunk) chunk;
    }

    private static int sectionIndex(int y) {
        final int sectionY = y >> 4;
        final int minLight = WorldUtil.getMinLightSection();
        final int maxLight = WorldUtil.getMaxLightSection();
        return (sectionY < minLight || sectionY > maxLight) ? -1 : sectionY - minLight;
    }

    private static SWMRNibbleArray getChannel(SWMRNibbleArray[] nibbles, int idx) {
        if (nibbles == null || idx < 0 || idx >= nibbles.length) return null;
        final SWMRNibbleArray nib = nibbles[idx];
        return ChunkLightHelper.nibbleAbsent(nib) ? null : nib;
    }

    private static int readNibble(SWMRNibbleArray[] nibbles, int idx, int x, int y, int z) {
        final SWMRNibbleArray nib = getChannel(nibbles, idx);
        return nib == null ? 0 : nib.getVisible(x, y, z);
    }
}
