package com.mitchej123.supernova.light.engine;

import org.junit.jupiter.api.Test;

import static com.mitchej123.supernova.api.PackedColorLight.ALL_CHANNELS;
import static com.mitchej123.supernova.api.PackedColorLight.BLUE_MASK;
import static com.mitchej123.supernova.api.PackedColorLight.GREEN_MASK;
import static com.mitchej123.supernova.api.PackedColorLight.RED_MASK;
import static com.mitchej123.supernova.api.PackedColorLight.anyComponentGreater;
import static com.mitchej123.supernova.api.PackedColorLight.anyNonZero;
import static com.mitchej123.supernova.api.PackedColorLight.blue;
import static com.mitchej123.supernova.api.PackedColorLight.channelPresenceMask;
import static com.mitchej123.supernova.api.PackedColorLight.green;
import static com.mitchej123.supernova.api.PackedColorLight.maxComponent;
import static com.mitchej123.supernova.api.PackedColorLight.pack;
import static com.mitchej123.supernova.api.PackedColorLight.packedMax;
import static com.mitchej123.supernova.api.PackedColorLight.packedSub;
import static com.mitchej123.supernova.api.PackedColorLight.packedSubRGB;
import static com.mitchej123.supernova.api.PackedColorLight.red;
import static com.mitchej123.supernova.api.PackedColorLight.transmittanceToAbsorption;
import static com.mitchej123.supernova.light.engine.BFSTestHelper.RGB_DIR_SHIFT;
import static com.mitchej123.supernova.light.engine.PackedColorLightQueue.channelMaskWhereGt;
import static com.mitchej123.supernova.light.engine.PackedColorLightQueue.decodeQueueRGB;
import static com.mitchej123.supernova.light.engine.PackedColorLightQueue.encodeQueuePackedRGB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedColorLightTest {

    private static final int[] ABSORPTIONS = {
        pack(1, 1, 1), pack(2, 1, 0), pack(0, 0, 1), pack(3, 0, 0),
        pack(15, 15, 15), pack(14, 1, 7), pack(0, 15, 0),
    };

    @Test
    void anyComponentGreaterDetectsEachChannel() {
        assertTrue(anyComponentGreater(pack(5, 0, 0), pack(4, 0, 0)));
        assertTrue(anyComponentGreater(pack(0, 5, 0), pack(0, 4, 0)));
        assertTrue(anyComponentGreater(pack(0, 0, 5), pack(0, 0, 4)));
        assertFalse(anyComponentGreater(pack(4, 4, 4), pack(4, 4, 4)));
        assertFalse(anyComponentGreater(pack(3, 3, 3), pack(4, 4, 4)));
        assertTrue(anyComponentGreater(pack(5, 3, 3), pack(4, 4, 4)));
    }

    @Test
    void maxComponentPicksLargestChannel() {
        assertEquals(15, maxComponent(pack(15, 7, 3)));
        assertEquals(12, maxComponent(pack(3, 12, 8)));
        assertEquals(9, maxComponent(pack(0, 0, 9)));
        assertEquals(0, maxComponent(pack(0, 0, 0)));
    }

    @Test
    void packedSubExhaustive() {
        for (int val = 0; val <= 15; val++) {
            for (int opacity = 0; opacity <= 15; opacity++) {
                int rgb = pack(val, val, val);
                int result = packedSub(rgb, opacity);
                int expected = Math.max(0, val - opacity);
                assertEquals(expected, red(result), "r: val=" + val + " opacity=" + opacity);
                assertEquals(expected, green(result), "g: val=" + val + " opacity=" + opacity);
                assertEquals(expected, blue(result), "b: val=" + val + " opacity=" + opacity);
            }
        }
    }

    @Test
    void channelMaskWhereGtFlagsGreaterChannels() {
        // The sweep below never has all channels greater: its G runs opposite to R and B.
        assertEquals(RED_MASK, channelMaskWhereGt(pack(10, 5, 2), pack(8, 5, 4)), "only R should be greater");
        assertEquals(ALL_CHANNELS, channelMaskWhereGt(pack(10, 10, 10), pack(5, 5, 5)));
        assertEquals(0, channelMaskWhereGt(pack(3, 3, 3), pack(5, 5, 5)));
        assertEquals(0, channelMaskWhereGt(pack(5, 5, 5), pack(5, 5, 5)));
    }

    @Test
    void channelMaskWhereGtExhaustive() {
        for (int a = 0; a <= 15; a++) {
            for (int b = 0; b <= 15; b++) {
                int pa = pack(a, 15 - a, a);
                int pb = pack(b, 15 - b, b);
                int mask = channelMaskWhereGt(pa, pb);
                boolean rGt = a > b;
                boolean gGt = (15 - a) > (15 - b);
                assertEquals(rGt ? RED_MASK : 0, mask & RED_MASK, "r: a=" + a + " b=" + b);
                assertEquals(gGt ? GREEN_MASK : 0, mask & GREEN_MASK, "g: a=" + a + " b=" + b);
                assertEquals(rGt ? BLUE_MASK : 0, mask & BLUE_MASK, "b: a=" + a + " b=" + b);
            }
        }
    }

    @Test
    void transmittanceToAbsorptionExhaustive() {
        for (int r = 0; r <= 15; r++) {
            for (int g = 0; g <= 15; g++) {
                for (int b = 0; b <= 15; b++) {
                    int result = transmittanceToAbsorption(pack(r, g, b));
                    assertEquals(15 - r, red(result), "r=" + r + " g=" + g + " b=" + b);
                    assertEquals(15 - g, green(result), "r=" + r + " g=" + g + " b=" + b);
                    assertEquals(15 - b, blue(result), "r=" + r + " g=" + g + " b=" + b);
                }
            }
        }
    }

    @Test
    void packedSubDelegatesToPackedSubRGB() {
        for (int r = 0; r <= 15; r += 3) {
            for (int g = 0; g <= 15; g += 3) {
                for (int b = 0; b <= 15; b += 3) {
                    for (int opacity = 0; opacity <= 15; opacity++) {
                        int rgb = pack(r, g, b);
                        assertEquals(
                                packedSubRGB(rgb, pack(opacity, opacity, opacity)),
                                packedSub(rgb, opacity),
                                "r=" + r + " g=" + g + " b=" + b + " op=" + opacity);
                    }
                }
            }
        }
    }

    @Test
    void channelPresenceMaskExhaustive() {
        for (int r = 0; r <= 15; r++) {
            for (int g = 0; g <= 15; g++) {
                for (int b = 0; b <= 15; b++) {
                    int packed = pack(r, g, b);
                    int mask = channelPresenceMask(packed);
                    assertEquals(r != 0 ? RED_MASK : 0, mask & RED_MASK, "r=" + r + " g=" + g + " b=" + b);
                    assertEquals(g != 0 ? GREEN_MASK : 0, mask & GREEN_MASK, "r=" + r + " g=" + g + " b=" + b);
                    assertEquals(b != 0 ? BLUE_MASK : 0, mask & BLUE_MASK, "r=" + r + " g=" + g + " b=" + b);
                }
            }
        }
    }

    private static void assertQueueEntry(long entry, int x, int z, int y, int rgb, int directions) {
        assertEquals(x, (int) (entry & 0x3F), "x");
        assertEquals(z, (int) ((entry >>> 6) & 0x3F), "z");
        assertEquals(y, (int) ((entry >>> 12) & 0xFFFF), "y");
        assertEquals(rgb, decodeQueueRGB(entry), "rgb");
        assertEquals(directions, (int) ((entry >>> RGB_DIR_SHIFT) & 0x3F), "directions");
    }

    @Test
    void queueEntryRoundTrips() {
        assertQueueEntry(SupernovaEngine.encodeCoords(15, 20, 200, 0) | encodeQueuePackedRGB(pack(12, 7, 3)) | ((long) 0b101010 << RGB_DIR_SHIFT),
            15, 20, 200, pack(12, 7, 3), 0b101010);

        final long max = SupernovaEngine.encodeCoords(63, 63, 65535, 0) | encodeQueuePackedRGB(pack(15, 15, 15)) | ((long) 0x3F << RGB_DIR_SHIFT)
            | SupernovaEngine.FLAG_WRITE_LEVEL | SupernovaEngine.FLAG_RECHECK_LEVEL | SupernovaEngine.FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
        assertQueueEntry(max, 63, 63, 65535, pack(15, 15, 15), 0x3F);
        assertTrue((max & SupernovaEngine.FLAG_WRITE_LEVEL) != 0);
        assertTrue((max & SupernovaEngine.FLAG_RECHECK_LEVEL) != 0);
        assertTrue((max & SupernovaEngine.FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) != 0);

        assertQueueEntry(SupernovaEngine.encodeCoords(0, 0, 0, 0) | encodeQueuePackedRGB(0), 0, 0, 0, 0, 0);
    }

    @Test
    void anyNonZeroDetectsAnyChannel() {
        assertFalse(anyNonZero(0));
        assertTrue(anyNonZero(pack(1, 0, 0)));
        assertTrue(anyNonZero(pack(0, 1, 0)));
        assertTrue(anyNonZero(pack(0, 0, 1)));
        assertTrue(anyNonZero(pack(15, 15, 15)));
    }

    @Test
    void packedMaxExhaustive() {
        for (int a = 0; a <= 15; a++) {
            for (int b = 0; b <= 15; b++) {
                int pa = pack(a, 15 - a, a);
                int pb = pack(b, 15 - b, b);
                int result = packedMax(pa, pb);
                assertEquals(Math.max(a, b), red(result), "r: a=" + a + " b=" + b);
                assertEquals(Math.max(15 - a, 15 - b), green(result), "g: a=" + a + " b=" + b);
                assertEquals(Math.max(a, b), blue(result), "b: a=" + a + " b=" + b);
            }
        }
    }

    @Test
    void packedSubRGBMatchesScalarReferenceForAllValues() {
        for (int r = 0; r <= 15; r++) {
            for (int g = 0; g <= 15; g++) {
                for (int b = 0; b <= 15; b++) {
                    final int packed = pack(r, g, b);
                    for (final int absorption : ABSORPTIONS) {
                        final int result = packedSubRGB(packed, absorption);
                        final String ctx = "(" + r + "," + g + "," + b + ") - 0x" + Integer.toHexString(absorption);
                        assertEquals(Math.max(r - red(absorption), 0), red(result), "R of " + ctx);
                        assertEquals(Math.max(g - green(absorption), 0), green(result), "G of " + ctx);
                        assertEquals(Math.max(b - blue(absorption), 0), blue(result), "B of " + ctx);
                        assertEquals(0, result & ~ALL_CHANNELS, "spacer garbage in " + ctx);
                    }
                }
            }
        }
    }

    @Test
    void queueRGBSurvivesAdjacentFields() {
        final int[] rgbs = { pack(15, 0, 0), pack(0, 15, 0), pack(0, 0, 15), pack(15, 15, 15), pack(15, 12, 10), 0 };
        final long allFlags = SupernovaEngine.FLAG_WRITE_LEVEL | SupernovaEngine.FLAG_RECHECK_LEVEL
            | SupernovaEngine.FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
        for (final int rgb : rgbs) {
            for (int dir = 0; dir < 64; dir++) {
                final long entry = 0x0FFFFFFFL
                    | encodeQueuePackedRGB(rgb)
                    | ((long) dir << RGB_DIR_SHIFT)
                    | allFlags;
                assertEquals(rgb, decodeQueueRGB(entry), "rgb 0x" + Integer.toHexString(rgb) + " with dir " + dir);
            }
        }
    }
}
