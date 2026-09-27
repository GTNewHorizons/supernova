package com.mitchej123.supernova.api;

import net.minecraft.world.chunk.Chunk;

/**
 * Supernova extension of {@code World}, injected at runtime.
 */
public interface ExtendedWorld {

    /**
     * Returns the chunk if loaded, {@code null} otherwise.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return the chunk, or {@code null}
     */
    Chunk supernova$getAnyChunkImmediately(int chunkX, int chunkZ);

    /**
     * Reports queued or in-flight lighting on either lane for this chunk.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return whether lighting is pending
     */
    boolean supernova$hasChunkPendingLight(int chunkX, int chunkZ);
}
