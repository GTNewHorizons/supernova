package com.mitchej123.supernova;

import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.TranslucencyRegistry;
import com.mitchej123.supernova.config.BlockColorConfig;
import com.mitchej123.supernova.config.BlockTranslucencyConfig;
import com.mitchej123.supernova.config.DefaultColors;
import com.mitchej123.supernova.config.DefaultTranslucency;
import com.mitchej123.supernova.compat.cubicchunks.CubicChunksCompat;
import com.mitchej123.supernova.core.SupernovaCore;
import com.mitchej123.supernova.light.LightRegistryDiagnostics;
import com.mitchej123.supernova.light.engine.FaceOcclusion;
import com.mitchej123.supernova.storage.SupernovaDataManager;
import com.mitchej123.supernova.storage.SupernovaSkyDataManager;
import com.mitchej123.supernova.world.SupernovaWorld;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import java.io.File;

public class CommonProxy {

    File configDir;

    public void preInit(FMLPreInitializationEvent event) {
        Supernova.LOG.info("Supernova " + Tags.VERSION + " loading");
        this.configDir = event.getModConfigurationDirectory();
        CubicChunksCompat.init();
    }

    public void init(FMLInitializationEvent event) {
        if (SupernovaCore.CHUNKAPI_PRESENT) {
            SupernovaDataManager.register();
            SupernovaSkyDataManager.register();
        }
        DefaultColors.register();
        DefaultTranslucency.register();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        ((SupernovaWorld) event.world).supernova$shutdown();
    }

    public void postInit(FMLPostInitializationEvent event) {
        DefaultColors.registerModded();
        LightRegistryDiagnostics.dumpUnregistered(this.configDir);
        FaceOcclusion.registerDefaults();
        TranslucencyRegistry.buildCache();
        LightColorRegistry.buildCache();
        BlockColorConfig.load(this.configDir);
        BlockTranslucencyConfig.load(this.configDir);
    }
}
