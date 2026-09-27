package com.mitchej123.supernova.light;

import com.google.common.util.concurrent.SettableFuture;
import com.mitchej123.supernova.util.CoordinateUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;

/**
 * Publication rests on map membership: a generation flips ready only inside the critical section that confirmed it is still live for its key, removing itself there.
 * Lock order is coordinator -> LightQueue; the queue calls back into nothing.
 */
final class InitialLightCoordinator {

    static final int SKY = 1;
    static final int BLOCK = 1 << 1;

    private final Object lock = new Object();
    private final Long2ObjectOpenHashMap<Generation> byChunk = new Long2ObjectOpenHashMap<>();
    private final int requiredLanes;
    private long nextGeneration;

    private final LightQueue skyQueue;
    private final LightQueue blockQueue;
    private final Publisher publisher;
    private final LongFunction<Chunk> loadedChunks;

    InitialLightCoordinator(final LightQueue skyQueue, final LightQueue blockQueue, final Publisher publisher, final LongFunction<Chunk> loadedChunks) {
        this.skyQueue = skyQueue;
        this.blockQueue = blockQueue;
        this.publisher = publisher;
        this.loadedChunks = loadedChunks;
        this.requiredLanes = (skyQueue != null ? SKY : 0) | (blockQueue != null ? BLOCK : 0);
    }

    /** Starts a fresh generation, superseding any live one for this chunk. */
    Generation queue(final int cx, final int cz, final Chunk chunk, final Boolean[] emptySections) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final Generation generation;
        final Generation superseded;

        synchronized (this.lock) {
            generation = new Generation(++this.nextGeneration, this.requiredLanes, chunk);
            superseded = this.byChunk.put(key, generation);
            // Flags cleared and both lanes queued under the lock, so no queue sees a newer generation before an older insertion.
            this.publisher.beginGeneration(chunk);
            if (this.skyQueue != null) this.skyQueue.queueChunkLight(cx, cz, chunk, emptySections, generation.generation);
            if (this.blockQueue != null) this.blockQueue.queueChunkLight(cx, cz, chunk, emptySections, generation.generation);
        }

