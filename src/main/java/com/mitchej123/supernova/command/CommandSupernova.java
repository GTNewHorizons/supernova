package com.mitchej123.supernova.command;

import com.mitchej123.supernova.config.BlockColorConfig;
import com.mitchej123.supernova.config.BlockTranslucencyConfig;
import com.mitchej123.supernova.light.LightRegistries;
import com.mitchej123.supernova.light.WorldLightManager;
import com.mitchej123.supernova.world.SupernovaWorld;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.io.File;
import java.util.List;

public class CommandSupernova extends CommandBase {

    private final File configDir;

    private final boolean clientSide;

    public CommandSupernova(File configDir) {
        this(configDir, false);
    }

    public CommandSupernova(File configDir, boolean clientSide) {
        this.configDir = configDir;
        this.clientSide = clientSide;
    }

    @Override
    public String getCommandName() {
        return this.clientSide ? "csupernova" : "supernova";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return this.clientSide
            ? "/csupernova relight [<cx> <cz> | <radius>] -- relights the client's own copy of the chunks"
            : "/supernova <colors|translucency> <dump|reload> | relight [<cx> <cz> | <radius>]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return this.clientSide ? 0 : 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equals("relight")) {
            processRelightSubcommand(sender, args);
            return;
        }
        if (!this.clientSide && args.length >= 2) {
            switch (args[0]) {
                case "colors" -> {
                    if (processColorSubcommand(sender, args[1])) return;
                }
                case "translucency" -> {
                    if (processTranslucencySubcommand(sender, args[1])) return;
                }
            }
        }
        sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
    }

    private boolean processColorSubcommand(ICommandSender sender, String action) {
        switch (action) {
            case "dump" -> {
                int count = BlockColorConfig.dump(configDir);
                sender.addChatMessage(new ChatComponentText("Dumped " + count + " light color entries to supernova-colors.cfg"));
                return true;
            }
            case "reload" -> {
                int loaded = BlockColorConfig.reload(configDir);
                LightRegistries.rebuildCaches();
                sender.addChatMessage(new ChatComponentText("Reloaded " + loaded + " light color entries from supernova-colors.cfg. Already-lit chunks keep their old light -- use /supernova relight <radius>."));
                return true;
            }
        }
        return false;
    }

    private boolean processTranslucencySubcommand(ICommandSender sender, String action) {
        switch (action) {
            case "dump" -> {
                int count = BlockTranslucencyConfig.dump(configDir);
                sender.addChatMessage(new ChatComponentText("Dumped " + count + " translucency entries to supernova-translucency.cfg"));
                return true;
            }
            case "reload" -> {
                int loaded = BlockTranslucencyConfig.reload(configDir);
                LightRegistries.rebuildCaches();
                sender.addChatMessage(new ChatComponentText("Reloaded " + loaded + " translucency entries from supernova-translucency.cfg. Already-lit chunks keep their old light -- use /supernova relight <radius>."));
                return true;
            }
        }
        return false;
    }

    private void processRelightSubcommand(ICommandSender sender, String[] args) {
        final World world = sender.getEntityWorld();
        if (world == null) {
            sender.addChatMessage(new ChatComponentText("No world available."));
            return;
        }
        final WorldLightManager mgr = ((SupernovaWorld) world).supernova$getLightManager();
        if (mgr == null) {
            sender.addChatMessage(new ChatComponentText("Supernova light manager not available for this world."));
            return;
        }

        if (args.length == 3) {
            // /supernova relight <cx> <cz>
            final int cx = parseInt(sender, args[1]);
            final int cz = parseInt(sender, args[2]);
            if (mgr.forceRelightChunk(cx, cz)) {
                sender.addChatMessage(new ChatComponentText("Queued relight for chunk (" + cx + ", " + cz + ")."));
            } else {
                sender.addChatMessage(new ChatComponentText("Chunk (" + cx + ", " + cz + ") is not loaded."));
            }
        } else if (args.length == 2) {
            // /supernova relight <radius>
            if (!(sender instanceof EntityPlayer player)) {
                sender.addChatMessage(new ChatComponentText("Radius form requires a player sender."));
                return;
            }
            final int radius = Math.min(parseIntWithMin(sender, args[1], 0), 16);
            final int playerCx = MathHelper.floor_double(player.posX) >> 4;
            final int playerCz = MathHelper.floor_double(player.posZ) >> 4;
            int count = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (mgr.forceRelightChunk(playerCx + dx, playerCz + dz)) count++;
                }
            }
            sender.addChatMessage(new ChatComponentText("Queued relight for " + count + " chunks (radius " + radius + ")."));
        } else {
            // /supernova relight (no args -- current chunk)
            if (!(sender instanceof EntityPlayer player)) {
                sender.addChatMessage(new ChatComponentText("Specify chunk coordinates: /" + getCommandName() + " relight <cx> <cz>"));
                return;
            }
            final int cx = MathHelper.floor_double(player.posX) >> 4;
            final int cz = MathHelper.floor_double(player.posZ) >> 4;
            if (mgr.forceRelightChunk(cx, cz)) {
                sender.addChatMessage(new ChatComponentText("Queued relight for chunk (" + cx + ", " + cz + ")."));
            } else {
                sender.addChatMessage(new ChatComponentText("Chunk (" + cx + ", " + cz + ") is not loaded."));
            }
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return this.clientSide
                ? getListOfStringsMatchingLastWord(args, "relight")
                : getListOfStringsMatchingLastWord(args, "colors", "translucency", "relight");
        }
        if (!this.clientSide && args.length == 2 && (args[0].equals("colors") || args[0].equals("translucency"))) {
            return getListOfStringsMatchingLastWord(args, "dump", "reload");
        }
        return null;
    }
}
