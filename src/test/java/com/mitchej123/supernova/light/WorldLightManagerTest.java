package com.mitchej123.supernova.light;

import com.mitchej123.supernova.light.engine.MCBootstrap;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLightManagerTest {

    @Test
    void neighboringEdgeWorkBlocksSaveButDistantWorkDoesNot() {
        final WorldLightManager manager = new WorldLightManager(MCBootstrap.createStubWorld(true), true, true);
        try {
            manager.queueEdgeChecks(1, 1);
            assertTrue(manager.hasUnsettledLightValues(0, 0));
            assertFalse(manager.hasUnsettledLightValues(-1, -1));
            assertFalse(manager.awaitPendingWork(0, 0));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void clientBudgetStopsBetweenTasksAndResumesNextTick() {
        final WorldLightManager manager = new WorldLightManager(MCBootstrap.createStubWorld(true), true, true);
        try {
            manager.queueEdgeChecks(0, 0);
            final AtomicLong clock = new AtomicLong();
            manager.drainClientLight(() -> clock.getAndAdd(3_000_000L));
            assertTrue(manager.hasChunkPendingLight(0, 0), "one of the two lanes must remain after the shared deadline");
            manager.drainClientLight(() -> clock.getAndAdd(3_000_000L));
            assertFalse(manager.hasChunkPendingLight(0, 0));
        } finally {
            manager.shutdown();
        }
    }
}
