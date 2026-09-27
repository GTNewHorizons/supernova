package com.mitchej123.supernova.mixin.early.engine;

import com.mitchej123.supernova.light.WorldLightManager;
import com.mitchej123.supernova.world.SupernovaWorld;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityPlayerMP.class)
public abstract class MixinEntityPlayerMP {

    /** The redirected check is what the vanilla send loop uses to drop a coordinate, so false leaves the chunk queued for the next tick. */
    @Redirect(method = "onUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;func_150802_k()Z"))
    private boolean supernova$gateSendOnLight(Chunk chunk) {
        if (!chunk.func_150802_k()) return false;
        if (chunk.worldObj == null) return true;
        final WorldLightManager manager = ((SupernovaWorld) chunk.worldObj).supernova$getLightManager();
        if (manager == null) return true;
        return manager.isReadyToSend(chunk.xPosition, chunk.zPosition);
    }
}
