package com.mitchej123.supernova.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.chunk.Chunk;

/**
 * Writers mutate under the monitor and flag the snapshot stale; the next reader re-clones, so readers are lock-free after that.
 * Primitive-keyed on purpose: SupernovaEngine.setupCaches does 25 lookups per BFS root and a boxing map allocates a Long for each.
 */
public final class SnapshotChunkMap {

    private final Long2ObjectOpenHashMap<Chunk> map = new Long2ObjectOpenHashMap<>();
    private volatile Long2ObjectOpenHashMap<Chunk> snapshot = new Long2ObjectOpenHashMap<>();
    private volatile boolean stale;

    public synchronized void put(final long key, final Chunk value) {
        map.put(key, value);
        stale = true;
    }

    public synchronized Chunk remove(final long key) {
        final Chunk removed = map.remove(key);
        stale = true;
        return removed;
    }

    public Chunk get(final long key) {
        // stale first: a snapshot read before the flag check could predate a put that another reader has since folded in.
        return (this.stale ? this.refresh() : this.snapshot).get(key);
    }

    private synchronized Long2ObjectOpenHashMap<Chunk> refresh() {
        if (this.stale) {
            this.snapshot = this.map.clone();
            this.stale = false;
        }
        return this.snapshot;
    }
}
