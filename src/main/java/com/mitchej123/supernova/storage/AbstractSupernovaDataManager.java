package com.mitchej123.supernova.storage;

import com.falsepattern.chunk.api.DataManager;
import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.config.SupernovaConfig;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.light.WorldLightManager;
import com.mitchej123.supernova.world.SupernovaWorld;
import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Shared base for block/sky RGB data managers.
 * Handles NBT persistence and packet synchronization for RGB nibble arrays.
 * Supports scalar↔RGB conversion when the save format doesn't match the running mode.
 * A missing or stale version marker forces relighting. Chunk-level storage avoids empty per-section compounds.
 */
abstract class AbstractSupernovaDataManager
    implements DataManager.ChunkDataManager, DataManager.PacketDataManager, DataManager.CubicPacketDataManager {

    /** Bumped whenever the on-disk layout changes; a chunk only loads its light when its stored marker matches. */
    static final int LIGHT_VERSION = 2;

    static final String NBT_VERSION = "v";
    static final String NBT_SCALAR = "sc";
    /** Slot indices are relative to this; persisting it lets a bounds change discard the light rather than shift it vertically. */
    static final String NBT_MIN_SECTION = "ms";

    private static final String[] STATE_KEYS = { "sR", "sG", "sB" };
    private static final String[] SECTION_KEY_PREFIX = { "R", "G", "B" };

    private static final int CHANNEL_R = 0;
    private static final int CHANNEL_G = 1;
    private static final int CHANNEL_B = 2;

    // Flag byte + up to 3 * 2048 bytes per section, 16 sections
    private static final int MAX_PACKET_BYTES = 16 * (1 + 3 * SWMRNibbleArray.ARRAY_SIZE);
    private static final int MAX_PACKET_BYTES_CUBIC = 1 + 3 * SWMRNibbleArray.ARRAY_SIZE;

    private static final byte FLAG_R = 1;
    private static final byte FLAG_G = 2;
    private static final byte FLAG_B = 4;

    // Dark channels carry no nibble array
    private static final byte FLAG_R_DARK = 8;
    private static final byte FLAG_G_DARK = 16;
    private static final byte FLAG_B_DARK = 32;

    private static final ThreadLocal<byte[]> PACKET_SCRATCH = ThreadLocal.withInitial(() -> new byte[SWMRNibbleArray.ARRAY_SIZE]);

    private final String idStr;
    private final String uninstallMsg;
    private final boolean sky;

    protected AbstractSupernovaDataManager(final String id, final String uninstallMessage, final boolean sky) {
        this.idStr = id;
        this.uninstallMsg = uninstallMessage;
        this.sky = sky;
    }

    protected abstract SWMRNibbleArray[] getNibblesR(SupernovaChunk chunk);

    protected abstract SWMRNibbleArray[] getNibblesG(SupernovaChunk chunk);

    protected abstract SWMRNibbleArray[] getNibblesB(SupernovaChunk chunk);

    @Override
    public String domain() {
        return Supernova.MODID;
    }

    @Override
    public String id() {
        return idStr;
    }

    @Override
    public String version() {
        return Integer.toString(LIGHT_VERSION);
    }

    @Override
    public String newInstallDescription() {
        return null;
    }

    @Override
    public String uninstallMessage() {
        return uninstallMsg;
    }

    /** ChunkAPI treats any non-null return as an incompatible change, so an unchanged version must answer null or every world load raises a startup query. */
    @Override
    public String versionChangeMessage(String priorVersion) {
        if (version().equals(priorVersion)) return null;
        return "Supernova light storage changed from format " + priorVersion + " to " + LIGHT_VERSION
            + ". Existing light is discarded and recalculated on load; no blocks are affected.";
    }

    @Override
    public void writeChunkToNBT(Chunk chunk, NBTTagCompound nbt) {
        final SupernovaChunk ext = (SupernovaChunk) chunk;
        // Cleared first so a worker's mark landing during the write survives.
        ext.clearLightDirty(sky);
        final boolean written = writeChunkLight(nbt, getNibblesR(ext), getNibblesG(ext), getNibblesB(ext), ext.isLightReady() && !hasUnsettledLightValues(chunk));
        if (!written) ext.markLightDirty(sky);
    }

    /** Pending light may still reflect the previous blocks; saving it would persist stale sources. */
    private static boolean hasUnsettledLightValues(final Chunk chunk) {
        if (chunk.worldObj == null) return false;
        final WorldLightManager manager = ((SupernovaWorld) chunk.worldObj).supernova$lightManagerIfPresent();
        return manager != null && manager.hasUnsettledLightValues(chunk.xPosition, chunk.zPosition);
    }

    /** True when the marker was written, i.e. the chunk's light is now on the tag. */
    boolean writeChunkLight(NBTTagCompound nbt, SWMRNibbleArray[] rNibbles, SWMRNibbleArray[] gNibbles, SWMRNibbleArray[] bNibbles, boolean lightReady) {
        // No marker while lighting is in flight, so a partial chunk relights on load rather than loading half-lit.
        if (!lightReady) return false;
        try {
            final boolean scalar = SupernovaConfig.isScalarMode();
            if (scalar) nbt.setBoolean(NBT_SCALAR, true);
            nbt.setInteger(NBT_MIN_SECTION, WorldUtil.getMinLightSection());
            writeChannel(nbt, CHANNEL_R, rNibbles);
            if (!scalar) {
                writeChannel(nbt, CHANNEL_G, gNibbles);
                writeChannel(nbt, CHANNEL_B, bNibbles);
            }
            // Last: a throw above leaves no marker, so the chunk relights rather than trusting a half-written tag.
            nbt.setInteger(NBT_VERSION, LIGHT_VERSION);
            return true;
        } catch (final Exception t) {
            Supernova.LOG.error("Failed to write Supernova light data; the chunk will relight on load", t);
            return false;
        }
    }

    @Override
    public void readChunkFromNBT(Chunk chunk, NBTTagCompound nbt) {
        if (nbt == null) return;
        final SupernovaChunk ext = (SupernovaChunk) chunk;
        readChunkLight(nbt, getNibblesR(ext), getNibblesG(ext), getNibblesB(ext));
    }

    /** False leaves the nibbles untouched, so the chunk relights from scratch. */
    boolean readChunkLight(NBTTagCompound nbt, SWMRNibbleArray[] rNibbles, SWMRNibbleArray[] gNibbles, SWMRNibbleArray[] bNibbles) {
        if (nbt.getInteger(NBT_VERSION) != LIGHT_VERSION) return false;
        if (nbt.getInteger(NBT_MIN_SECTION) != WorldUtil.getMinLightSection()) return false;
        try {
            final boolean savedScalar = nbt.getBoolean(NBT_SCALAR);
            if (SupernovaConfig.isScalarMode()) {
                if (savedScalar) {
                    readChannel(nbt, CHANNEL_R, rNibbles);
                } else {
                    readCollapsedToScalar(nbt, rNibbles);
                }
            } else if (savedScalar) {
                readChannel(nbt, CHANNEL_R, rNibbles);
                mirrorChannel(rNibbles, gNibbles);
                mirrorChannel(rNibbles, bNibbles);
            } else {
                readChannel(nbt, CHANNEL_R, rNibbles);
                readChannel(nbt, CHANNEL_G, gNibbles);
                readChannel(nbt, CHANNEL_B, bNibbles);
            }
            return true;
        } catch (final Exception t) {
            Supernova.LOG.error("Corrupt Supernova light data; discarding it so the chunk relights", t);
            discard(rNibbles);
            discard(gNibbles);
            discard(bNibbles);
            return false;
        }
    }

    private static void writeChannel(NBTTagCompound nbt, int channel, SWMRNibbleArray[] nibbles) {
        if (nibbles == null) return;
        final int count = nibbles.length;
        byte[] states = null;
        for (int i = 0; i < count; ++i) {
            final SWMRNibbleArray nib = nibbles[i];
            if (nib == null) continue;
            final SWMRNibbleArray.SaveState state = nib.getSaveState();
            if (state == null) continue;
            if (states == null) states = new byte[count];
            states[i] = (byte) state.state;
            if (state.data != null) {
                nbt.setByteArray(SECTION_KEY_PREFIX[channel] + i, state.data);
            }
        }
        if (states != null) nbt.setByteArray(STATE_KEYS[channel], states);
    }

    private static void readChannel(NBTTagCompound nbt, int channel, SWMRNibbleArray[] nibbles) {
        if (nibbles == null) return;
        final byte[] states = nbt.getByteArray(STATE_KEYS[channel]);
        final int count = Math.min(states.length, nibbles.length);
        for (int i = 0; i < count; ++i) {
            final int state = states[i];
            if (state == 0) continue;
            final byte[] data = sectionData(nbt, channel, i);
            // Must copy: AnvilChunkLoader.loadChunk__Async hands back the live pending-save tag when a chunk reloads before its save flushes.
            nibbles[i] = data != null ? new SWMRNibbleArray(data.clone(), state) : new SWMRNibbleArray();
        }
    }

    private static void readCollapsedToScalar(NBTTagCompound nbt, SWMRNibbleArray[] rNibbles) {
        if (rNibbles == null) return;
        final byte[] rStates = nbt.getByteArray(STATE_KEYS[CHANNEL_R]);
        final byte[] gStates = nbt.getByteArray(STATE_KEYS[CHANNEL_G]);
        final byte[] bStates = nbt.getByteArray(STATE_KEYS[CHANNEL_B]);
        final int count = rNibbles.length;
        for (int i = 0; i < count; ++i) {
            if ((stateAt(rStates, i) | stateAt(gStates, i) | stateAt(bStates, i)) == 0) continue;
            final byte[] rData = sectionData(nbt, CHANNEL_R, i);
            final byte[] gData = sectionData(nbt, CHANNEL_G, i);
            final byte[] bData = sectionData(nbt, CHANNEL_B, i);
            if (rData == null && gData == null && bData == null) {
                rNibbles[i] = new SWMRNibbleArray();
                continue;
            }
            final byte[] base = rData != null ? rData.clone() : new byte[SWMRNibbleArray.ARRAY_SIZE];
            collapseMax(base, gData, bData);
            rNibbles[i] = new SWMRNibbleArray(base);
        }
    }

    private static int stateAt(byte[] states, int idx) {
        return idx < states.length ? states[idx] : 0;
    }

    private static byte[] sectionData(NBTTagCompound nbt, int channel, int idx) {
        final byte[] data = nbt.getByteArray(SECTION_KEY_PREFIX[channel] + idx);
        return data.length == 0 ? null : data;
    }

    private static void mirrorChannel(SWMRNibbleArray[] src, SWMRNibbleArray[] dest) {
        if (src == null || dest == null) return;
        final int count = Math.min(src.length, dest.length);
        for (int i = 0; i < count; ++i) {
            cloneNibbleAt(src, dest, i);
        }
    }

    private static void discard(SWMRNibbleArray[] nibbles) {
        if (nibbles == null) return;
        for (int i = 0; i < nibbles.length; ++i) {
            nibbles[i] = new SWMRNibbleArray(null, true);
        }
    }

    /** Readiness is deliberately not copied: the clone's neighbors are not this chunk's, so its seams have to be reconciled again. */
    @Override
    public void cloneChunk(Chunk from, Chunk to) {
        final SupernovaChunk src = (SupernovaChunk) from;
        final SupernovaChunk dst = (SupernovaChunk) to;
        mirrorChannel(getNibblesR(src), getNibblesR(dst));
        mirrorChannel(getNibblesG(src), getNibblesG(dst));
        mirrorChannel(getNibblesB(src), getNibblesB(dst));
    }

    @Override
    public int maxPacketSize() {
        return MAX_PACKET_BYTES;
    }

    @Override
    public void writeToBuffer(Chunk chunk, int subChunkMask, boolean forceUpdate, ByteBuffer buffer) {
        final SupernovaChunk ext = (SupernovaChunk) chunk;
        for (int sectionY = 0; sectionY < 16; ++sectionY) {
            if ((subChunkMask & (1 << sectionY)) == 0) continue;
            writeSectionToBuffer(buffer, getNibblesR(ext), getNibblesG(ext), getNibblesB(ext), sectionY);
        }
    }

    @Override
    public void readFromBuffer(Chunk chunk, int subChunkMask, boolean forceUpdate, ByteBuffer buffer) {
        final SupernovaChunk ext = (SupernovaChunk) chunk;
        for (int sectionY = 0; sectionY < 16; ++sectionY) {
            if ((subChunkMask & (1 << sectionY)) == 0) continue;
            readSectionFromBuffer(buffer, getNibblesR(ext), getNibblesG(ext), getNibblesB(ext), sectionY);
        }
    }

    @Override
    public int maxPacketSizeCubic() {
        return MAX_PACKET_BYTES_CUBIC;
    }

    @Override
    public void writeToBuffer(Chunk chunk, ExtendedBlockStorage blockStorage, ByteBuffer buffer) {
        final int sectionY = blockStorage.getYLocation() >> 4;
        final SupernovaChunk ext = (SupernovaChunk) chunk;
        writeSectionToBuffer(buffer, getNibblesR(ext), getNibblesG(ext), getNibblesB(ext), sectionY);
    }

    @Override
    public void readFromBuffer(Chunk chunk, ExtendedBlockStorage blockStorage, ByteBuffer buffer) {
        final int sectionY = blockStorage.getYLocation() >> 4;
        final SupernovaChunk ext = (SupernovaChunk) chunk;
        readSectionFromBuffer(buffer, getNibblesR(ext), getNibblesG(ext), getNibblesB(ext), sectionY);
    }

    static int nibbleIndex(int sectionY) {
        return sectionY - WorldUtil.getMinLightSection();
    }

    private static void cloneNibbleChannel(SWMRNibbleArray[] src, SWMRNibbleArray[] dest, int sectionY) {
        if (src == null || dest == null) return;
        final int idx = nibbleIndex(sectionY);
        if (idx < 0 || idx >= src.length || idx >= dest.length) return;
        cloneNibbleAt(src, dest, idx);
    }

    private static void cloneNibbleAt(SWMRNibbleArray[] src, SWMRNibbleArray[] dest, int idx) {
        final SWMRNibbleArray srcNib = src[idx];
        if (srcNib == null) return;
        final byte[] bytes = new byte[SWMRNibbleArray.ARRAY_SIZE];
        final int state = srcNib.snapshotVisible(bytes, 0);
        if (state == SWMRNibbleArray.VISIBLE_ABSENT) return;
        if (state == SWMRNibbleArray.VISIBLE_FULL) Arrays.fill(bytes, (byte) 0xFF);
        dest[idx] = srcNib.isUninitialisedVisible() ? new SWMRNibbleArray() : new SWMRNibbleArray(bytes);
    }

    static void writeSectionToBuffer(ByteBuffer buffer, SWMRNibbleArray[] rNibbles, SWMRNibbleArray[] gNibbles, SWMRNibbleArray[] bNibbles, int sectionY) {
        final SWMRNibbleArray rNib = nibbleAt(rNibbles, sectionY);
        final boolean rDark = rNib != null && rNib.isDarkVisible();
        final boolean hasR = hasVisibleData(rNib, rDark);

        byte flags = 0;
        if (hasR) {
            flags |= FLAG_R;
        } else if (rDark) {
            flags |= FLAG_R_DARK;
        }

        if (SupernovaConfig.isScalarMode()) {
            buffer.put(flags);
            if (hasR) putNibble(buffer, rNib);
        } else {
            final SWMRNibbleArray gNib = nibbleAt(gNibbles, sectionY);
            final boolean gDark = gNib != null && gNib.isDarkVisible();
            final boolean hasG = hasVisibleData(gNib, gDark);
            final SWMRNibbleArray bNib = nibbleAt(bNibbles, sectionY);
            final boolean bDark = bNib != null && bNib.isDarkVisible();
            final boolean hasB = hasVisibleData(bNib, bDark);

            if (hasG) {
                flags |= FLAG_G;
            } else if (gDark) {
                flags |= FLAG_G_DARK;
            }
            if (hasB) {
                flags |= FLAG_B;
            } else if (bDark) {
                flags |= FLAG_B_DARK;
            }

            buffer.put(flags);
            if (hasR) putNibble(buffer, rNib);
            if (hasG) putNibble(buffer, gNib);
            if (hasB) putNibble(buffer, bNib);
        }
    }

    private static void putNibble(final ByteBuffer buffer, final SWMRNibbleArray nib) {
        final byte[] scratch = PACKET_SCRATCH.get();
        final int state = nib.snapshotVisible(scratch, 0);
        // A publish since the isDarkVisible probe can leave a uniform state.
        if (state != SWMRNibbleArray.VISIBLE_DATA) {
            Arrays.fill(scratch, state == SWMRNibbleArray.VISIBLE_FULL ? (byte) 0xFF : (byte) 0);
        }
        buffer.put(scratch);
    }

    /** A null nibble is absent rather than dark and carries neither flag, so the reader leaves the receiver's nibble alone. */
    private static boolean hasVisibleData(final SWMRNibbleArray nibble, final boolean dark) {
        return nibble != null && !dark && !nibble.isNullNibbleVisible();
    }

    static void readSectionFromBuffer(ByteBuffer buffer, SWMRNibbleArray[] rNibbles, SWMRNibbleArray[] gNibbles, SWMRNibbleArray[] bNibbles, int sectionY) {
        final byte flags = buffer.get();
        final boolean hasR = (flags & FLAG_R) != 0;
        final boolean hasG = (flags & FLAG_G) != 0;
        final boolean hasB = (flags & FLAG_B) != 0;
        final boolean darkR = (flags & FLAG_R_DARK) != 0;
        final boolean darkG = (flags & FLAG_G_DARK) != 0;
        final boolean darkB = (flags & FLAG_B_DARK) != 0;

        if (SupernovaConfig.isScalarMode()) {
            // A scalar client must still consume all three channels off the wire; collapse to max in R.
            byte[] rData = null, gData = null, bData = null;
            if (hasR) rData = readRawFromBuffer(buffer);
            if (hasG) gData = readRawFromBuffer(buffer);
            if (hasB) bData = readRawFromBuffer(buffer);

            if (rNibbles == null) return;
            final int idx = nibbleIndex(sectionY);
            if (idx < 0 || idx >= rNibbles.length) return;

            if (rData == null && (gData != null || bData != null)) {
                rData = new byte[SWMRNibbleArray.ARRAY_SIZE];
            }
            if (rData != null) {
                if (gData != null || bData != null) {
                    collapseMax(rData, gData, bData);
                }
                rNibbles[idx] = new SWMRNibbleArray(rData);
            } else if (darkR || darkG || darkB) {
                rNibbles[idx] = new SWMRNibbleArray();
            }
        } else {
            if (hasR) {
                readNibbleFromBuffer(buffer, rNibbles, sectionY);
            } else if (darkR) {
                setUninitNibble(rNibbles, sectionY);
            }
            if (hasG) {
                readNibbleFromBuffer(buffer, gNibbles, sectionY);
            } else if (darkG) {
                setUninitNibble(gNibbles, sectionY);
            }
            if (hasB) {
                readNibbleFromBuffer(buffer, bNibbles, sectionY);
            } else if (darkB) {
                setUninitNibble(bNibbles, sectionY);
            }
            // Only R present means a scalar server; mirror it for uniform white light.
            if ((hasR || darkR) && !hasG && !darkG && !hasB && !darkB) {
                cloneNibbleChannel(rNibbles, gNibbles, sectionY);
                cloneNibbleChannel(rNibbles, bNibbles, sectionY);
            }
        }
    }

    private static byte[] readRawFromBuffer(ByteBuffer buffer) {
        final byte[] data = new byte[SWMRNibbleArray.ARRAY_SIZE];
        buffer.get(data);
        return data;
    }

    private static SWMRNibbleArray nibbleAt(SWMRNibbleArray[] nibbles, int sectionY) {
        if (nibbles == null) return null;
        final int idx = nibbleIndex(sectionY);
        return (idx < 0 || idx >= nibbles.length) ? null : nibbles[idx];
    }

    private static void setUninitNibble(SWMRNibbleArray[] nibbles, int sectionY) {
        if (nibbles == null) return;
        final int idx = nibbleIndex(sectionY);
        if (idx < 0 || idx >= nibbles.length) return;
        nibbles[idx] = new SWMRNibbleArray();
    }

    private static void readNibbleFromBuffer(ByteBuffer buffer, SWMRNibbleArray[] nibbles, int sectionY) {
        final byte[] data = new byte[SWMRNibbleArray.ARRAY_SIZE];
        buffer.get(data);
        if (nibbles == null) return;
        final int idx = nibbleIndex(sectionY);
        if (idx < 0 || idx >= nibbles.length) return;
        nibbles[idx] = new SWMRNibbleArray(data);
    }

    static void collapseMax(byte[] rData, byte[] gData, byte[] bData) {
        for (int i = 0; i < rData.length; ++i) {
            final int r = rData[i] & 0xFF;
            final int g = gData != null ? gData[i] & 0xFF : 0;
            final int b = bData != null ? bData[i] & 0xFF : 0;
            final int rLo = r & 0xF, rHi = (r >>> 4) & 0xF;
            final int gLo = g & 0xF, gHi = (g >>> 4) & 0xF;
            final int bLo = b & 0xF, bHi = (b >>> 4) & 0xF;
            rData[i] = (byte) ((Math.max(rHi, Math.max(gHi, bHi)) << 4) | Math.max(rLo, Math.max(gLo, bLo)));
        }
    }
}
