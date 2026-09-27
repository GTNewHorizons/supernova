package com.mitchej123.supernova.api;

import com.mitchej123.supernova.light.engine.MCBootstrap;
import com.mitchej123.supernova.light.engine.MCBootstrap.TestIds;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.mitchej123.supernova.api.PackedColorLight.blue;
import static com.mitchej123.supernova.api.PackedColorLight.green;
import static com.mitchej123.supernova.api.PackedColorLight.pack;
import static com.mitchej123.supernova.api.PackedColorLight.red;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightColorRegistryTest {

    @BeforeEach
    @AfterEach
    void resetRegistries() {
        LightColorRegistry.clearForTest();
        TranslucencyRegistry.clearForTest();
    }

    @Test
    void cacheAgreesWithDirectPathForUnregisteredMeta() {
        final Block block = MCBootstrap.registerTestBlock(TestIds.PARTIAL_META_LAMP, "partial_meta_lamp", Block.class, 0, false, 10);
        LightColorRegistry.register(block, 13, 15, 0, 0);
        LightColorRegistry.buildCache();

        final int id = Block.getIdFromBlock(block);
        assertEquals(LightColorRegistry.getPackedEmissionNoWorld(block, 0), LightColorRegistry.getPackedEmissionCached(id, 0));
        assertEquals(PackedColorLight.pack(10, 10, 10), LightColorRegistry.getPackedEmissionCached(id, 0), "unregistered meta falls back to vanilla white");
    }

    @Test
    void metasPastFifteenDoNotCostTheBlockItsCache() {
        final Block lamp = MCBootstrap.registerTestBlock(TestIds.PARTIAL_META_LAMP, "partial_meta_lamp", Block.class, 0, false, 0);
        for (int meta = 0; meta < 32; meta++) {
            LightColorRegistry.register(lamp, meta, meta < 16 ? 7 : 1, 0, 0);
        }
        LightColorRegistry.buildCache();

        final int id = Block.getIdFromBlock(lamp);
        assertEquals(LightColorRegistry.getPackedEmissionNoWorld(lamp, 3), LightColorRegistry.getPackedEmissionCached(id, 3));
        assertEquals(LightColorRegistry.getPackedEmissionNoWorld(lamp, 20), LightColorRegistry.getPackedEmissionCached(id, 20),
            "meta past the cached row must fall through to the direct path");

        // Unrebuilt cache still answers the old value, proving metas 0-15 hit the cache.
        LightColorRegistry.register(lamp, 3, 0, 15, 0);
        assertEquals(pack(7, 0, 0), LightColorRegistry.getPackedEmissionCached(id, 3));
    }

    @Test
    void wildcardRegistrationAppliesToEveryMeta() {
        Block block = Blocks.stone;
        LightColorRegistry.register(block, 10, 5, 3);

        for (int meta = 0; meta <= 15; meta++) {
            int emission = LightColorRegistry.getPackedEmissionNoWorld(block, meta);
            assertEquals(10, red(emission), "red at meta " + meta);
            assertEquals(5, green(emission), "green at meta " + meta);
            assertEquals(3, blue(emission), "blue at meta " + meta);
        }
    }

    @Test
    void perMetaRegistrationAffectsOnlyThatMeta() {
        Block block = Blocks.stone;
        LightColorRegistry.register(block, 0, 15, 0, 0);
        LightColorRegistry.register(block, 5, 0, 15, 0);

        int m0 = LightColorRegistry.getPackedEmissionNoWorld(block, 0);
        assertEquals(15, red(m0));
        assertEquals(0, green(m0));

        int m5 = LightColorRegistry.getPackedEmissionNoWorld(block, 5);
        assertEquals(0, red(m5));
        assertEquals(15, green(m5));

        // Stone has lightValue 0, so an unregistered meta has no vanilla fallback either.
        int m3 = LightColorRegistry.getPackedEmissionNoWorld(block, 3);
        assertEquals(0, m3);
    }

    @Test
    void perMetaRegistrationOverridesWildcard() {
        Block block = Blocks.stone;
        LightColorRegistry.register(block, 5, 5, 5);
        LightColorRegistry.register(block, 3, 15, 0, 0);

        int m3 = LightColorRegistry.getPackedEmissionNoWorld(block, 3);
        assertEquals(15, red(m3));
        assertEquals(0, green(m3));

        int m0 = LightColorRegistry.getPackedEmissionNoWorld(block, 0);
        assertEquals(5, red(m0));
        assertEquals(5, green(m0));
        assertEquals(5, blue(m0));
    }

    @Test
    void hasExplicitEntryReflectsRegistration() {
        assertFalse(LightColorRegistry.hasExplicitEntry(Blocks.stone));
        LightColorRegistry.register(Blocks.stone, 10, 10, 10);
        assertTrue(LightColorRegistry.hasExplicitEntry(Blocks.stone));
        assertTrue(LightColorRegistry.hasExplicitEntry(Block.getIdFromBlock(Blocks.stone)));
    }

    @Test
    void vanillaFallbackUsesBlockLightValue() {
        Block emitter = MCBootstrap.registerTestBlock(TestIds.VANILLA_FALLBACK_EMITTER, "vanilla_fallback_emitter", Block.class, 0, false, 10);
        int emission = LightColorRegistry.getPackedEmissionNoWorld(emitter, 0);
        assertEquals(10, red(emission));
        assertEquals(10, green(emission));
        assertEquals(10, blue(emission));
    }

    @Test
    void unregisteredBlockWithNoLightHasNoEmission() {
        assertEquals(0, LightColorRegistry.getPackedEmissionNoWorld(Blocks.stone, 0));
    }

    @Nested
    class Remap {

        @Test
        void emissionLookupsFollowRemappedIds() {
            final Block lamp = MCBootstrap.registerTestBlock(TestIds.REMAP_LAMP, "remap_lamp", Block.class, 0, false, 0);
            LightColorRegistry.register(lamp, 13, 0, 15, 0);
            assertTrue(LightColorRegistry.hasExplicitEntry(TestIds.REMAP_LAMP));

            MCBootstrap.rebindBlock(TestIds.REMAP_LAMP_TARGET, "remap_lamp", lamp);
            LightColorRegistry.rebuildIdMappings();
            LightColorRegistry.buildCache();

            assertTrue(LightColorRegistry.hasExplicitEntry(TestIds.REMAP_LAMP_TARGET), "entry must follow the block to its new id");
            assertFalse(LightColorRegistry.hasExplicitEntry(TestIds.REMAP_LAMP), "old id must not retain the entry");
            final int emission = LightColorRegistry.getPackedEmissionNoWorld(lamp, 13);
            assertEquals(15, green(emission));
            assertEquals(0, red(emission));
            assertEquals(emission, LightColorRegistry.getPackedEmissionCached(TestIds.REMAP_LAMP_TARGET, 13));
        }

        @Test
        void absorptionLookupsFollowRemappedIds() {
            final Block glass = MCBootstrap.registerTestBlock(TestIds.REMAP_GLASS, "remap_glass", Block.class, 1, false, 0);
            TranslucencyRegistry.registerTransmittance(glass, 13, 15, 2, 2);
            assertTrue(TranslucencyRegistry.hasExplicitEntry(TestIds.REMAP_GLASS));
            final int absorption = TranslucencyRegistry.getPackedAbsorptionNoInterface(glass, 13);
            assertEquals(pack(0, 13, 13), absorption);

            MCBootstrap.rebindBlock(TestIds.REMAP_GLASS_TARGET, "remap_glass", glass);
            TranslucencyRegistry.rebuildIdMappings();
            TranslucencyRegistry.buildCache();

            assertTrue(TranslucencyRegistry.hasExplicitEntry(TestIds.REMAP_GLASS_TARGET));
            assertFalse(TranslucencyRegistry.hasExplicitEntry(TestIds.REMAP_GLASS));
            assertEquals(absorption, TranslucencyRegistry.getPackedAbsorptionNoInterface(glass, 13));
            assertEquals(absorption, TranslucencyRegistry.getPackedAbsorptionCached(TestIds.REMAP_GLASS_TARGET, 13));
        }

        @Test
        void uncacheableMarksFollowRemappedIds() {
            final Block sided = MCBootstrap.registerTestBlock(TestIds.REMAP_SIDED, "remap_sided", Block.class, 3, false, 0);
            TranslucencyRegistry.markUncacheable(TestIds.REMAP_SIDED);

            MCBootstrap.rebindBlock(TestIds.REMAP_SIDED_TARGET, "remap_sided", sided);
            TranslucencyRegistry.rebuildIdMappings();
            TranslucencyRegistry.buildCache();

            assertEquals(-1, TranslucencyRegistry.getPackedAbsorptionCached(TestIds.REMAP_SIDED_TARGET, 0),
                "uncacheable mark must follow the block to its new id");
        }
    }
}
