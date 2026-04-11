package com.mitchej123.supernova.light;

import com.mitchej123.supernova.util.CoordinateUtils;
import com.mitchej123.supernova.util.WorldUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.Semaphore;

/**
 * Synchronized insertion-ordered queue of per-chunk light tasks. Main thread enqueues; light worker thread dequeues via {@link WorldLightManager}.
 */
public final class LightQueue {

    private final Long2ObjectLinkedOpenHashMap<ChunkTasks> tasksByChunk = new Long2ObjectLinkedOpenHashMap<>();
    private final Semaphore workAvailable = new Semaphore(0);
    private LightStats stats;

    void setStats(final LightStats stats) {
        this.stats = stats;
    }

    private ChunkTasks getOrCreate(final long key) {
        ChunkTasks tasks = this.tasksByChunk.get(key);
        if (tasks == null) {
            tasks = new ChunkTasks(key);
            this.tasksByChunk.put(key, tasks);
            if (this.stats != null) {
                this.stats.chunksQueued.incrementAndGet();
            }
        }
        return tasks;
    }

    public synchronized void queueBlockChange(final int x, final int y, final int z) {
        final long key = CoordinateUtils.getChunkKey(x >> 4, z >> 4);
        final ChunkTasks tasks = this.getOrCreate(key);
        if (tasks.changedPositions == null) {
            tasks.changedPositions = new IntOpenHashSet();
        }
        tasks.changedPositions.add((x & 15) | ((z & 15) << 4) | (y << 8));
        this.workAvailable.release(1);
    }

    public synchronized void queueSectionChange(final int cx, final int sectionY, final int cz, final boolean empty) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        if (tasks.changedSectionSet == null) {
            tasks.changedSectionSet = new Boolean[WorldUtil.getTotalSections()];
        }
        tasks.changedSectionSet[sectionY] = empty;
        this.workAvailable.release(1);
    }

    public synchronized void queueChunkLight(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        tasks.initialLightChunk = chunk;
        tasks.initialLightEmptySections = emptySections;
        this.workAvailable.release(1);
    }

    /**
     * Re-queue a chunk for full relighting after a queue overflow. Increments the relight attempt counter.
     */
    public synchronized void requeueChunkLight(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections, final int previousAttempts) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        tasks.initialLightChunk = chunk;
        tasks.initialLightEmptySections = emptySections;
        tasks.relightAttempts = previousAttempts + 1;
        this.workAvailable.release(1);
    }

    public synchronized void queueEdgeCheck(final int cx, final int cz, final int sectionY, final boolean isSky) {
        if (!isLightSectionInRange(sectionY)) {
            return;
        }
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        if (isSky) {
            if (tasks.queuedEdgeChecksSky == null) {
                tasks.queuedEdgeChecksSky = new IntOpenHashSet();
            }
            tasks.queuedEdgeChecksSky.add(sectionY);
        } else {
            if (tasks.queuedEdgeChecksBlock == null) {
                tasks.queuedEdgeChecksBlock = new IntOpenHashSet();
            }
            tasks.queuedEdgeChecksBlock.add(sectionY);
        }
        this.workAvailable.release(1);
    }

    /**
     * Queue edge checks for all light sections on a chunk.
     */
    public synchronized void queueEdgeCheckAllSections(final int cx, final int cz, final boolean isSky) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final ChunkTasks tasks = this.getOrCreate(key);
        if (isSky) {
            if (tasks.queuedEdgeChecksSky == null) {
                tasks.queuedEdgeChecksSky = new IntOpenHashSet();
            }
            for (int s = WorldUtil.getMinLightSection(); s <= WorldUtil.getMaxLightSection(); ++s) {
                tasks.queuedEdgeChecksSky.add(s);
            }
        } else {
            if (tasks.queuedEdgeChecksBlock == null) {
                tasks.queuedEdgeChecksBlock = new IntOpenHashSet();
            }
            for (int s = WorldUtil.getMinLightSection(); s <= WorldUtil.getMaxLightSection(); ++s) {
                tasks.queuedEdgeChecksBlock.add(s);
            }
        }
        this.workAvailable.release(1);
    }

    private static boolean isLightSectionInRange(final int sectionY) {
        return sectionY >= WorldUtil.getMinLightSection() && sectionY <= WorldUtil.getMaxLightSection();
    }

    /**
     * Remove and return the first task that has initial lighting work. Used to prioritize initial lights over edge-check-only tasks.
     */
    public synchronized ChunkTasks removeFirstInitialLightTask() {
        for (final var it = this.tasksByChunk.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            final var entry = it.next();
            final ChunkTasks task = entry.getValue();
            if (task.initialLightChunk != null) {
                it.remove();
                return task;
            }
        }
        return null;
    }

    public synchronized ChunkTasks removeFirstTask() {
        if (this.tasksByChunk.isEmpty()) {
            return null;
        }
        final long key = this.tasksByChunk.firstLongKey();
        return this.tasksByChunk.remove(key);
    }

    /**
     * Remove and return the first task that has block changes (changedPositions non-empty), skipping initial-lighting-only tasks. Returns null if no such task
     * exists. Used to prioritize player block placement/breaking over chunk loading.
     */
    public synchronized ChunkTasks removeFirstBlockChangeTask() {
        for (final var it = this.tasksByChunk.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            final var entry = it.next();
            final ChunkTasks task = entry.getValue();
            if (task.changedPositions != null && !task.changedPositions.isEmpty()) {
                it.remove();
                return task;
            }
        }
        return null;
    }

    /**
     * Check whether any queued task has initial lighting work. Used for priority preemption -- lets initial light tasks interrupt long-running edge check phases.
     */
    public synchronized boolean hasInitialLightTask() {
        for (final var entry : this.tasksByChunk.values()) {
            if (entry.initialLightChunk != null) return true;
        }
        return false;
    }

    public synchronized void removeChunk(final int cx, final int cz) {
        this.tasksByChunk.remove(CoordinateUtils.getChunkKey(cx, cz));
    }

    public synchronized boolean hasPendingWork(final int cx, final int cz) {
        return this.tasksByChunk.containsKey(CoordinateUtils.getChunkKey(cx, cz));
    }

    public synchronized boolean isEmpty() {
        return this.tasksByChunk.isEmpty();
    }

    public synchronized int size() {
        return this.tasksByChunk.size();
    }

    /**
     * Block until work is available. Drains all excess permits so we process everything per wake.
     */
    void waitForWork() throws InterruptedException {
        this.workAvailable.acquire();
        this.workAvailable.drainPermits();
    }

    /**
     * Wake the worker thread (e.g., for shutdown).
     */
    void wakeUp() {
        this.workAvailable.release(1);
    }
}
