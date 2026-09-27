package com.mitchej123.supernova.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColoredLightHelperTest {

    private static final float EPSILON = 1e-4f;

    private static float[] tint(float br, float bg, float bb, float sr, float sg, float sb) {
        float[] out = new float[3];
        ColoredLightHelper.computeTint(br, bg, bb, sr, sg, sb, out);
        return out;
    }

    private static float[] tint(TintBlendMode mode, float[] in) {
        float[] out = new float[3];
        mode.computeTint(in[0], in[1], in[2], in[3], in[4], in[5], out);
        return out;
    }

    private static float[] tintImpl(TintBlendMode mode, float[] in) {
        float[] out = new float[3];
        mode.computeTintImpl(in[0], in[1], in[2], in[3], in[4], in[5], out);
        return out;
    }

    @Nested
    class ThroughHelper {

        /** Tint function and blend mode are process-wide globals AngelicaCompat may have changed. */
        @BeforeEach
        void pinBlendMode() {
            TintBlendMode.current = TintBlendMode.SQUARED_WEIGHT;
            ColoredLightHelper.setActiveTintFunction(
                (br, bg, bb, sr, sg, sb, out) -> TintBlendMode.current.computeTint(br, bg, bb, sr, sg, sb, out));
        }

        private float weighted(float unweighted, float maxLight) {
            final float w = TintBlendMode.hueConfidence(maxLight);
            return 1f + (unweighted - 1f) * w;
        }

        @Test
        void bothBelowThreshold() {
            float[] result = tint(0.4f, 0.2f, 0.1f, 0.3f, 0.1f, 0.0f);
            assertArrayEquals(new float[] { 1f, 1f, 1f }, result);
        }

        @Test
        void pureBlockLightRed() {
            float[] result = tint(15, 2, 0, 0, 0, 0);
            assertEquals(1.0f, result[0], EPSILON);
            assertEquals(2f / 15f, result[1], EPSILON);
            assertEquals(0f, result[2], EPSILON);
        }

        @Test
        void pureSkyLightWhite() {
            float[] result = tint(0, 0, 0, 10, 10, 10);
            assertEquals(1.0f, result[0], EPSILON);
            assertEquals(1.0f, result[1], EPSILON);
            assertEquals(1.0f, result[2], EPSILON);
        }

        @Test
        void pureSkyLightColored() {
            float[] result = tint(0, 0, 0, 5, 5, 10);
            assertEquals(weighted(0.5f, 10f), result[0], EPSILON);
            assertEquals(weighted(0.5f, 10f), result[1], EPSILON);
            assertEquals(1.0f, result[2], EPSILON);
        }

        @Test
        void equalSourcesUniform() {
            float[] result = tint(10, 10, 10, 10, 10, 10);
            assertEquals(1.0f, result[0], EPSILON);
            assertEquals(1.0f, result[1], EPSILON);
            assertEquals(1.0f, result[2], EPSILON);
        }

        @Test
        void dominantBlockLight() {
            float[] result = tint(15, 0, 0, 2, 2, 2);
            assertEquals(1.0f, result[0], EPSILON);
            assertEquals(0.0174672f, result[1], EPSILON);
            assertEquals(0.0174672f, result[2], EPSILON);
        }

        @Test
        void dominantSkyLight() {
            float[] result = tint(2, 0, 0, 15, 15, 15);
            assertEquals(1.0f, result[0], EPSILON);
            assertEquals(0.9825328f, result[1], EPSILON);
            assertEquals(0.9825328f, result[2], EPSILON);
        }

        @Test
        void symmetricColorsEqualWeight() {
            float[] result = tint(10, 0, 0, 0, 0, 10);
            assertEquals(weighted(0.5f, 10f), result[0], EPSILON);
            assertEquals(weighted(0f, 10f), result[1], EPSILON);
            assertEquals(weighted(0.5f, 10f), result[2], EPSILON);
        }

        @Test
        void blockThresholdBoundary() {
            // blockMax exactly 0.5 is below the threshold, so the block contribution is neutral white.
            float[] result = tint(0.5f, 0, 0, 10, 10, 10);
            assertEquals(1.0f, result[0], EPSILON);
            assertEquals(1.0f, result[1], EPSILON);
            assertEquals(1.0f, result[2], EPSILON);
        }

        @Test
        void blockJustAboveThreshold() {
            float[] result = tint(0.6f, 0.3f, 0, 0, 0, 0);
            assertEquals(1.0f, result[0], EPSILON);
            assertEquals(weighted(0.5f, 0.6f), result[1], EPSILON);
            assertEquals(weighted(0f, 0.6f), result[2], EPSILON);
        }
    }

    @Nested
    class BlendModes {

        private float distFromWhite(float[] tint) {
            return Math.max(Math.abs(tint[0] - 1f), Math.max(Math.abs(tint[1] - 1f), Math.abs(tint[2] - 1f)));
        }

        @Test
        void dimSourcesTintWeakerThanBrightOnesOfTheSameHue() {
            for (TintBlendMode mode : TintBlendMode.values()) {
                float[] dim = tint(mode, new float[] { 1, 0, 0, 0, 0, 0 });
                float[] bright = tint(mode, new float[] { 15, 0, 0, 0, 0, 0 });
                assertNotEquals(distFromWhite(bright), distFromWhite(dim), 1e-3f,
                        mode + " tints a level-1 source exactly like a level-15 source");
                assertTrue(distFromWhite(dim) < distFromWhite(bright), mode + " did not weaken the dim source's tint");
                assertTrue(distFromWhite(dim) < 0.05f, mode + " left a level-1 source visibly tinted: " + distFromWhite(dim));
            }
        }

        @Test
        void zeroLightIsFullyNeutral() {
            for (TintBlendMode mode : TintBlendMode.values()) {
                assertArrayEquals(new float[] { 1f, 1f, 1f }, tint(mode, new float[] { 0, 0, 0, 0, 0, 0 }), 1e-6f,
                        mode + " tinted a completely unlit position");
            }
        }

        @Test
        void fullLightIsAStrictNoOp() {
            for (TintBlendMode mode : TintBlendMode.values()) {
                for (float[] in : new float[][] {
                        { 15, 0, 0, 0, 0, 0 },
                        { 0, 0, 0, 15, 15, 15 },
                        { 5, 7, 3, 15, 15, 15 } }) {
                    assertArrayEquals(tintImpl(mode, in), tint(mode, in), 0f, mode + " altered a fully-lit position");
                }
            }
        }

        @Test
        void confidenceIsMonotonicAndBounded() {
            assertEquals(0f, TintBlendMode.hueConfidence(0f), 1e-6f, "not neutral in the dark");
            assertEquals(1f, TintBlendMode.hueConfidence(15f), 1e-6f, "does not reach full confidence");
            float prev = TintBlendMode.hueConfidence(0f);
            for (float level = 0.25f; level <= 15f; level += 0.25f) {
                float w = TintBlendMode.hueConfidence(level);
                assertTrue(w >= 0f && w <= 1f, "out of range at " + level + ": " + w);
                assertTrue(w > prev, "did not increase at " + level);
                prev = w;
            }
        }

        @Test
        void confidenceClampsOutOfRangeInput() {
            assertEquals(0f, TintBlendMode.hueConfidence(-3f), 1e-6f, "underflowed");
            assertEquals(1f, TintBlendMode.hueConfidence(20f), 1e-6f, "overflowed");
        }

        @Test
        void saturationIsNonDecreasingInIntensity() {
            for (TintBlendMode mode : TintBlendMode.values()) {
                float prev = 0f;
                for (int level = 1; level <= 15; level++) {
                    float dist = distFromWhite(tint(mode, new float[] { 0, level, 0, 0, 0, 0 }));
                    assertTrue(dist >= prev - 1e-5f, mode + " regressed at level " + level);
                    prev = dist;
                }
            }
        }

        @Test
        void fullWhiteSkyStaysWhite() {
            for (TintBlendMode mode : TintBlendMode.values()) {
                assertArrayEquals(new float[] { 1f, 1f, 1f }, tint(mode, new float[] { 0, 0, 0, 15, 15, 15 }), 1e-3f,
                        mode + " tinted full white sky");
            }
        }
    }
}
