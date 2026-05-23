package com.mitchej123.supernova.core;

import com.gtnewhorizon.gtnhmixins.builders.ITargetMod;
import com.gtnewhorizon.gtnhmixins.builders.TargetModBuilder;

import javax.annotation.Nonnull;

public enum TargetedMod implements ITargetMod {

    ANGELICA("com.gtnewhorizons.angelica.loading.AngelicaTweaker", "angelica"),
    FMP("codechicken.multipart.minecraft.MinecraftMultipartMod");

    private final TargetModBuilder builder;


    TargetedMod(String targetClass) {
        this.builder = new TargetModBuilder().setTargetClass(targetClass);
    }
    TargetedMod(String coreModClass, String modId) {
        this.builder = new TargetModBuilder().setCoreModClass(coreModClass).setModId(modId);
    }

    @Nonnull
    @Override
    public TargetModBuilder getBuilder() {
        return builder;
    }
}
