package com.mitchej123.supernova.compat.angelica;

import com.gtnewhorizons.angelica.api.SectionLightData;
import com.mitchej123.supernova.light.SWMRNibbleArray;

import java.util.Arrays;

/** Fuses the six nibble planes into one array at construction, so a {@link SectionLightData} read touches no volatile and no SWMR state. */
public class SupernovaSectionLightData implements SectionLightData {

    /** Fused value for a section with zero block light and full white sky light. */
    private static final long FUSED_ZERO_BLOCK_WHITE_SKY = 0xFFFL;

    private static final int SIZE = SWMRNibbleArray.ARRAY_SIZE;

    private static final byte[] ZERO_BYTES = new byte[SIZE];
    private static final byte[] FULL_BYTES = new byte[SIZE];

    static {
        Arrays.fill(FULL_BYTES, (byte) 0xFF);
    }

    private static final ThreadLocal<byte[][]> SCRATCH = ThreadLocal.withInitial(() -> new byte[6][SIZE]);

    /** Per position, {@code (block << 16) | sky}, each half a 12-bit RGB triple; null for a uniform dark-block/full-sky section. */
    private final int[] fusedCache;

    /** No block light, full daylight: the shape of every section above the terrain. */
    public static final SupernovaSectionLightData ZERO_BLOCK_FULL_SKY = new SupernovaSectionLightData(null, null, null, null, null, null, true);

    public SupernovaSectionLightData(SWMRNibbleArray r, SWMRNibbleArray g, SWMRNibbleArray b, SWMRNibbleArray skyR, SWMRNibbleArray skyG,
        SWMRNibbleArray skyB, boolean hasSky) {
        final byte[][] scratch = SCRATCH.get();
        final byte[] rSrc = stateSource(snapshot(r, scratch[0]), scratch[0]);
        final byte[] gSrc = stateSource(snapshot(g, scratch[1]), scratch[1]);
        final byte[] bSrc = stateSource(snapshot(b, scratch[2]), scratch[2]);

        // Absent sky R is daylight; absent G/B mirror R.
        final int srState = snapshot(skyR, scratch[3]);
        final byte[] srSrc = srState == SWMRNibbleArray.VISIBLE_ABSENT ? (hasSky ? FULL_BYTES : ZERO_BYTES) : stateSource(srState, scratch[3]);
        final int sgState = snapshot(skyG, scratch[4]);
        final byte[] sgSrc = sgState == SWMRNibbleArray.VISIBLE_ABSENT ? srSrc : stateSource(sgState, scratch[4]);
        final int sbState = snapshot(skyB, scratch[5]);
        final byte[] sbSrc = sbState == SWMRNibbleArray.VISIBLE_ABSENT ? srSrc : stateSource(sbState, scratch[5]);

        if (rSrc == ZERO_BYTES && gSrc == ZERO_BYTES && bSrc == ZERO_BYTES && srSrc == FULL_BYTES && sgSrc == FULL_BYTES && sbSrc == FULL_BYTES) {
            this.fusedCache = null;
            return;
        }

        final int[] cache = new int[4096];
        for (int i = 0, idx = 0; i < SIZE; ++i, idx += 2) {
            final int rb = rSrc[i], gb = gSrc[i], bb = bSrc[i];
            final int srb = srSrc[i], sgb = sgSrc[i], sbb = sbSrc[i];
            cache[idx] = ((rb & 0xF) << 24) | ((gb & 0xF) << 20) | ((bb & 0xF) << 16) | ((srb & 0xF) << 8) | ((sgb & 0xF) << 4) | (sbb & 0xF);
            cache[idx + 1] = ((rb & 0xF0) << 20) | ((gb & 0xF0) << 16) | ((bb & 0xF0) << 12) | ((srb & 0xF0) << 4) | (sgb & 0xF0) | ((sbb & 0xF0) >>> 4);
        }
        this.fusedCache = cache;
    }

    private static int snapshot(final SWMRNibbleArray nib, final byte[] dst) {
        return nib == null ? SWMRNibbleArray.VISIBLE_ABSENT : nib.snapshotVisible(dst, 0);
    }

    private static byte[] stateSource(final int state, final byte[] scratch) {
        return switch (state) {
            case SWMRNibbleArray.VISIBLE_DATA -> scratch;
            case SWMRNibbleArray.VISIBLE_FULL -> FULL_BYTES;
            default -> ZERO_BYTES;
        };
    }

    @Override
    public int getRGB(int localX, int localY, int localZ) {
        if (fusedCache == null) return 0;
        final int idx = (localX & 15) | ((localZ & 15) << 4) | ((localY & 15) << 8);
        return (fusedCache[idx] >>> 16) & 0xFFF;
    }

    @Override
    public int getSkyRGB(int localX, int localY, int localZ) {
        if (fusedCache == null) return 0xFFF;
        final int idx = (localX & 15) | ((localZ & 15) << 4) | ((localY & 15) << 8);
        return fusedCache[idx] & 0xFFF;
    }

    /** Returns both 12-bit RGB triples as {@code (block << 16) | sky}. */
    @Override
    public long getRGBAndSkyRGB(int localX, int localY, int localZ) {
        if (fusedCache == null) return FUSED_ZERO_BLOCK_WHITE_SKY;
        final int idx = (localX & 15) | ((localZ & 15) << 4) | ((localY & 15) << 8);
        return fusedCache[idx];
    }
}
