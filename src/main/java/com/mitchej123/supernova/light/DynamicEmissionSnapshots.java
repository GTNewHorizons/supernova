package com.mitchej123.supernova.light;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.util.CoordinateUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Set;

/** Samples tile-entity emission on the main thread; workers read published values, never tile entities. */
public final class DynamicEmissionSnapshots {

    private final World world;
    private final ConcurrentHashMap<Long, Sources> chunks = new ConcurrentHashMap<>();
    private final Set<Class<?>> sampleFailuresLogged = new HashSet<>();
    private WorldLightManager manager;

    private static final class Sources {
        final Chunk chunk;
        final Int2ObjectOpenHashMap<TrackedSource> tracked = new Int2ObjectOpenHashMap<>();
        final ConcurrentHashMap<Integer, Integer> values = new ConcurrentHashMap<>();

        Sources(final Chunk chunk) {this.chunk = chunk;}
    }

    private static final class TrackedSource {
        final TileEntity tile;
        final ChunkPosition position;
        final Integer key;

        TrackedSource(final TileEntity tile) {
            this.tile = tile;
            this.position = new ChunkPosition(tile.xCoord & 15, tile.yCoord, tile.zCoord & 15);
            this.key = localKey(tile.xCoord, tile.yCoord, tile.zCoord);
        }
    }

    DynamicEmissionSnapshots(final World world) {this.world = world;}

    void setManager(final WorldLightManager manager) {this.manager = manager;}

    /** Null means no snapshot; the worker uses ordinary block emission. */
    public Integer get(final int x, final int y, final int z) {
        final Sources sources = this.chunks.get(CoordinateUtils.getChunkKey(x >> 4, z >> 4));
        return sources == null ? null : sources.values.get(localKey(x, y, z));
    }

    void registerChunk(final Chunk chunk) {
        final Sources sources = new Sources(chunk);
        for (final TileEntity tile : chunk.chunkTileEntityMap.values()) {
            track(sources, tile);
        }
        final long key = CoordinateUtils.getChunkKey(chunk.xPosition, chunk.zPosition);
        if (sources.tracked.isEmpty()) this.chunks.remove(key);
        else this.chunks.put(key, sources);
    }

    void unregisterChunk(final int cx, final int cz) {
        this.chunks.remove(CoordinateUtils.getChunkKey(cx, cz));
    }

    /** Called after a tile entity is added or removed, and before a block-change task is queued. */
    void refreshPosition(final int x, final int y, final int z) {
        final long chunkKey = CoordinateUtils.getChunkKey(x >> 4, z >> 4);
        Sources sources = this.chunks.get(chunkKey);
        if (sources == null) {
            final Chunk loaded = this.manager.getLoadedChunk(x >> 4, z >> 4);
            if (loaded == null || !isTileEmitter(loaded, x, y, z)) return;
            final TileEntity tile = loaded.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
            if (tile == null || tile.isInvalid()) return;
            sources = new Sources(loaded);
            this.chunks.put(chunkKey, sources);
            track(sources, tile);
            if (sources.tracked.isEmpty()) this.chunks.remove(chunkKey, sources);
            return;
        }
        final int key = localKey(x, y, z);
        if (!sources.tracked.containsKey(key) && !isTileEmitter(sources.chunk, x, y, z)) return;
        final TileEntity tile = sources.chunk.chunkTileEntityMap.get(new ChunkPosition(x & 15, y, z & 15));
        if (tile == null || tile.isInvalid()) {
            sources.tracked.remove(key);
            sources.values.remove(key);
        } else {
            track(sources, tile);
        }
        if (sources.tracked.isEmpty()) this.chunks.remove(chunkKey, sources);
    }

    void tileEntityChanged(final int x, final int y, final int z) {
        final Integer before = get(x, y, z);
        refreshPosition(x, y, z);
        final Integer after = get(x, y, z);
        if (before == null ? after != null : !before.equals(after)) this.manager.queueBlockChange(x, y, z);
    }

