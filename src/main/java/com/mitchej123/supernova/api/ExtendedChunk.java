package com.mitchej123.supernova.api;

/**
 * Supernova extension of {@code Chunk}, injected at runtime.
 */
public interface ExtendedChunk {

    /** Returns true when this chunk's lighting is complete. */
    boolean isLightReady();

    /** Returns true when neighbors can seed from this chunk, even if edge checks remain. */
    boolean isLightUsable();
}
