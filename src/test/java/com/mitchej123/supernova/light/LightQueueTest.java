package com.mitchej123.supernova.light;

import com.mitchej123.supernova.light.engine.MCBootstrap;
import com.mitchej123.supernova.util.CoordinateUtils;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightQueueTest {

    private static final Chunk DUMMY_CHUNK = MCBootstrap.allocate(Chunk.class);

    private LightQueue queue;

    @BeforeEach
    void setup() {
        queue = new LightQueue();
    }

    @Test
    void emptyQueue() {
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
        assertNull(queue.removeFirstTask());
        assertNull(queue.removeFirstBlockChangeTask());
        assertNull(queue.removeFirstInitialLightTask());
        assertFalse(queue.hasInitialLightTask());
    }

    @Test
    void blockChangePriority() {
        queue.queueEdgeCheckAllSections(0, 0);
        queue.queueBlockChange(16, 64, 16);

        final ChunkTasks blockTask = queue.removeFirstBlockChangeTask();
        assertNotNull(blockTask);
        assertEquals(CoordinateUtils.getChunkKey(1, 1), blockTask.chunkCoordinate);
        assertNotNull(blockTask.changedPositions);
        assertFalse(blockTask.changedPositions.isEmpty());

        final ChunkTasks edgeTask = queue.removeFirstTask();
        assertNotNull(edgeTask);
        assertEquals(CoordinateUtils.getChunkKey(0, 0), edgeTask.chunkCoordinate);
    }

    @Test
    void initialLightPriority() {
        queue.queueEdgeCheckAllSections(0, 0);
        queue.queueChunkLight(1, 1, DUMMY_CHUNK, null, 1L);

        final ChunkTasks lightTask = queue.removeFirstInitialLightTask();
        assertNotNull(lightTask);
        assertEquals(CoordinateUtils.getChunkKey(1, 1), lightTask.chunkCoordinate);

        assertNull(queue.removeFirstInitialLightTask());
        assertNotNull(queue.removeFirstTask());
    }

    @Test
    void removeFirstTaskFIFO() {
        queue.queueEdgeCheckAllSections(0, 0);
        queue.queueEdgeCheckAllSections(1, 1);
        queue.queueEdgeCheckAllSections(2, 2);

        assertEquals(CoordinateUtils.getChunkKey(0, 0), queue.removeFirstTask().chunkCoordinate);
        assertEquals(CoordinateUtils.getChunkKey(1, 1), queue.removeFirstTask().chunkCoordinate);
        assertEquals(CoordinateUtils.getChunkKey(2, 2), queue.removeFirstTask().chunkCoordinate);
        assertNull(queue.removeFirstTask());
    }

    @Test
    void coalescingBlockChanges() {
        queue.queueBlockChange(5, 64, 7);
        queue.queueBlockChange(10, 80, 3);

        assertEquals(1, queue.size());
        final ChunkTasks task = queue.removeFirstTask();
        assertNotNull(task.changedPositions);
        assertEquals(2, task.changedPositions.size());

        final int pos1 = (5 & 15) | ((7 & 15) << 4) | (64 << 8);
        final int pos2 = (10 & 15) | ((3 & 15) << 4) | (80 << 8);
        assertTrue(task.changedPositions.contains(pos1));
        assertTrue(task.changedPositions.contains(pos2));
    }

    @Test
    void removeChunk() {
        queue.queueBlockChange(5, 64, 7);
        assertTrue(queue.hasPendingWork(0, 0));

        final ChunkTasks removed = queue.removeChunk(0, 0);
        assertNotNull(removed);
        assertNull(removed.initialLightChunk, "block-change-only task must not count as a dropped initial light");
        assertNull(queue.removeChunk(0, 0));
        assertFalse(queue.hasPendingWork(0, 0));
        assertTrue(queue.isEmpty());
    }

    @Test
    void removeChunkReturnsQueuedInitialLightTask() {
        queue.queueChunkLight(0, 0, DUMMY_CHUNK, null, 1L);

        final ChunkTasks removed = queue.removeChunk(0, 0);
        assertNotNull(removed);
        assertNotNull(removed.initialLightChunk, "queued-unstarted initial light must be visible to the caller");
        assertNull(queue.removeChunk(0, 0));
    }

    @Test
    void dequeuedTaskStaysVisibleUntilCompleted() {
        queue.queueBlockChange(5, 64, 7);
        final ChunkTasks task = queue.removeFirstBlockChangeTask();
        assertNotNull(task);

        assertTrue(queue.hasPendingWork(0, 0), "a task a worker is running is still work on that chunk");
        assertNotNull(queue.pendingWorkFuture(CoordinateUtils.getChunkKey(0, 0)));
        assertNull(queue.removeChunk(0, 0), "task already dequeued by a worker must not be reported as dropped");

        queue.completeTask(task);
        assertFalse(queue.hasPendingWork(0, 0));
        assertNull(queue.pendingWorkFuture(CoordinateUtils.getChunkKey(0, 0)));
    }

    @Test
    void edgeOnlyWorkRemainsPendingUntilComplete() {
        queue.queueEdgeCheckAllSections(0, 0);
        final long key = CoordinateUtils.getChunkKey(0, 0);

        assertTrue(queue.hasPendingWork(0, 0));
        assertNotNull(queue.pendingWorkFuture(key));
        final ChunkTasks task = queue.removeFirstTask();
        assertNotNull(queue.pendingWorkFuture(key));
        queue.completeTask(task);
        assertNull(queue.pendingWorkFuture(key));
    }

    @Test
    void olderGenerationDoesNotOverwriteNewer() {
        queue.queueChunkLight(0, 0, DUMMY_CHUNK, null, 5L);
        queue.queueChunkLight(0, 0, DUMMY_CHUNK, null, 3L);

        final ChunkTasks task = queue.removeFirstInitialLightTask();
        assertEquals(5L, task.lightGeneration, "a stale relight must not clobber the live one");
    }

    @Test
    void newPropagationSupersedesAQueuedEdgePass() {
        queue.queueChunkLight(0, 0, DUMMY_CHUNK, null, 1L);
        queue.removeFirstInitialLightTask();
        queue.queueInitialLightEdges(0, 0, 1L, 0);
        queue.queueChunkLight(0, 0, DUMMY_CHUNK, null, 2L);

        final ChunkTasks task = queue.removeFirstInitialLightTask();
        assertEquals(2L, task.lightGeneration);
        assertFalse(task.edgePass, "the newer propagation will re-drive its own edge pass");
    }

    @Test
    void requeueIncrementsAttempts() {
        queue.requeueChunkLight(0, 0, DUMMY_CHUNK, null, 1L, 0);
        ChunkTasks task = queue.removeFirstInitialLightTask();
        assertEquals(1, task.attempts);

        queue.requeueChunkLight(0, 0, DUMMY_CHUNK, null, 1L, 2);
        task = queue.removeFirstInitialLightTask();
        assertEquals(3, task.attempts);
    }

    @Test
    void mixedTaskCoalescing() {
        queue.queueBlockChange(5, 64, 7);
        queue.queueChunkLight(0, 0, DUMMY_CHUNK, null, 1L);
        queue.queueEdgeCheckAllSections(0, 0);

        assertEquals(1, queue.size());
        final ChunkTasks task = queue.removeFirstTask();
        assertNotNull(task.changedPositions);
        assertNotNull(task.initialLightChunk);
        assertNotNull(task.queuedEdgeChecks);
    }

    @Test
    void hasInitialLightTaskTracksQueuedTasksOnly() {
        assertFalse(queue.hasInitialLightTask());

        queue.queueChunkLight(0, 0, DUMMY_CHUNK, null, 1L);
        assertTrue(queue.hasInitialLightTask());

        assertNotNull(queue.removeFirstInitialLightTask());
        assertFalse(queue.hasInitialLightTask(), "a dequeued task is no longer queued work");

        queue.queueChunkLight(1, 1, DUMMY_CHUNK, null, 2L);
        assertTrue(queue.hasInitialLightTask());
        assertNotNull(queue.removeChunk(1, 1));
        assertFalse(queue.hasInitialLightTask(), "an unload drops the queued initial light");
    }

    @Test
    void removingAMergedTaskClearsBothCounters() {
        queue.queueBlockChange(5, 64, 7);
        queue.queueChunkLight(0, 0, DUMMY_CHUNK, null, 1L);
        assertEquals(1, queue.size());

        final ChunkTasks task = queue.removeFirstBlockChangeTask();
        assertNotNull(task);
        assertNotNull(task.initialLightChunk);

        assertFalse(queue.hasInitialLightTask());
        assertNull(queue.removeFirstInitialLightTask());
        assertNull(queue.removeFirstBlockChangeTask());
        assertTrue(queue.isEmpty());
    }
}
