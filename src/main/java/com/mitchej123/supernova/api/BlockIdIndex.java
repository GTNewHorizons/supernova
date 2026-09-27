package com.mitchej123.supernova.api;

import net.minecraft.block.Block;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

final class BlockIdIndex {

    private static final int[][] NO_ENTRIES = new int[0][];

    private final IdentityHashMap<Block, int[]> master = new IdentityHashMap<>();
    private volatile int[][] byId = NO_ENTRIES;

    void put(final Block block, final int[] entry) {
        this.master.put(block, entry);
        final int id = Block.getIdFromBlock(block);
        if (id < 0) return;

        int[][] table = this.byId;
        if (id >= table.length) table = Arrays.copyOf(table, Math.max(id + 1, table.length * 2));
        table[id] = entry;
        this.byId = table;
    }

    int[] entryFor(final int blockId) {
        final int[][] table = this.byId;
        return (blockId >= 0 && blockId < table.length) ? table[blockId] : null;
    }

    int lookup(final int blockId, final int meta) {
        return lookup(entryFor(blockId), meta);
    }

    static int lookup(final int[] entry, final int meta) {
        if (entry == null) return -1;
        if (entry.length == 1) return entry[0] - 1;
        if (meta >= 0 && meta < entry.length) {
            final int v = entry[meta];
            if (v != 0) return v - 1;
        }
        return -1;
    }

    void putMeta(final Block block, final int meta, final int valuePlusOne) {
        if (meta < 0) {
            throw new IllegalArgumentException("meta must be >= 0, got " + meta);
        }
        final int[] existing = this.master.get(block);
        final int[] entry;
        if (existing == null) {
            entry = new int[Math.max(meta + 1, 16)];
        } else if (existing.length == 1) {
            entry = new int[Math.max(meta + 1, 16)];
            Arrays.fill(entry, existing[0]);
        } else {
            entry = Arrays.copyOf(existing, Math.max(existing.length, meta + 1));
        }
        entry[meta] = valuePlusOne;
        put(block, entry);
    }

    boolean hasEntry(final int blockId) {
        return entryFor(blockId) != null;
    }

    boolean isMetaSensitive(final int blockId) {
        final int[] entry = entryFor(blockId);
        return entry != null && entry.length > 1;
    }

    void rebuild() {
        int maxId = -1;
        for (final Block block : this.master.keySet()) {
            maxId = Math.max(maxId, Block.getIdFromBlock(block));
        }
        final int[][] table = maxId < 0 ? NO_ENTRIES : new int[maxId + 1][];
        for (final Map.Entry<Block, int[]> e : this.master.entrySet()) {
            final int id = Block.getIdFromBlock(e.getKey());
            if (id >= 0) table[id] = e.getValue();
        }
        this.byId = table;
    }

    void forEach(final BiConsumer<Block, int[]> consumer) {
        for (final Map.Entry<Block, int[]> e : this.master.entrySet()) {
            consumer.accept(e.getKey(), e.getValue());
        }
    }

    void clear() {
        this.master.clear();
        this.byId = NO_ENTRIES;
    }

    static final class Cache {

        static final Cache EMPTY = new Cache(new int[0], new int[0][]);

        private final int[] uniform;
        private final int[][] perMeta;

        Cache(final int[] uniform, final int[][] perMeta) {
            this.uniform = uniform;
            this.perMeta = perMeta;
        }

        int lookup(final int blockId, final int meta, final int miss) {
            final int[] flat = this.uniform;
            if (blockId < 0 || blockId >= flat.length) return miss;
            final int cached = flat[blockId];
            if (cached != miss) return cached;
            final int[][] byMeta = this.perMeta;
            final int[] entry = blockId < byMeta.length ? byMeta[blockId] : null;
            if (entry != null && meta >= 0 && meta < entry.length) return entry[meta];
            return miss;
        }
    }
}
