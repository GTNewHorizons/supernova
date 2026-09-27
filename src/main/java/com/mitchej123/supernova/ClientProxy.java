package com.mitchej123.supernova;

import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;
import com.mitchej123.supernova.client.TintBlendMode;
import com.mitchej123.supernova.command.CommandSupernova;
import com.mitchej123.supernova.compat.angelica.AngelicaCompat;
import com.mitchej123.supernova.config.SupernovaClientConfig;
import com.mitchej123.supernova.config.SupernovaConfig;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.client.ClientCommandHandler;
import org.lwjgl.input.Keyboard;

public class ClientProxy extends CommonProxy {

    private static boolean isDevEnvironment() {
        return Boolean.TRUE.equals(Launch.blackboard.get("fml.deobfuscatedEnvironment"));
    }

    private static KeyBinding tintModeKeyBinding;
    private static boolean angelicaLoaded;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        try {
            ConfigurationManager.registerConfig(SupernovaClientConfig.class);
        } catch (ConfigException e) {
            throw new RuntimeException("Failed to register Supernova client config", e);
        }
        TintBlendMode.current = SupernovaClientConfig.tintBlendMode;
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        ClientCommandHandler.instance.registerCommand(new CommandSupernova(this.configDir, true));

        tintModeKeyBinding = new KeyBinding("Cycle Tint Blend Mode", isDevEnvironment() ? Keyboard.KEY_BACKSLASH : Keyboard.KEY_NONE, "Supernova");
        ClientRegistry.registerKeyBinding(tintModeKeyBinding);
        FMLCommonHandler.instance().bus().register(this);

        angelicaLoaded = Loader.isModLoaded("angelica") && !SupernovaConfig.isScalarMode();
        if (angelicaLoaded) {
            AngelicaCompat.register();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        while (tintModeKeyBinding.isPressed()) {
            final TintBlendMode[] modes = TintBlendMode.values();
            final int nextIndex = (TintBlendMode.current.ordinal() + 1) % modes.length;
            TintBlendMode.current = modes[nextIndex];
            SupernovaClientConfig.tintBlendMode = TintBlendMode.current;
            ConfigurationManager.save(SupernovaClientConfig.class);

            if (angelicaLoaded) {
                AngelicaCompat.syncTintMode();
            }

            if (Minecraft.getMinecraft().thePlayer != null) {
                Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText("\u00a7e[Supernova]\u00a7r Tint blend mode: \u00a7b"
                        + TintBlendMode.current.name()));
            }
            rebuildTintedGeometry();
        }
    }

    private static void rebuildTintedGeometry() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.renderGlobal == null) return;
        if (mc.thePlayer == null) return;
        if (angelicaLoaded) {
            AngelicaCompat.rebuildAllSections(mc.thePlayer.chunkCoordX, mc.thePlayer.chunkCoordZ);
        } else {
            final int px = MathHelper.floor_double(mc.thePlayer.posX);
            final int pz = MathHelper.floor_double(mc.thePlayer.posZ);
            final int r = mc.gameSettings.renderDistanceChunks * 16;
            mc.renderGlobal.markBlockRangeForRenderUpdate(px - r, 0, pz - r, px + r, 255, pz + r);
        }
    }
}