    void tick() {
        for (final Sources sources : this.chunks.values()) {
            if (sources.tracked.isEmpty()) continue;
            IntArrayList changed = null;
            final var it = sources.tracked.int2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                final Int2ObjectMap.Entry<TrackedSource> entry = it.next();
                final int key = entry.getIntKey();
                final TrackedSource source = entry.getValue();
                final TileEntity tile = source.tile;
                if (tile.isInvalid() || sources.chunk.chunkTileEntityMap.get(source.position) != tile) {
                    it.remove();
                    sources.values.remove(key);
                    if (changed == null) changed = new IntArrayList();
                    changed.add(key);
                    continue;
                }
                final int x = tile.xCoord, y = tile.yCoord, z = tile.zCoord;
                final Block block = sources.chunk.getBlock(x & 15, y, z & 15);
                if (!LightColorRegistry.isPositional(block)) {
                    it.remove();
                    sources.values.remove(key);
                    if (changed == null) changed = new IntArrayList();
                    changed.add(key);
                    continue;
                }
                final int emission = sample(block, sources.chunk.getBlockMetadata(x & 15, y, z & 15), x, y, z);
                if (emission < 0) continue;
                final Integer old = sources.values.get(source.key);
                if (old == null || old != emission) {
                    sources.values.put(source.key, emission);
                    if (changed == null) changed = new IntArrayList();
                    changed.add(key);
                }
            }
            if (changed != null) {
                for (int i = 0; i < changed.size(); ++i) {
                    final int key = changed.getInt(i);
                    this.manager.queueBlockChange((sources.chunk.xPosition << 4) | (key & 15), key >>> 8,
                        (sources.chunk.zPosition << 4) | ((key >>> 4) & 15));
                }
            }
            if (sources.tracked.isEmpty()) {
                this.chunks.remove(CoordinateUtils.getChunkKey(sources.chunk.xPosition, sources.chunk.zPosition), sources);
            }
        }
    }

    private static boolean isTileEmitter(final Chunk chunk, final int x, final int y, final int z) {
        if (y < 0 || y > 255) return false;
        final Block block = chunk.getBlock(x & 15, y, z & 15);
        final int meta = chunk.getBlockMetadata(x & 15, y, z & 15);
        return block.hasTileEntity(meta) && LightColorRegistry.isPositional(block);
    }

    private void track(final Sources sources, final TileEntity tile) {
        final int x = tile.xCoord, y = tile.yCoord, z = tile.zCoord;
        final int key = localKey(x, y, z);
        if (tile.isInvalid() || y < 0 || y > 255 || x >> 4 != sources.chunk.xPosition || z >> 4 != sources.chunk.zPosition) return;
        final Block block = sources.chunk.getBlock(x & 15, y, z & 15);
        final int meta = sources.chunk.getBlockMetadata(x & 15, y, z & 15);
        if (!LightColorRegistry.isPositional(block)) {
            sources.tracked.remove(key);
            sources.values.remove(key);
            return;
        }
        final int emission = sample(block, meta, x, y, z);
        final TrackedSource old = sources.tracked.get(key);
        final TrackedSource source;
        if (old == null || old.tile != tile) {
            source = new TrackedSource(tile);
            sources.tracked.put(key, source);
        } else {
            source = old;
        }
        if (emission >= 0) sources.values.put(source.key, emission);
        else sources.values.putIfAbsent(source.key, LightColorRegistry.getPackedEmissionNoWorld(block, meta));
    }

    private int sample(final Block block, final int meta, final int x, final int y, final int z) {
        try {
            return LightColorRegistry.getPackedEmission(this.world, block, meta, x, y, z);
        } catch (final RuntimeException e) {
            if (this.sampleFailuresLogged.add(block.getClass())) {
                Supernova.LOG.warn("Could not sample tile-dependent light at ({}, {}, {})", x, y, z, e);
            }
            return -1;
        }
    }

    private static int localKey(final int x, final int y, final int z) {
        return (y << 8) | ((z & 15) << 4) | (x & 15);
    }
}
