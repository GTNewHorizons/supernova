package com.mitchej123.supernova.api;

/** Mixed into {@code ExtendedBlockStorage}. */
public interface ExtendedSection {

    /** Vanilla's original {@code isEmpty()}: no blocks. {@code isEmpty()} itself now also requires trivial light. */
    boolean supernova$hasNoBlocks();

    /**
     * Updates occupancy after a full write; caller holds the section lock.
     *
     * @param sky true for sky, false for block light
     * @param nonTrivial whether the array differs from trivial light (sky {@code 0xFF}, block 0)
     */
    void supernova$setLightNonTrivial(boolean sky, boolean nonTrivial);

    /**
     * Updates occupancy after a partial write; caller holds the section lock.
     *
     * @param sky true for sky, false for block light
     * @param rangeNonTrivial whether the written range contains nontrivial light
     * @param data full array, scanned only when a nontrivial flag may clear
     * @param trivialByte trivial byte value for the selected light lane
     */
    void supernova$updateLightNonTrivial(boolean sky, boolean rangeNonTrivial, byte[] data, int trivialByte);
}
