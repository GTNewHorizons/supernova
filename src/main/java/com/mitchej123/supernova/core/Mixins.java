package com.mitchej123.supernova.core;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;
import com.mitchej123.supernova.config.SupernovaConfig;

import javax.annotation.Nonnull;

public enum Mixins implements IMixins {

    ENGINE(new MixinBuilder("Supernova light engine")
            .addCommonMixins(
                    "early.engine.MixinChunk",
                    "early.engine.MixinWorld",
                    "early.engine.MixinWorldServer")
            .addClientMixins(
                    "early.engine.MixinChunk_FillChunkClient",
                    "early.engine.MixinPlayerControllerMP"
            )
            .setPhase(Phase.EARLY)
    ),

    VANILLA_RENDERING(new MixinBuilder("Vanilla colored light rendering").
            addClientMixins("early.rendering.MixinRenderBlocks")
            .addExcludedMod(TargetedMod.ANGELICA)
            .setApplyIf(() -> !SupernovaConfig.isScalarMode())
            .setPhase(Phase.EARLY)
    ),

    ENTITY_RENDERING(new MixinBuilder("Entity colored light rendering")
            .addClientMixins("early.rendering.MixinRenderManager")
            .setApplyIf(() -> !SupernovaConfig.isScalarMode())
            .setPhase(Phase.EARLY)
    );

    private final MixinBuilder builder;

    Mixins(MixinBuilder builder) {
        this.builder = builder;
    }

    @Nonnull
    @Override
    public MixinBuilder getBuilder() {
        return builder;
    }
}
