package com.mitchej123.supernova.mixin.early.engine;

import com.mitchej123.supernova.light.WorldLightManager;
import com.mitchej123.supernova.world.SupernovaWorld;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @Inject(method = "tick", at = @At("TAIL"))
    private void supernova$tickLighting(CallbackInfo ci) {
        final WorldLightManager iface = ((SupernovaWorld) this).supernova$lightManagerIfPresent();
        if (iface != null && iface.hasUpdates()) {
            iface.tick();
        }
    }
}
