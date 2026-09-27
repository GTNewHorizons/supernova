package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.PackedColorLight;
import net.minecraft.world.chunk.Chunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Notify index == section cache index (both x + 5*z + 25*y). */
class BlockEnginePublishTest {

    private TestableBlockEngine engine;

    @BeforeEach
    void setup() {
        engine = new TestableBlockEngine(MCBootstrap.createStubWorld(true));
        BFSTestHelper.setupCenter(engine);
    }

    private static TestableBlockEngine serverEngine() {
        return BFSTestHelper.centeredWithAir(new TestableBlockEngine(MCBootstrap.getServerWorld()));
    }

    private static Chunk newTrackedChunk(TestableBlockEngine engine) {
        final Chunk chunk = new Chunk(MCBootstrap.getServerWorld(), 0, 0);
        chunk.isModified = false;
        engine.putChunkInCache(0, 0, chunk);
        return chunk;
    }

    private int[] marked() {
        final boolean[] cache = engine.getNotifyUpdateCache();
        int n = 0;
        for (boolean b : cache) if (b) n++;
        final int[] out = new int[n];
        int w = 0;
        for (int i = 0; i < cache.length; i++) if (cache[i]) out[w++] = i;
        return out;
    }

    private void clearMarks() {
        Arrays.fill(engine.getNotifyUpdateCache(), false);
    }

    @Test
    void interiorChangeMarksOnlyOwnSection() {
        final int idx = BFSTestHelper.sectionIndex(engine, 0, 4, 0);

        engine.postLightUpdate(idx, BFSTestHelper.localIndex(5, 5, 5));

        assertArrayEquals(new int[] { idx }, marked(), "interior change marks only its own section");
    }

    @Test
    void xFaceChangeMarksNegativeXNeighbor() {
        final int idx = BFSTestHelper.sectionIndex(engine, 0, 4, 0);

        engine.postLightUpdate(idx, BFSTestHelper.localIndex(0, 5, 5));

        assertArrayEquals(new int[] { idx - 1, idx }, marked(), "local x==0 also marks the -x neighbor");
    }

    @Test
    void maxCornerMarksEightSections() {
        final int idx = BFSTestHelper.sectionIndex(engine, 0, 4, 0);

        engine.postLightUpdate(idx, BFSTestHelper.localIndex(15, 15, 15));

        // +x is +1, +z is +5, +y is +25
        final int[] expected = new int[] { idx, idx + 1, idx + 5, idx + 6, idx + 25, idx + 26, idx + 30, idx + 31 };
        Arrays.sort(expected);
        assertArrayEquals(expected, marked(), "max corner marks the 2x2x2 block toward +x/+y/+z");
    }

    @Test
    void xEdgeOfCacheClampsInBounds() {
        final int idx = BFSTestHelper.sectionIndex(engine, -2, 4, 0);
        assertEquals(0, idx % 5, "test setup: section must sit on the -x edge of the cache");

        engine.postLightUpdate(idx, BFSTestHelper.localIndex(0, 5, 5));

        assertArrayEquals(new int[] { idx }, marked(), "cache-edge section must not mark out of bounds in x");
    }

    @Test
    void serverSideEngineNeverMarks() {
        final TestableBlockEngine server = new TestableBlockEngine(MCBootstrap.getServerWorld());
        BFSTestHelper.setupCenter(server);

        server.postLightUpdate(BFSTestHelper.sectionIndex(server, 0, 4, 0), BFSTestHelper.localIndex(15, 15, 15));

        for (boolean b : server.getNotifyUpdateCache()) assertFalse(b, "server-side engine must not mark render updates");
    }

    @Test
    void clientPublishLeavesChunkUnmodified() {
        BFSTestHelper.populateAirSection(engine, 0, 4, 0);
        final Chunk chunk = newTrackedChunk(engine);

        engine.setLightAt(8, 68, 8, PackedColorLight.pack(9, 0, 0));
        engine.callUpdateVisible();

        assertFalse(chunk.isModified, "client-side publishes must not mark chunks");
    }

    @Test
    void nibbleChangeMarksOwningChunkModified() {
        final TestableBlockEngine engine = serverEngine();
        final Chunk chunk = newTrackedChunk(engine);

        engine.setLightAt(8, 68, 8, PackedColorLight.pack(9, 0, 0));
        engine.callUpdateVisible();

        assertTrue(chunk.isModified, "publishing a changed nibble must mark the owning chunk");
    }

    @Test
    void greenOnlyChangeMarksOwningChunkModified() {
        final TestableBlockEngine engine = serverEngine();
        final Chunk chunk = newTrackedChunk(engine);

        final int idx = BFSTestHelper.sectionIndex(engine, 0, 4, 0);
        engine.getNibbleCacheG()[idx].set(0, 5);
        engine.callUpdateVisible();

        assertTrue(chunk.isModified, "a G-channel-only change must mark the owning chunk");
    }

    @Test
    void noChangeLeavesChunkUnmarked() {
        final TestableBlockEngine engine = serverEngine();
        final Chunk chunk = newTrackedChunk(engine);

        engine.callUpdateVisible();

        assertFalse(chunk.isModified, "no nibble change must not mark the chunk");
    }

    @Test
    void packedWritesAreUnpackedBeforeTheNibblePublishLoop() {
        final boolean[] cleanAtExtra = new boolean[1];
        final TestableBlockEngine engine = new TestableBlockEngine(MCBootstrap.getServerWorld()) {

            @Override
            protected void updateVisibleExtra() {
                boolean clean = true;
                for (boolean dirty : this.packedCacheDirty) clean &= !dirty;
                cleanAtExtra[0] = clean;
                super.updateVisibleExtra();
            }
        };
        BFSTestHelper.centeredWithAir(engine);

        // Read first so the write lands in packedRGBCache, not the nibbles.
        engine.getLightAt(8, 68, 8);
        engine.setLightAt(8, 68, 8, PackedColorLight.pack(9, 0, 0));
        engine.callUpdateVisible();

        assertTrue(cleanAtExtra[0], "the packed cache must already be unpacked when the nibble loop publishes");
    }

    @Test
    void updateVisiblePassesTheSectionOriginToTheRenderRange() {
        BFSTestHelper.populateAirSection(engine, 0, 4, 0);
        engine.setLightAt(8, 72, 8, PackedColorLight.pack(9, 3, 1));

        engine.callUpdateVisible();

        assertEquals(Arrays.asList(0, 64, 0), engine.getRenderMarks(), "one mark, at the section's world-space origin");
    }

    @Test
    void unchangedValueDoesNotReMark() {
        BFSTestHelper.populateAirSection(engine, 0, 4, 0);
        final int idx = BFSTestHelper.sectionIndex(engine, 0, 4, 0);
        final int packed = PackedColorLight.pack(9, 3, 1);

        engine.setLightAt(8, 72, 8, packed);
        assertArrayEquals(new int[] { idx }, marked(), "first write must mark");

        clearMarks();
        engine.setLightAt(8, 72, 8, packed);
        assertArrayEquals(new int[0], marked(), "rewriting the same value must not mark");

        engine.setLightAt(8, 72, 8, PackedColorLight.pack(9, 3, 2));
        assertArrayEquals(new int[] { idx }, marked(), "a blue-only difference must mark");
    }
}