        if (superseded != null) superseded.finish(false);
        return generation;
    }

    /** Drops the live generation for a chunk that is unloading. */
    void removeChunk(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        final Generation dropped;
        synchronized (this.lock) {
            dropped = this.byChunk.remove(key);
            if (this.skyQueue != null) this.skyQueue.removeChunk(cx, cz);
            if (this.blockQueue != null) this.blockQueue.removeChunk(cx, cz);
        }
        if (dropped != null) dropped.finish(false);
    }

    void completePropagation(final ChunkTasks task, final int lane) {
        if (task.lightGeneration <= 0L || task.edgePass) return;

        final Generation generation;
        final Generation failed;
        synchronized (this.lock) {
            generation = this.byChunk.get(task.chunkCoordinate);
            if (generation == null) return;
            if (generation.completePropagation(task.lightGeneration, lane) != Generation.Result.PROPAGATION_DONE) return;

            if (this.loadedChunks.apply(task.chunkCoordinate) != generation.chunk) {
                this.byChunk.remove(task.chunkCoordinate);
                failed = generation;
            } else {
                this.publisher.markUsable(generation.chunk);
                final int cx = CoordinateUtils.getChunkX(task.chunkCoordinate);
                final int cz = CoordinateUtils.getChunkZ(task.chunkCoordinate);
                if (this.skyQueue != null) this.skyQueue.queueInitialLightEdges(cx, cz, generation.generation, 0);
                if (this.blockQueue != null) this.blockQueue.queueInitialLightEdges(cx, cz, generation.generation, 0);
                failed = null;
            }
        }
        if (failed != null) failed.finish(false);
    }

    void completeEdges(final ChunkTasks task, final int lane) {
        if (task.lightGeneration <= 0L || !task.edgePass) return;

        final Generation generation;
        synchronized (this.lock) {
            generation = this.byChunk.get(task.chunkCoordinate);
            if (generation == null) return;
            if (generation.completeEdges(task.lightGeneration, lane) != Generation.Result.EDGES_DONE) return;
        }

        // Outside the lock: whole-chunk copy. A superseding generation is caught by the re-check below and overwrites these bytes.
        this.publisher.syncToVanilla(generation.chunk);

        boolean published = false;
        synchronized (this.lock) {
            if (this.byChunk.get(task.chunkCoordinate) == generation) {
                this.byChunk.remove(task.chunkCoordinate);
                if (this.loadedChunks.apply(task.chunkCoordinate) == generation.chunk) {
                    this.publisher.markReady(generation.chunk);
                    published = true;
                }
            }
        }
        generation.finish(published);
    }

    boolean hasPending(final long key) {
        synchronized (this.lock) {
            return this.byChunk.containsKey(key);
        }
    }

    SettableFuture<Void> pendingFuture(final long key) {
        synchronized (this.lock) {
            final Generation generation = this.byChunk.get(key);
            return generation == null ? null : generation.done;
        }
    }

    /** Lane countdown for one relight: every lane finishes propagation before any lane starts edges, and completions from a superseded relight are dropped. */
    static final class Generation {

        enum Result {
            /** Stale generation, wrong lane, duplicate, or edges before propagation finished. */
            IGNORED,
            WAITING,
            PROPAGATION_DONE,
            EDGES_DONE
        }

        final long generation;
        final Chunk chunk;
        final SettableFuture<Void> done = SettableFuture.create();
        volatile boolean published;

        private final int requiredLanes;
        private int propagatedLanes;
        private int edgeLanes;
        private final AtomicBoolean finished = new AtomicBoolean();

        Generation(final long generation, final int requiredLanes, final Chunk chunk) {
            if (generation <= 0L) {
                throw new IllegalArgumentException("generation must be positive, got " + generation);
            }
            if (requiredLanes == 0 || (requiredLanes & ~(SKY | BLOCK)) != 0) {
                throw new IllegalArgumentException("invalid lane mask: " + requiredLanes);
            }
            this.generation = generation;
            this.requiredLanes = requiredLanes;
            this.chunk = chunk;
        }

        /** Lock-free on purpose: Guava runs listeners on the completing thread, and the countdown methods below run under the coordinator lock. */
        void finish(final boolean published) {
            if (!this.finished.compareAndSet(false, true)) return;
            this.published = published;
            this.done.set(null);
        }

        synchronized Result completePropagation(final long taskGeneration, final int lane) {
            if (!accepts(taskGeneration, lane) || (this.propagatedLanes & lane) != 0) {
                return Result.IGNORED;
            }
            this.propagatedLanes |= lane;
            return this.propagatedLanes == this.requiredLanes ? Result.PROPAGATION_DONE : Result.WAITING;
        }

        synchronized Result completeEdges(final long taskGeneration, final int lane) {
            if (!accepts(taskGeneration, lane) || this.propagatedLanes != this.requiredLanes || (this.edgeLanes & lane) != 0) {
                return Result.IGNORED;
            }
            this.edgeLanes |= lane;
            return this.edgeLanes == this.requiredLanes ? Result.EDGES_DONE : Result.WAITING;
        }

        private boolean accepts(final long taskGeneration, final int lane) {
            if (lane != SKY && lane != BLOCK) {
                throw new IllegalArgumentException("lane must be exactly SKY or BLOCK, got " + lane);
            }
            return taskGeneration == this.generation && (lane & this.requiredLanes) != 0;
        }
    }

    /** An interface rather than a direct SupernovaChunk cast, so the coordinator needs no mixin-applied chunk. */
    interface Publisher {

        /** A new generation has started: the chunk is neither usable nor ready until it finishes. */
        void beginGeneration(Chunk chunk);

        /** Propagation is done on every lane; neighbors may now seed from this chunk. */
        void markUsable(Chunk chunk);

        /** Runs outside the coordinator lock: a per-section memcpy over the whole chunk. */
        void syncToVanilla(Chunk chunk);

        /** Edges are reconciled on every lane; the chunk may now be saved and sent. */
        void markReady(Chunk chunk);
    }
}
