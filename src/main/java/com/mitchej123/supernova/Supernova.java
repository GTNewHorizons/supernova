package com.mitchej123.supernova;

import com.mitchej123.supernova.command.CommandSupernova;
import com.mitchej123.supernova.light.LightRegistries;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLModIdMappingEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = Supernova.MODID, version = Tags.VERSION, name = "Supernova", acceptedMinecraftVersions = "[1.7.10]", dependencies = "after:chunkapi@[0.8.1,);required-after:hodgepodge@[2.7.179,);after:angelica@[2.1.61,)")
public class Supernova {

    public static final String MODID = "supernova";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.mitchej123.supernova.ClientProxy", serverSide = "com.mitchej123.supernova.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandSupernova(proxy.configDir));
    }

    @Mod.EventHandler
    public void onIdMapping(FMLModIdMappingEvent event) {
        // Unconditional: revertToFrozen changes ids and fires with an empty remap list.
        LightRegistries.rebuildAll();
    }
}
