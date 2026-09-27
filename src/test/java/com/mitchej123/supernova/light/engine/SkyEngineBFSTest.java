package com.mitchej123.supernova.light.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.mitchej123.supernova.api.PackedColorLight.green;
import static com.mitchej123.supernova.api.PackedColorLight.red;
import static com.mitchej123.supernova.light.engine.BlockEngineBFSTest.assertLight;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shared cases are parameterized in {@link BlockEngineBFSTest}. */
class SkyEngineBFSTest {

    private TestableSkyEngine engine;

    @BeforeEach
    void setup() {
        engine = BFSTestHelper.centeredNeighborhood(new TestableSkyEngine(MCBootstrap.getServerWorld()));
    }

    @Test
    void testWhiteSourceImmutableDuringDecrease() {
        BFSTestHelper.enqueueIncrease(engine, 4, 68, 8, 15, 15, 15);
        BFSTestHelper.enqueueIncrease(engine, 12, 68, 8, 15, 15, 15);
        engine.callPerformLightIncrease();

        BFSTestHelper.setLight(engine, 12, 68, 8, 0, 0, 0);
        BFSTestHelper.enqueueDecrease(engine, 12, 68, 8, 15, 15, 15);
        engine.callPerformLightDecrease();

        assertLight(engine, 4, 68, 8, 15, 15, 15);
        assertLight(engine, 5, 68, 8, 14, 14, 14);
        assertLight(engine, 3, 68, 8, 14, 14, 14);
    }

    @Test
    void testDecreaseWithSurvivingWhiteSource() {
        BFSTestHelper.enqueueIncrease(engine, 4, 68, 8, 15, 15, 15);
        BFSTestHelper.enqueueIncrease(engine, 12, 68, 8, 15, 0, 0);
        engine.callPerformLightIncrease();

        BFSTestHelper.setLight(engine, 12, 68, 8, 0, 0, 0);
        BFSTestHelper.enqueueDecrease(engine, 12, 68, 8, 15, 0, 0);
        engine.callPerformLightDecrease();

        assertLight(engine, 4, 68, 8, 15, 15, 15);
        assertLight(engine, 5, 68, 8, 14, 14, 14);

        final int removed = BFSTestHelper.getLight(engine, 12, 68, 8);
        assertEquals(0, red(removed) - green(removed), "no red excess over green at removed source (white still reaches here)");
    }
}
