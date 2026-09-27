package com.mitchej123.supernova.light;

import com.mitchej123.supernova.util.CoordinateUtils;
import com.mitchej123.supernova.util.WorldUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import com.google.common.util.concurrent.SettableFuture;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.Semaphore;

/**
 * Insertion-ordered per-chunk task queue with one producer (main thread) and one consumer (the lane's worker).
 */
public final class LightQueue {

    private final Long2ObjectLinkedOpenHashMap<ChunkTasks> tasksByChunk = new Long2ObjectLinkedOpenHashMap<>();
    /** The one dequeued but not yet finished task: a queue has a single consumer and a task is completed before the next dequeue. */
    private ChunkTasks inFlight;
    private final Semaphore workAvailable = new Semaphore(0);
    /** Keys are pushed on the false -> true flip only and never removed, so a key may name a gone or no-longer-qualifying task; readers skip those. */
    private final LongArrayFIFOQueue blockChangeKeys = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue initialLightKeys = new LongArrayFIFOQueue();
    private LightStats stats;

    void setStats(final LightStats stats) {
        this.stats = stats;
    }

    /** Ordering, not staleness: propagation uses > so an overflow retry can replace its own generation, edges use >= so they never jump ahead of it. */
    private boolean isSuperseded(final long key, final long generation, final boolean edgePass) {
        final ChunkTasks existing = this.tasksByChunk.get(key);
        if (existing == null || existing.lightGeneration == 0L) return false;
        return edgePass ? existing.lightGeneration >= generation : existing.lightGeneration > generation;
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
        if (!tasks.hasBlockChanges()) this.blockChangeKeys.enqueue(key);
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
        tasks.changedSectionSet[sectionY - WorldUtil.getMinSection()] = empty;
        this.workAvailable.release(1);
    }

    public synchronized void queueChunkLight(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections, final long generation) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        // Guard before getOrCreate: a stranded empty task keeps hasPendingWork true and holds the save gate closed.
        if (isSuperseded(key, generation, false)) return;
        final ChunkTasks tasks = this.getOrCreate(key);
        if (tasks.initialLightChunk == null) this.initialLightKeys.enqueue(key);
        tasks.initialLightChunk = chunk;
        tasks.initialLightEmptySections = emptySections;
        tasks.lightGeneration = generation;
        // getOrCreate hands back the same task across generations, so the retry budget has to be reset explicitly.
        tasks.edgePass = false;
        tasks.attempts = 0;
        this.workAvailable.release(1);
    }

    /** Retry the same generation after a BFS queue overflow. */
    public synchronized void requeueChunkLight(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections, final long generation,
        final int previousAttempts) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        if (isSuperseded(key, generation, false)) return;
        final ChunkTasks tasks = this.getOrCreate(key);
        if (tasks.initialLightChunk == null) this.initialLightKeys.enqueue(key);
        tasks.initialLightChunk = chunk;
        tasks.initialLightEmptySections = emptySections;
        tasks.lightGeneration = generation;
        tasks.edgePass = false;
        tasks.attempts = Math.max(tasks.attempts, previousAttempts + 1);
        this.workAvailable.release(1);
    }

    /** Edge reconciliation for a generation whose propagation finished on every lane. */
    public synchronized void queueInitialLightEdges(final int cx, final int cz, final long generation, final int attempts) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        if (isSuperseded(key, generation, true)) return;
        final ChunkTasks tasks = this.getOrCreate(key);
        tasks.lightGeneration = generation;
        tasks.edgePass = true;
        tasks.attempts = attempts;
        addAllSections(tasks);
        this.workAvailable.release(1);
    }

    public synchronized void queueEdgeCheckAllSections(final int cx, final int cz) {
        addAllSections(this.getOrCreate(CoordinateUtils.getChunkKey(cx, cz)));
        this.workAvailable.release(1);
    }

    private static void addAllSections(final ChunkTasks tasks) {
        if (tasks.queuedEdgeChecks == null) tasks.queuedEdgeChecks = new IntOpenHashSet();
        for (int s = WorldUtil.getMinLightSection(); s <= WorldUtil.getMaxLightSection(); ++s) {
            tasks.queuedEdgeChecks.add(s);
        }
    }

    public synchronized ChunkTasks removeFirstInitialLightTask() {
        while (!this.initialLightKeys.isEmpty()) {
            final long key = this.initialLightKeys.dequeueLong();
            final ChunkTasks task = this.tasksByChunk.get(key);
            if (task == null || task.initialLightChunk == null) continue;
            this.tasksByChunk.remove(key);
            onTaskDequeued(task);
            return task;
        }
        return null;
    }

    public synchronized ChunkTasks removeFirstTask() {
        if (this.tasksByChunk.isEmpty()) {
            return null;
        }
        final long key = this.tasksByChunk.firstLongKey();
        final ChunkTasks task = this.tasksByChunk.remove(key);
        onTaskDequeued(task);
        return task;
    }

    /** Skips initial-lighting-only tasks: player block placement/breaking outranks chunk loading. */
    public synchronized ChunkTasks removeFirstBlockChangeTask() {
        while (!this.blockChangeKeys.isEmpty()) {
            final long key = this.blockChangeKeys.dequeueLong();
            final ChunkTasks task = this.tasksByChunk.get(key);
            if (task == null || !task.hasBlockChanges()) continue;
            this.tasksByChunk.remove(key);
            onTaskDequeued(task);
            return task;
        }
        return null;
    }

    /** Lets an arriving initial light preempt the edge check phase. */
    public synchronized boolean hasInitialLightTask() {
        while (!this.initialLightKeys.isEmpty()) {
            final ChunkTasks task = this.tasksByChunk.get(this.initialLightKeys.firstLong());
            if (task != null && task.initialLightChunk != null) return true;
            this.initialLightKeys.dequeueLong();
        }
        return false;
    }

    public ChunkTasks removeChunk(final int cx, final int cz) {
        final ChunkTasks task;
        synchronized (this) {
            task = this.tasksByChunk.remove(CoordinateUtils.getChunkKey(cx, cz));
        }
        if (task != null) task.onComplete.set(null);
        return task;
    }

    private void onTaskDequeued(final ChunkTasks task) {
        if (task != null) this.inFlight = task;
    }

    /** Called from the worker's finally, so a throwing task cannot strand its chunk. */
    void completeTask(final ChunkTasks task) {
        synchronized (this) {
            if (this.inFlight == task) this.inFlight = null;
        }
        task.onComplete.set(null);
    }

    public synchronized boolean hasPendingWork(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        return this.tasksByChunk.containsKey(key) || (this.inFlight != null && this.inFlight.chunkCoordinate == key);
    }

    public synchronized SettableFuture<Void> pendingWorkFuture(final long key) {
        final ChunkTasks running = this.inFlight;
        if (running != null && running.chunkCoordinate == key) return running.onComplete;
        final ChunkTasks queued = this.tasksByChunk.get(key);
        return queued == null ? null : queued.onComplete;
    }

    public synchronized boolean isEmpty() {
        return this.tasksByChunk.isEmpty();
    }

    public synchronized int size() {
        return this.tasksByChunk.size();
    }

    void waitForWork() throws InterruptedException {
        this.workAvailable.acquire();
        this.workAvailable.drainPermits();
    }

    void wakeUp() {
        this.workAvailable.release(1);
    }
}
