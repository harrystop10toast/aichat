package com.geministuff.aichat.command;

import com.geministuff.aichat.config.ConfigScreen;
import com.geministuff.aichat.config.ModConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class GeminiConfigCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerConfig(dispatcher, "aiconfig");
            registerConfig(dispatcher, "AIconfig");
            registerConfig(dispatcher, "geminiconfig");
        });
    }

    private static void registerConfig(CommandDispatcher<FabricClientCommandSource> dispatcher, String literal) {
        dispatcher.register(ClientCommands.literal(literal)
                .executes(ctx -> {
                    Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().setScreen(new ConfigScreen()));
                    return 1;
                })
                .then(ClientCommands.literal("set")
                        .then(ClientCommands.argument("key", StringArgumentType.word())
                                .then(ClientCommands.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> applySet(ctx,
                                                StringArgumentType.getString(ctx, "key"),
                                                StringArgumentType.getString(ctx, "value"))))))
        );
    }

    private static int applySet(CommandContext<FabricClientCommandSource> ctx, String key, String value) {
        ModConfig cfg = ModConfig.get();
        switch (key.toLowerCase()) {
            case "provider" -> {
                String v = value.trim().toLowerCase();
                if (!v.equals("gemini") && !v.equals("groq")) {
                    ctx.getSource().sendError(Component.literal("provider must be gemini or groq"));
                    return 0;
                }
                cfg.provider = v;
            }
            case "apikey" -> cfg.setCurrentKey(value.trim());
            case "hypixelkey" -> cfg.hypixelApiKey = value.trim();
            case "searxng" -> cfg.searxngUrl = value.trim();
            case "internet" -> cfg.internetEnabled = Boolean.parseBoolean(value.trim());
            case "model" -> cfg.setCurrentModel(value.trim());
            case "posttochat" -> cfg.postToChat = Boolean.parseBoolean(value.trim());
            case "guild" -> cfg.guildEnabled = Boolean.parseBoolean(value.trim());
            case "party" -> cfg.partyEnabled = Boolean.parseBoolean(value.trim());
            case "public" -> cfg.publicEnabled = Boolean.parseBoolean(value.trim());
            case "tone" -> {
                String norm = ModConfig.normalizeTone(value);
                if (norm == null) {
                    ctx.getSource().sendError(Component.literal("Unknown tone. Options: "
                            + String.join(", ", cfg.getAvailableTones())));
                    return 0;
                }
                cfg.tone = norm;
            }
            default -> {
                ctx.getSource().sendError(Component.literal(
                        "Unknown key. Use: provider, apikey, hypixelkey, searxng, internet, model, tone, postToChat, guild, party, public"));
                return 0;
            }
        }
        cfg.save();
        ctx.getSource().sendFeedback(Component.literal("AIChat: " + key + " updated."));
        return 1;
    }
}
