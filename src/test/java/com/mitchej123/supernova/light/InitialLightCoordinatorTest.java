package com.mitchej123.supernova.light;

import com.mitchej123.supernova.light.InitialLightCoordinator.Generation;
import com.mitchej123.supernova.light.InitialLightCoordinator.Publisher;
import com.mitchej123.supernova.light.engine.MCBootstrap;
import com.mitchej123.supernova.util.CoordinateUtils;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mitchej123.supernova.light.InitialLightCoordinator.BLOCK;
import static com.mitchej123.supernova.light.InitialLightCoordinator.SKY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InitialLightCoordinatorTest {

    private static final int CX = 3;
    private static final int CZ = 5;
    private static final long KEY = CoordinateUtils.getChunkKey(CX, CZ);


    private static final class RecordingPublisher implements Publisher {

        final ConcurrentHashMap<Chunk, Boolean> usable = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Chunk, Boolean> ready = new ConcurrentHashMap<>();
        final AtomicInteger publishes = new AtomicInteger();

        @Override
        public void beginGeneration(Chunk chunk) {
            usable.put(chunk, false);
            ready.put(chunk, false);
        }

        @Override
        public void markUsable(Chunk chunk) {
            usable.put(chunk, true);
        }

        @Override
        public void syncToVanilla(Chunk chunk) {}

        @Override
        public void markReady(Chunk chunk) {
            ready.put(chunk, true);
            publishes.incrementAndGet();
        }

        boolean isReady(Chunk chunk) {
            return Boolean.TRUE.equals(ready.get(chunk));
        }
    }

    private LightQueue skyQueue;
    private LightQueue blockQueue;
    private RecordingPublisher publisher;
    private ConcurrentHashMap<Long, Chunk> loaded;
    private InitialLightCoordinator coordinator;

    @BeforeEach
    void setup() {
        skyQueue = new LightQueue();
        blockQueue = new LightQueue();
        publisher = new RecordingPublisher();
        loaded = new ConcurrentHashMap<>();
        coordinator = new InitialLightCoordinator(skyQueue, blockQueue, publisher, loaded::get);
    }

    private void propagate(LightQueue queue, int lane) {
        final ChunkTasks task = queue.removeFirstInitialLightTask();
        assertNotNull(task, "propagation task must be queued");
        coordinator.completePropagation(task, lane);
        queue.completeTask(task);
    }

    private void reconcileEdges(LightQueue queue, int lane) {
        final ChunkTasks task = queue.removeFirstTask();
        assertNotNull(task, "edge task must be queued once every lane has propagated");
        coordinator.completeEdges(task, lane);
        queue.completeTask(task);
    }

    private Chunk loadedChunk() {
        final Chunk chunk = MCBootstrap.allocate(Chunk.class);
        loaded.put(KEY, chunk);
        return chunk;
    }

    @Test
    void publishesOnlyAfterBothLanesFinishBothPhases() {
        final Chunk chunk = loadedChunk();
        coordinator.queue(CX, CZ, chunk, null);

        propagate(skyQueue, SKY);
        assertFalse(publisher.isReady(chunk), "one lane propagated is not enough");
        assertFalse(Boolean.TRUE.equals(publisher.usable.get(chunk)), "usable waits for every lane too");

        propagate(blockQueue, BLOCK);
        assertTrue(publisher.usable.get(chunk), "both lanes propagated; neighbors may seed from it");
        assertFalse(publisher.isReady(chunk), "but seams are not reconciled yet");

        reconcileEdges(skyQueue, SKY);
        assertFalse(publisher.isReady(chunk));

        reconcileEdges(blockQueue, BLOCK);
        assertTrue(publisher.isReady(chunk));
        assertEquals(1, publisher.publishes.get());
    }

    @Test
    void chunkUnloadedBeforeTheLastLaneNeverPublishes() {
        final Chunk chunk = loadedChunk();
        coordinator.queue(CX, CZ, chunk, null);

        coordinator.completePropagation(skyQueue.removeFirstInitialLightTask(), SKY);
        loaded.remove(KEY);
        coordinator.completePropagation(blockQueue.removeFirstInitialLightTask(), BLOCK);

        assertEquals(0, publisher.publishes.get(), "a chunk that left the world must not be published");
        assertFalse(publisher.isReady(chunk));
        assertFalse(coordinator.hasPending(KEY), "and the generation must not leak");
    }

    @Test
    void supersededGenerationCanNeverPublish() throws Exception {
        final Chunk chunk = loadedChunk();
        final Generation first = coordinator.queue(CX, CZ, chunk, null);
        final ChunkTasks stale = skyQueue.removeFirstInitialLightTask();

        coordinator.queue(CX, CZ, chunk, null);
        first.done.get(1, TimeUnit.SECONDS);
        assertFalse(first.published, "the superseded generation resolves unpublished");

        coordinator.completePropagation(stale, SKY);
        coordinator.completePropagation(blockQueue.removeFirstInitialLightTask(), BLOCK);
        assertEquals(0, publisher.publishes.get(), "a stale lane completion must not advance the replacement");
    }

    @Test
    void removeChunkDropsTheGenerationAndResolvesItsWaiters() throws Exception {
        final Chunk chunk = loadedChunk();
        final Generation generation = coordinator.queue(CX, CZ, chunk, null);
        assertTrue(coordinator.hasPending(KEY));

        coordinator.removeChunk(CX, CZ);

        generation.done.get(1, TimeUnit.SECONDS);
        assertFalse(generation.published);
        assertFalse(coordinator.hasPending(KEY));
        assertEquals(0, publisher.publishes.get());
    }

    @Test
    void relightAfterUnloadStartsCleanly() {
        final Chunk chunk = loadedChunk();
        coordinator.queue(CX, CZ, chunk, null);
        coordinator.removeChunk(CX, CZ);

        coordinator.queue(CX, CZ, chunk, null);
        propagate(skyQueue, SKY);
        propagate(blockQueue, BLOCK);
        reconcileEdges(skyQueue, SKY);
        reconcileEdges(blockQueue, BLOCK);

        assertEquals(1, publisher.publishes.get(), "a relight queued after an unload must still be able to publish");
    }

    @Nested
    class LaneCountdown {

        private Generation bothLanes(long generation) {
            return new Generation(generation, SKY | BLOCK, null);
        }

        @Test
        void propagationThenEdgesAcrossBothLanes() {
            final Generation g = bothLanes(7);

            assertEquals(Generation.Result.WAITING, g.completePropagation(7, SKY));
            assertEquals(Generation.Result.PROPAGATION_DONE, g.completePropagation(7, BLOCK));
            assertEquals(Generation.Result.WAITING, g.completeEdges(7, SKY));
            assertEquals(Generation.Result.EDGES_DONE, g.completeEdges(7, BLOCK));
        }

        @Test
        void staleGenerationsAreIgnored() {
            final Generation g = bothLanes(7);

            assertEquals(Generation.Result.IGNORED, g.completePropagation(6, SKY), "a superseded relight must not advance its replacement");
            assertEquals(Generation.Result.WAITING, g.completePropagation(7, SKY));
            assertEquals(Generation.Result.IGNORED, g.completePropagation(8, BLOCK), "a generation that does not exist yet must not advance it either");
        }

        @Test
        void duplicateLaneCompletionIsIgnored() {
            final Generation g = bothLanes(7);

            assertEquals(Generation.Result.WAITING, g.completePropagation(7, SKY));
            assertEquals(Generation.Result.IGNORED, g.completePropagation(7, SKY));
            assertEquals(Generation.Result.PROPAGATION_DONE, g.completePropagation(7, BLOCK));
            assertEquals(Generation.Result.IGNORED, g.completePropagation(7, BLOCK));
        }

        @Test
        void edgesBeforePropagationAreIgnored() {
            final Generation g = bothLanes(7);

            assertEquals(Generation.Result.IGNORED, g.completeEdges(7, SKY));
            g.completePropagation(7, SKY);
            assertEquals(Generation.Result.IGNORED, g.completeEdges(7, SKY), "one lane propagated is not enough to start the edge phase");
        }

        /** Nether/End: no sky lane. */
        @Test
        void singleLaneWorldCompletes() {
            final Generation g = new Generation(12, BLOCK, null);

            assertEquals(Generation.Result.IGNORED, g.completePropagation(12, SKY), "a lane this world does not run must not count");
            assertEquals(Generation.Result.PROPAGATION_DONE, g.completePropagation(12, BLOCK));
            assertEquals(Generation.Result.EDGES_DONE, g.completeEdges(12, BLOCK));
        }

        @Test
        void invalidGenerationsAndLaneMasksThrow() {
            assertThrows(IllegalArgumentException.class, () -> new Generation(0, SKY, null), "0 is the not-a-generation sentinel");
            assertThrows(IllegalArgumentException.class, () -> new Generation(1, 0, null));
            assertThrows(IllegalArgumentException.class, () -> bothLanes(1).completePropagation(1, 0));
            assertThrows(IllegalArgumentException.class, () -> bothLanes(1).completePropagation(1, SKY | BLOCK), "lanes complete one at a time");
        }
    }
}
