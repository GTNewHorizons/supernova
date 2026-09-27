package com.mitchej123.supernova.light.engine;

import net.minecraft.init.Blocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.mitchej123.supernova.api.PackedColorLight.blue;
import static com.mitchej123.supernova.api.PackedColorLight.green;
import static com.mitchej123.supernova.api.PackedColorLight.pack;
import static com.mitchej123.supernova.api.PackedColorLight.red;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/** R >= G >= B holds only because every source is hue-ordered; a non-ordered source voids every assertion. */
class BlockEngineHueInvariantTest {

    private static final int SRC_X = 8, SRC_Y = 68, SRC_Z = 8;
    private static final int ORDERED_R = 15, ORDERED_G = 12, ORDERED_B = 10;

    private static final int POOL_X = 6, POOL_Y = 68, POOL_Z = 6;
    private static final int POOL_SIZE = 6;

    /** Populated cache: chunks -1..1, sections 3..5. */
    private static final int MIN_XZ = -16, MAX_XZ = 31, MIN_Y = 48, MAX_Y = 95;

    private static final int REACH = 16;

    private static final int[] CHURN_SOURCE = { 12, 68, 12 };

    private static final int[][] FRINGE = {
        {-2, 68, 8}, {20, 68, 9}, {8, 68, -2}, {9, 68, 20},
        {-4, 70, 6}, {21, 66, 11}, {7, 60, 7}, {10, 76, 10},
        {-1, 72, 3}, {18, 66, 3}, {5, 64, 17}, {13, 72, -1},
        {22, 68, 14}, {-5, 68, 10}, {11, 68, 22}, {6, 68, -5}
    };

    private TestableBlockEngine engine;

    @BeforeEach
    void setup() {
        engine = BFSTestHelper.centeredNeighborhood(new TestableBlockEngine(MCBootstrap.getServerWorld()));
    }

    private interface FieldAssertion {
        void assertAt(int x, int y, int z, int manhattanDist, int light);
    }

    private void lightOrderedSource() {
        BFSTestHelper.enqueueIncrease(engine, SRC_X, SRC_Y, SRC_Z, ORDERED_R, ORDERED_G, ORDERED_B);
        engine.callPerformLightIncrease();
    }

