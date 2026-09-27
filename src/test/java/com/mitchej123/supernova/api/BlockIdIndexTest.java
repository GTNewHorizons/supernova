package com.mitchej123.supernova.api;

import com.mitchej123.supernova.light.engine.MCBootstrap;
import net.minecraft.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.mitchej123.supernova.api.PackedColorLight.pack;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockIdIndexTest {

    private static Block probe;

    @BeforeAll
    static void bootstrap() {
        probe = MCBootstrap.registerTestBlock(MCBootstrap.TestIds.INDEX_PROBE, "block_id_index_probe", Block.class, 0, false, 0);
    }

    private static int[] wildcard(final int value) {
        final BlockIdIndex index = new BlockIdIndex();
        index.put(probe, new int[] { value + 1 });
        return index.entryFor(Block.getIdFromBlock(probe));
    }

    private static int[] perMeta(final int... metaThenValuePairs) {
        final BlockIdIndex index = new BlockIdIndex();
        for (int i = 0; i < metaThenValuePairs.length; i += 2) {
            index.putMeta(probe, metaThenValuePairs[i], metaThenValuePairs[i + 1] + 1);
        }
        return index.entryFor(Block.getIdFromBlock(probe));
    }

    @Test
    void lookupPerMetaRegistered() {
        final int abs0 = pack(1, 2, 3);
        final int abs5 = pack(10, 11, 12);
        final int[] entry = perMeta(0, abs0, 5, abs5);

        assertEquals(abs0, BlockIdIndex.lookup(entry, 0));
        assertEquals(abs5, BlockIdIndex.lookup(entry, 5));
    }

    @Test
    void lookupMissReturnsMinusOne() {
        final int[] entry = perMeta(0, pack(1, 1, 1));
        assertEquals(-1, BlockIdIndex.lookup(entry, 3), "unregistered meta");
        assertEquals(-1, BlockIdIndex.lookup(entry, 20), "meta past the entry");
        assertEquals(-1, BlockIdIndex.lookup(entry, -1), "negative meta");
    }

    @Test
    void lookupWildcardIgnoresMeta() {
        final int absorption = pack(7, 7, 7);
        final int[] entry = wildcard(absorption);
        for (int meta = 0; meta <= 15; meta++) {
            assertEquals(absorption, BlockIdIndex.lookup(entry, meta), "meta=" + meta);
        }
        assertEquals(absorption, BlockIdIndex.lookup(entry, -5));
        assertEquals(absorption, BlockIdIndex.lookup(entry, 100));
    }

    @Test
    void putMetaNeverMutatesAPublishedEntry() {
        final BlockIdIndex index = new BlockIdIndex();
        final int id = Block.getIdFromBlock(probe);
        index.putMeta(probe, 0, pack(1, 2, 3) + 1);
        final int[] published = index.entryFor(id);

        index.putMeta(probe, 3, pack(4, 5, 6) + 1);

        assertEquals(0, published[3], "a worker holding the old entry must not see the new meta appear under it");
    }

    @Test
    void sentinelValueDistinctFromZeroAbsorption() {
        // Stored value is absorption + 1; 0 means unregistered.
        final int[] entry = perMeta(0, pack(0, 0, 0));

        assertEquals(0, BlockIdIndex.lookup(entry, 0));
        assertEquals(-1, BlockIdIndex.lookup(entry, 1));
    }
}
