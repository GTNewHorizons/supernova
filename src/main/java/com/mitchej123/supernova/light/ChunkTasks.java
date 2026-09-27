package com.mitchej123.supernova.light;

import com.google.common.util.concurrent.SettableFuture;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.world.chunk.Chunk;

/**
 * Per-chunk batched light work. Accumulates block changes, section changes, initial lighting requests, and edge checks until processed by
 * {@link WorldLightManager}.
 */
public final class ChunkTasks {

    public final long chunkCoordinate;
    public IntOpenHashSet changedPositions;

    /** Tri-state: null = no change, TRUE = empty, FALSE = non-empty. */
    public Boolean[] changedSectionSet;

    public Chunk initialLightChunk;
    public Boolean[] initialLightEmptySections;

    /** One set, not one per lane: a LightQueue is bound to a single lane, so a sky task never carries block edge checks. */
    public IntOpenHashSet queuedEdgeChecks;

    /** Which relight this batch belongs to, 0 for ordinary work; on the task so a worker can test currency without the coordinator lock. */
    public long lightGeneration;
    /** false = propagation, true = edge reconciliation; only meaningful when lightGeneration is non-zero. */
    public boolean edgePass;

    public final long enqueueTimeNs;
    public int attempts;

    public final SettableFuture<Void> onComplete = SettableFuture.create();

    public ChunkTasks(final long chunkCoordinate) {
        this.chunkCoordinate = chunkCoordinate;
        this.enqueueTimeNs = System.nanoTime();
    }

    boolean hasBlockChanges() {
        return this.changedPositions != null && !this.changedPositions.isEmpty();
    }

}
