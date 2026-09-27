package com.mitchej123.supernova.light;

import com.mitchej123.supernova.api.ExtendedSection;
import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

public final class ChunkLightHelper {

    /** The sync reads the other lane's nibbles while it may be publishing. */
    private static final ThreadLocal<byte[][]> SYNC_SCRATCH = ThreadLocal.withInitial(() -> new byte[3][SWMRNibbleArray.ARRAY_SIZE]);

    private ChunkLightHelper() {}

    public static boolean hasSavedBlockData(SWMRNibbleArray[] blockNibblesR, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < blockNibblesR.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) {
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
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;
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
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;
            final NibbleArray vanillaBlock = storageArrays[sectionY].getBlocklightArray();
            if (vanillaBlock == null) continue;
            blockR[i] = SWMRNibbleArray.fromVanilla(vanillaBlock);
            if (blockG != null) blockG[i] = SWMRNibbleArray.fromVanilla(vanillaBlock);
            if (blockB != null) blockB[i] = SWMRNibbleArray.fromVanilla(vanillaBlock);
        }
    }

    /** A new section is zero-filled and only dirtied nibbles are written back; an absent sky nibble means above-terrain daylight, not dark. */
    public static void seedVanillaSection(final ExtendedBlockStorage section, final SWMRNibbleArray[] skyR, final SWMRNibbleArray[] skyG,
        final SWMRNibbleArray[] skyB, final SWMRNibbleArray[] blockR, final SWMRNibbleArray[] blockG, final SWMRNibbleArray[] blockB, final int sectionY) {
        if (section == null) return;
        final int idx = sectionY - WorldUtil.getMinLightSection();

        synchronized (section) {
            final ExtendedSection ext = (ExtendedSection) section;
            final NibbleArray vanillaSky = section.getSkylightArray();
            if (vanillaSky != null) {
                final SWMRNibbleArray nib = (skyR == null || idx < 0 || idx >= skyR.length) ? null : skyR[idx];
                if (nibbleAbsent(nib)) {
                    Arrays.fill(vanillaSky.data, (byte) 0xFF);
                    ext.supernova$setLightNonTrivial(true, false);
                } else {
                    ext.supernova$setLightNonTrivial(true, syncSkyToVanillaSection(vanillaSky, nib, skyG, skyB, idx));
                }
            }

            final NibbleArray vanillaBlock = section.getBlocklightArray();
            if (vanillaBlock != null) {
                ext.supernova$setLightNonTrivial(false, syncBlockToVanillaSection(vanillaBlock, blockR, blockG, blockB, idx));
            }
        }
    }

    /** True when the result is anything but full daylight, i.e. the packet has to carry this section. */
    private static boolean syncSkyToVanillaSection(final NibbleArray vanilla, final SWMRNibbleArray rNib, final SWMRNibbleArray[] skyG,
        final SWMRNibbleArray[] skyB, final int idx) {
        final byte[][] scratch = SYNC_SCRATCH.get();
        final byte[] rData = channelSnapshot(rNib, scratch[0]);
        final byte[] gData = channelSnapshot(skyG, idx, scratch[1]);
        final byte[] bData = channelSnapshot(skyB, idx, scratch[2]);
        return maxIntoVanilla(vanilla, rData, gData, bData, 0, SWMRNibbleArray.ARRAY_SIZE - 1, 0xFF);
    }

    /** True when any block light was written, i.e. the packet has to carry this section. */
    private static boolean syncBlockToVanillaSection(final NibbleArray vanilla, final SWMRNibbleArray[] blockR, final SWMRNibbleArray[] blockG,
        final SWMRNibbleArray[] blockB, final int idx) {
        final byte[][] scratch = SYNC_SCRATCH.get();
        final byte[] rData = channelSnapshot(blockR, idx, scratch[0]);
        final byte[] gData = channelSnapshot(blockG, idx, scratch[1]);
        final byte[] bData = channelSnapshot(blockB, idx, scratch[2]);
        if (rData == null && gData == null && bData == null) {
            Arrays.fill(vanilla.data, (byte) 0);
            return false;
        }
        return maxIntoVanilla(vanilla, rData, gData, bData, 0, SWMRNibbleArray.ARRAY_SIZE - 1, 0);
    }

    private static byte[] channelSnapshot(final SWMRNibbleArray[] nibbles, final int idx, final byte[] dst) {
        if (nibbles == null || idx < 0 || idx >= nibbles.length || nibbles[idx] == null) return null;
        return channelSnapshot(nibbles[idx], dst);
    }

    private static byte[] channelSnapshot(final SWMRNibbleArray nib, final byte[] dst) {
        final int state = nib.snapshotVisible(dst, 0);
        if (state == SWMRNibbleArray.VISIBLE_FULL) Arrays.fill(dst, (byte) 0xFF);
        return state == SWMRNibbleArray.VISIBLE_DATA || state == SWMRNibbleArray.VISIBLE_FULL ? dst : null;
    }

    public static byte[] visibleData(final SWMRNibbleArray nib) {
        return nib == null ? null : nib.getVisibleData();
    }

    /** Returns whether the written range differs from the channel's trivial value. */
    public static boolean maxIntoVanilla(final NibbleArray vanilla, final byte[] r, final byte[] g, final byte[] b, final int minByte, final int maxByte,
        final int trivialByte) {
        final byte[] out = vanilla.data;
        int differing = 0;
        for (int i = minByte; i <= maxByte; ++i) {
            final byte value = maxOfChannels(r == null ? 0 : r[i], g == null ? 0 : g[i], b == null ? 0 : b[i]);
            out[i] = value;
            differing |= (value & 0xFF) ^ trivialByte;
        }
        return differing != 0;
    }

    private static byte maxOfChannels(final int r, final int g, final int b) {
        final int lo = Math.max(r & 0x0F, Math.max(g & 0x0F, b & 0x0F));
        final int hi = Math.max((r >> 4) & 0x0F, Math.max((g >> 4) & 0x0F, (b >> 4) & 0x0F));
        return (byte) ((hi << 4) | lo);
    }

    public static void syncSkyToVanilla(SWMRNibbleArray[] skyR, SWMRNibbleArray[] skyG, SWMRNibbleArray[] skyB, ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < skyR.length; ++i) {
            final SWMRNibbleArray rNib = skyR[i];
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;
            final NibbleArray vanilla = storageArrays[sectionY].getSkylightArray();
            if (vanilla == null) continue;
            final ExtendedBlockStorage section = storageArrays[sectionY];
            synchronized (section) {
                if (nibbleAbsent(rNib)) {
                    // Preserve provider-owned sky bytes, but keep packet section selection in sync with them.
                    boolean nonTrivial = false;
                    for (final byte value : vanilla.data) {
                        if ((value & 0xFF) != 0xFF) {
                            nonTrivial = true;
                            break;
                        }
                    }
                    ((ExtendedSection) section).supernova$setLightNonTrivial(true, nonTrivial);
                } else {
                    ((ExtendedSection) section).supernova$setLightNonTrivial(true, syncSkyToVanillaSection(vanilla, rNib, skyG, skyB, i));
                }
            }
        }
    }

    public static void syncBlockToVanilla(SWMRNibbleArray[] blockR, SWMRNibbleArray[] blockG, SWMRNibbleArray[] blockB,
        ExtendedBlockStorage[] storageArrays) {
        final int minLight = WorldUtil.getMinLightSection();
        for (int i = 0; i < blockR.length; ++i) {
            final int sectionY = i + minLight;
            if (sectionY < 0 || sectionY > 15 || storageArrays[sectionY] == null) continue;
            final NibbleArray vanilla = storageArrays[sectionY].getBlocklightArray();
            if (vanilla == null) continue;
            final ExtendedBlockStorage section = storageArrays[sectionY];
            synchronized (section) {
                ((ExtendedSection) section).supernova$setLightNonTrivial(false, syncBlockToVanillaSection(vanilla, blockR, blockG, blockB, i));
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
        final int r = readSkyChannel(skyR, idx, x, y, z, 15);
        final int g = readSkyChannel(skyG, idx, x, y, z, r);
        final int b = readSkyChannel(skyB, idx, x, y, z, r);
        return Math.max(r, Math.max(g, b));
    }

    /** An absent channel is not darkness: R carries the section state, so absent R means above-world (15) and absent G/B mirror R. */
    public static int readSkyChannel(SWMRNibbleArray[] nibbles, int idx, int x, int y, int z, int absent) {
        if (nibbles == null || idx < 0 || idx >= nibbles.length) return absent;
        final SWMRNibbleArray nib = nibbles[idx];
        return nibbleAbsent(nib) ? absent : nib.getVisible(x, y, z);
    }

    public static boolean nibbleAbsent(SWMRNibbleArray nib) {
        return nib == null || nib.isNullNibbleVisible();
    }
}