    private void scanField(FieldAssertion assertion) {
        for (int dx = -16; dx <= 16; dx++) {
            for (int dy = -15; dy <= 15; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    final int d = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (d > 16) continue;
                    final int x = SRC_X + dx, y = SRC_Y + dy, z = SRC_Z + dz;
                    assertion.assertAt(x, y, z, d, BFSTestHelper.getLight(engine, x, y, z));
                }
            }
        }
    }

    private static void assertExactOrderedField(int x, int y, int z, int d, int light) {
        assertEquals(Math.max(ORDERED_R - d, 0), red(light), "R at (" + x + "," + y + "," + z + ") d=" + d);
        assertEquals(Math.max(ORDERED_G - d, 0), green(light), "G at (" + x + "," + y + "," + z + ") d=" + d);
        assertEquals(Math.max(ORDERED_B - d, 0), blue(light), "B at (" + x + "," + y + "," + z + ") d=" + d);
    }

    private static void assertHueOrdering(int x, int y, int z, int light, String phase) {
        final int r = red(light), g = green(light), b = blue(light);
        if (r >= g && g >= b) return;
        fail(phase + ": hue ordering violated at (" + x + "," + y + "," + z + "): rgb=(" + r + "," + g + "," + b + ")");
    }

    private void assertHueOrderingEverywhere(String phase) {
        assertHueOrderingIn(phase, MIN_XZ, MAX_XZ, MIN_Y, MAX_Y, MIN_XZ, MAX_XZ);
    }

    private void assertHueOrderingNear(String phase, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = Math.max(MIN_XZ, x0 - REACH); x <= Math.min(MAX_XZ, x1 + REACH); x++) {
            final int dx = axisDist(x, x0, x1);
            for (int y = Math.max(MIN_Y, y0 - REACH); y <= Math.min(MAX_Y, y1 + REACH); y++) {
                final int dxy = dx + axisDist(y, y0, y1);
                if (dxy > REACH) continue;
                for (int z = Math.max(MIN_XZ, z0 - REACH); z <= Math.min(MAX_XZ, z1 + REACH); z++) {
                    if (dxy + axisDist(z, z0, z1) > REACH) continue;
                    assertHueOrdering(x, y, z, BFSTestHelper.getLight(engine, x, y, z), phase);
                }
            }
        }
    }

    private void assertHueOrderingNear(String phase, int[] cell) {
        assertHueOrderingNear(phase, cell[0], cell[0], cell[1], cell[1], cell[2], cell[2]);
    }

    private void assertHueOrderingNearPool(String phase, int x0, int y, int z0) {
        assertHueOrderingNear(phase, x0, x0 + POOL_SIZE - 1, y, y, z0, z0 + POOL_SIZE - 1);
    }

    private void assertHueOrderingIn(String phase, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    assertHueOrdering(x, y, z, BFSTestHelper.getLight(engine, x, y, z), phase);
                }
            }
        }
    }

    private static int axisDist(int v, int lo, int hi) {
        return v < lo ? lo - v : (v > hi ? v - hi : 0);
    }

    private static int[] fringe(int cycle, int k) {
        return FRINGE[(cycle * 3 + k) % FRINGE.length];
    }

    private void placePool(int x0, int y, int z0) {
        for (int dx = 0; dx < POOL_SIZE; dx++) {
            for (int dz = 0; dz < POOL_SIZE; dz++) {
                BFSTestHelper.enqueueIncrease(engine, x0 + dx, y, z0 + dz, ORDERED_R, ORDERED_G, ORDERED_B);
            }
        }
        engine.callPerformLightIncrease();
    }

    private void removePoolOneBatch(int x0, int y, int z0) {
        for (int dx = 0; dx < POOL_SIZE; dx++) {
            for (int dz = 0; dz < POOL_SIZE; dz++) {
                final int x = x0 + dx, z = z0 + dz;
                final int old = BFSTestHelper.getLight(engine, x, y, z);
                BFSTestHelper.setLight(engine, x, y, z, 0, 0, 0);
                BFSTestHelper.enqueueDecrease(engine, x, y, z, red(old), green(old), blue(old));
            }
        }
        engine.callPerformLightDecrease();
    }

    @Test
    void singleEmitterHasExactPerChannelFalloff() {
        lightOrderedSource();
        scanField(BlockEngineHueInvariantTest::assertExactOrderedField);
    }

    @Test
    void emitterRemovalLeavesNoResidue() {
        lightOrderedSource();

        BFSTestHelper.setLight(engine, SRC_X, SRC_Y, SRC_Z, 0, 0, 0);
        BFSTestHelper.enqueueDecrease(engine, SRC_X, SRC_Y, SRC_Z, ORDERED_R, ORDERED_G, ORDERED_B);
        engine.callPerformLightDecrease();

        scanField((x, y, z, d, light) ->
            assertEquals(0, light & 0x0F0F0F, "residue at (" + x + "," + y + "," + z + "): 0x" + Integer.toHexString(light)));
    }

    @Test
    void whiteRemovalPreservesTheOrderedField() {
        lightOrderedSource();
        BFSTestHelper.enqueueIncrease(engine, SRC_X + 8, SRC_Y, SRC_Z, 15, 15, 15);
        engine.callPerformLightIncrease();

        BFSTestHelper.setLight(engine, SRC_X + 8, SRC_Y, SRC_Z, 0, 0, 0);
        BFSTestHelper.enqueueDecrease(engine, SRC_X + 8, SRC_Y, SRC_Z, 15, 15, 15);
        engine.callPerformLightDecrease();

        scanField(BlockEngineHueInvariantTest::assertExactOrderedField);
    }

    @Test
    void overlappingSourcesStayPerChannelMaxima() {
        lightOrderedSource();
        BFSTestHelper.enqueueIncrease(engine, SRC_X + 10, SRC_Y, SRC_Z, 0, 15, 0);
        engine.callPerformLightIncrease();

        scanField((x, y, z, d, light) -> {
            if (x == SRC_X + 10 && y == SRC_Y && z == SRC_Z) {
                assertEquals(pack(0, 15, 0), light & 0x0F0F0F, "green source cell is written wholesale, never back-filled by BFS");
                return;
            }
            final int dGreen = Math.abs(x - (SRC_X + 10)) + Math.abs(y - SRC_Y) + Math.abs(z - SRC_Z);
            assertEquals(Math.max(ORDERED_R - d, 0), red(light), "R at (" + x + "," + y + "," + z + ")");
            assertEquals(Math.max(Math.max(ORDERED_G - d, 0), Math.max(15 - dGreen, 0)), green(light), "G at (" + x + "," + y + "," + z + ")");
            assertEquals(Math.max(ORDERED_B - d, 0), blue(light), "B at (" + x + "," + y + "," + z + ")");
        });
    }

    @Test
    void fringeChurnKeepsHueOrdering() {
        placePool(POOL_X, POOL_Y, POOL_Z);

        for (int cycle = 0; cycle < 40; cycle++) {
            for (int k = 0; k < 3; k++) {
                final int[] c = fringe(cycle, k);
                final int old = BFSTestHelper.getLight(engine, c[0], c[1], c[2]);
                BFSTestHelper.setLight(engine, c[0], c[1], c[2], 0, 0, 0);
                BFSTestHelper.enqueueDecrease(engine, c[0], c[1], c[2], red(old), green(old), blue(old));
            }
            if (cycle % 5 == 0) {
                final int n = BFSTestHelper.getLight(engine, 11, 68, 11);
                BFSTestHelper.enqueueIncrease(engine, CHURN_SOURCE[0], CHURN_SOURCE[1], CHURN_SOURCE[2],
                    Math.max(red(n) - 1, 0), Math.max(green(n) - 1, 0), Math.max(blue(n) - 1, 0));
            }
            engine.callPerformLightDecrease();
            for (int k = 0; k < 3; k++) assertHueOrderingNear("churn cycle " + cycle, fringe(cycle, k));
            assertHueOrderingNear("churn cycle " + cycle, CHURN_SOURCE);
        }
        assertHueOrderingEverywhere("after churn");
    }

    @Test
    void blockToggleThroughCheckBlockKeepsHueOrdering() {
        placePool(POOL_X, POOL_Y, POOL_Z);

        for (int cycle = 0; cycle < 25; cycle++) {
            for (int k = 0; k < 3; k++) {
                final int[] c = fringe(cycle, k);
                BFSTestHelper.setBlock(engine, c[0], c[1], c[2], Blocks.stone);
                engine.callCheckBlock(c[0], c[1], c[2]);
            }
            engine.callPerformLightDecrease();
            for (int k = 0; k < 3; k++) assertHueOrderingNear("stone placed cycle " + cycle, fringe(cycle, k));

            for (int k = 0; k < 3; k++) {
                final int[] c = fringe(cycle, k);
                BFSTestHelper.setBlock(engine, c[0], c[1], c[2], Blocks.air);
                engine.callCheckBlock(c[0], c[1], c[2]);
            }
            engine.callPerformLightDecrease();
            for (int k = 0; k < 3; k++) assertHueOrderingNear("stone removed cycle " + cycle, fringe(cycle, k));
        }
        assertHueOrderingEverywhere("after block toggles");
    }

    @Test
    void overlappingRemovalFrontsKeepHueOrdering() {
        final int poolAX = 0, poolBX = 20, poolZ = 6;
        placePool(poolAX, POOL_Y, poolZ);
        placePool(poolBX, POOL_Y, poolZ);

        for (int iter = 0; iter < 10; iter++) {
            removePoolOneBatch(poolAX, POOL_Y, poolZ);
            assertHueOrderingNearPool("iter " + iter + " pool A removed", poolAX, POOL_Y, poolZ);
            placePool(poolAX, POOL_Y, poolZ);
            assertHueOrderingNearPool("iter " + iter + " pool A re-added", poolAX, POOL_Y, poolZ);

            removePoolOneBatch(poolBX, POOL_Y, poolZ);
            assertHueOrderingNearPool("iter " + iter + " pool B removed", poolBX, POOL_Y, poolZ);
            placePool(poolBX, POOL_Y, poolZ);
            assertHueOrderingNearPool("iter " + iter + " pool B re-added", poolBX, POOL_Y, poolZ);
        }
        assertHueOrderingEverywhere("after overlapping removal fronts");
    }
}
