package com.geministuff.aichat.command;

import com.geministuff.aichat.chat.ChatChannel;
import com.geministuff.aichat.chat.ChatSender;
import com.geministuff.aichat.config.ModConfig;
import com.geministuff.aichat.ai.ConversationMemory;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Client-side slash commands for ChatGPT-like multi-chat management:
 *   /newchat [channel]     or /NewChat
 *   /destroy [channel]     or /Destroy
 *   /allchats [channel]    or /allChats
 *   /settitle <prompt>     or /setTitle <prompt>
 *   /selectchat <id>       or /selectChat <id> [channel]
 *   /settone [tone]        or /setTone [tone]
 */
public class ChatManageCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerNewChat(dispatcher, "newchat");

            registerDestroy(dispatcher, "destroy");


            registerAllChats(dispatcher, "allchats");


            registerSetTitle(dispatcher, "settitle");


            registerSelectChat(dispatcher, "selectchat");


            registerSetTone(dispatcher, "settone");

        });
    }

    private static String resolveDefaultChannel() {
        return ModConfig.get().postToChat ? "public" : "private";
    }

    private static String normalizeChannel(String ch) {
        if (ch == null || ch.isBlank()) return resolveDefaultChannel();
        String c = ch.trim().toLowerCase();
        return switch (c) {
            case "guild", "g" -> "guild";
            case "party", "p" -> "party";
            case "public", "pub" -> "public";
            case "private", "priv", "me" -> "private";
            default -> c;
        };
    }

    // --- /newchat ---
    private static void registerNewChat(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommands.literal(name)
                .executes(ctx -> {
                    String channel = resolveDefaultChannel();
                    ConversationMemory.Result res = ConversationMemory.newChat(channel);
                    sendResult(ctx.getSource(), res);
                    return res.ok() ? 1 : 0;
                })
                .then(ClientCommands.argument("channel", StringArgumentType.word())
                        .executes(ctx -> {
                            String channel = normalizeChannel(StringArgumentType.getString(ctx, "channel"));
                            ConversationMemory.Result res = ConversationMemory.newChat(channel);
                            sendResult(ctx.getSource(), res);
                            return res.ok() ? 1 : 0;
                        })));
    }

    // --- /destroy ---
    private static void registerDestroy(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommands.literal(name)
                .executes(ctx -> {
                    String channel = resolveDefaultChannel();
                    ConversationMemory.Result res = ConversationMemory.destroyCurrentChat(channel);
                    sendResult(ctx.getSource(), res);
                    return res.ok() ? 1 : 0;
                })
                .then(ClientCommands.argument("channel", StringArgumentType.word())
                        .executes(ctx -> {
                            String channel = normalizeChannel(StringArgumentType.getString(ctx, "channel"));
                            ConversationMemory.Result res = ConversationMemory.destroyCurrentChat(channel);
                            sendResult(ctx.getSource(), res);
                            return res.ok() ? 1 : 0;
                        })));
    }

    // --- /allchats ---
    private static void registerAllChats(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommands.literal(name)
                .executes(ctx -> {
                    FabricClientCommandSource src = ctx.getSource();
                    String provider = ModConfig.get().providerName();
                    src.sendFeedback(Component.literal("§b=== AIChat Chats [Provider: " + provider + "] ==="));

                    String[] channels = new String[]{"guild", "party", "public", "private"};
                    for (String ch : channels) {
                        List<String> lines = ConversationMemory.formatAllChatsDetailed(provider, ch);
                        // Skip header line since we display per-channel header
                        src.sendFeedback(Component.literal("§6▶ Channel: §e[" + ch + "]"));
                        for (int i = 1; i < lines.size() - 1; i++) {
                            src.sendFeedback(Component.literal(lines.get(i)));
                        }
                    }
                    src.sendFeedback(Component.literal("§7Use §f/selectChat <id> [channel] §7to switch."));
                    return 1;
                })
                .then(ClientCommands.argument("channel", StringArgumentType.word())
                        .executes(ctx -> {
                            FabricClientCommandSource src = ctx.getSource();
                            String channel = normalizeChannel(StringArgumentType.getString(ctx, "channel"));
                            String provider = ModConfig.get().providerName();
                            List<String> lines = ConversationMemory.formatAllChatsDetailed(provider, channel);
                            for (String l : lines) {
                                src.sendFeedback(Component.literal(l));
                            }
                            return 1;
                        })));
    }

    // --- /settitle ---
    private static void registerSetTitle(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommands.literal(name)
                .then(ClientCommands.argument("title", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String title = StringArgumentType.getString(ctx, "title");
                            String channel = resolveDefaultChannel();
                            ConversationMemory.Result res = ConversationMemory.setTitle(channel, title);
                            sendResult(ctx.getSource(), res);
                            return res.ok() ? 1 : 0;
                        })));
    }

    // --- /selectchat ---
    private static void registerSelectChat(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommands.literal(name)
                .then(ClientCommands.argument("id", IntegerArgumentType.integer(1, ConversationMemory.MAX_CHATS_PER_CHANNEL))
                        .executes(ctx -> {
                            int id = IntegerArgumentType.getInteger(ctx, "id");
                            String channel = resolveDefaultChannel();
                            ConversationMemory.Result res = ConversationMemory.selectChat(channel, id);
                            sendResult(ctx.getSource(), res);
                            return res.ok() ? 1 : 0;
                        })
                        .then(ClientCommands.argument("channel", StringArgumentType.word())
                                .executes(ctx -> {
                                    int id = IntegerArgumentType.getInteger(ctx, "id");
                                    String channel = normalizeChannel(StringArgumentType.getString(ctx, "channel"));
                                    ConversationMemory.Result res = ConversationMemory.selectChat(channel, id);
                                    sendResult(ctx.getSource(), res);
                                    return res.ok() ? 1 : 0;
                                }))));
    }

    // --- /settone ---
    private static void registerSetTone(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommands.literal(name)
                .executes(ctx -> handleShowTone(ctx.getSource(), name))
                .then(ClientCommands.literal("add")
                        .executes(ctx -> {
                            ctx.getSource().sendError(Component.literal("§c[AIChat]§r Usage: /" + name + " add <tone> (e.g. /" + name + " add pirate, or /" + name + " add pirate: speak like a pirate)"));
                            return 0;
                        })
                        .then(ClientCommands.argument("newTone", StringArgumentType.greedyString())
                                .executes(ctx -> handleAddTone(ctx.getSource(), StringArgumentType.getString(ctx, "newTone")))))
                .then(ClientCommands.literal("remove")
                        .executes(ctx -> {
                            ctx.getSource().sendError(Component.literal("§c[AIChat]§r Usage: /" + name + " remove <tone>"));
                            return 0;
                        })
                        .then(ClientCommands.argument("remTone", StringArgumentType.greedyString())
                                .executes(ctx -> handleRemoveTone(ctx.getSource(), StringArgumentType.getString(ctx, "remTone")))))
                .then(ClientCommands.argument("tone", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String arg = StringArgumentType.getString(ctx, "tone").trim();
                            if (arg.toLowerCase().startsWith("add ") || arg.equalsIgnoreCase("add")) {
                                String param = arg.length() > 4 ? arg.substring(4).trim() : "";
                                return handleAddTone(ctx.getSource(), param);
                            }
                            if (arg.toLowerCase().startsWith("remove ") || arg.equalsIgnoreCase("remove")) {
                                String param = arg.length() > 7 ? arg.substring(7).trim() : "";
                                return handleRemoveTone(ctx.getSource(), param);
                            }
                            return handleSetTone(ctx.getSource(), arg);
                        })));
    }

    private static int handleShowTone(FabricClientCommandSource src, String cmdName) {
        ModConfig cfg = ModConfig.get();
        String current = cfg.getTone();
        String options = String.join(", ", cfg.getAvailableTones());

        if (cfg.postToChat) {
            ChatSender.sendDirectMessage(resolveCurrentChatChannel(), "Current tone: " + current + ". Options: " + options);
        } else {
            src.sendFeedback(Component.literal("§b[AIChat] §7Current tone: §e" + current));
            src.sendFeedback(Component.literal("§7Available tones: §f" + options));
            src.sendFeedback(Component.literal("§7Use §f/" + cmdName + " <tone> §7to switch, §f/" + cmdName + " add <tone> §7to add, or §f/" + cmdName + " remove <tone> §7to remove."));
        }
        return 1;
    }

    private static int handleAddTone(FabricClientCommandSource src, String input) {
        if (input == null || input.isBlank()) {
            src.sendError(Component.literal("§c[AIChat]§r Tone name cannot be empty. Example: /settone add pirate"));
            return 0;
        }
        String toneName;
        String instruction = null;
        int colon = input.indexOf(':');
        if (colon != -1) {
            toneName = input.substring(0, colon).trim().toLowerCase();
            instruction = input.substring(colon + 1).trim();
        } else {
            toneName = input.trim().toLowerCase();
        }

        if (toneName.isEmpty()) {
            src.sendError(Component.literal("§c[AIChat]§r Tone name cannot be empty."));
            return 0;
        }
        if (toneName.length() > 30) {
            src.sendError(Component.literal("§c[AIChat]§r Tone name cannot exceed 30 characters."));
            return 0;
        }

        ModConfig cfg = ModConfig.get();
        boolean ok = cfg.addTone(toneName, instruction);
        if (!ok) {
            src.sendError(Component.literal("§c[AIChat]§r Failed to add tone."));
            return 0;
        }

        if (cfg.postToChat) {
            ChatSender.sendDirectMessage(resolveCurrentChatChannel(), "Added and set tone to '" + toneName + "'.");
        }
        src.sendFeedback(Component.literal("§b[AIChat]§r Added and set tone to §e" + toneName + "§r."));
        return 1;
    }

    private static int handleRemoveTone(FabricClientCommandSource src, String input) {
        if (input == null || input.isBlank()) {
            src.sendError(Component.literal("§c[AIChat]§r Usage: /settone remove <tone>"));
            return 0;
        }
        String toneName = input.trim().toLowerCase();
        if ("default".equals(toneName)) {
            if (ModConfig.get().postToChat) {
                ChatSender.sendDirectMessage(resolveCurrentChatChannel(), "Cannot remove the 'default' tone.");
            }
            src.sendError(Component.literal("§c[AIChat]§r Cannot remove the 'default' tone."));
            return 0;
        }

        ModConfig cfg = ModConfig.get();
        boolean removed = cfg.removeTone(toneName);
        if (!removed) {
            String msg = "Tone '" + toneName + "' not found. Options: " + String.join(", ", cfg.getAvailableTones());
            if (cfg.postToChat) {
                ChatSender.sendDirectMessage(resolveCurrentChatChannel(), msg);
            }
            src.sendError(Component.literal("§c[AIChat]§r " + msg));
            return 0;
        }

        String msg = "Removed tone '" + toneName + "'. Current tone: " + cfg.getTone() + ".";
        if (cfg.postToChat) {
            ChatSender.sendDirectMessage(resolveCurrentChatChannel(), msg);
        }
        src.sendFeedback(Component.literal("§b[AIChat]§r " + msg));
        return 1;
    }

    private static int handleSetTone(FabricClientCommandSource src, String arg) {
        String norm = ModConfig.normalizeTone(arg);
        ModConfig cfg = ModConfig.get();
        if (norm == null) {
            String msg = "Unknown tone '" + arg + "'. Options: " + String.join(", ", cfg.getAvailableTones());
            if (cfg.postToChat) {
                ChatSender.sendDirectMessage(resolveCurrentChatChannel(), msg);
            }
            src.sendError(Component.literal("§c[AIChat]§r " + msg));
            return 0;
        }

        cfg.tone = norm;
        cfg.save();

        if (cfg.postToChat) {
            ChatSender.sendDirectMessage(resolveCurrentChatChannel(), "Tone set to '" + norm + "'.");
        }
        src.sendFeedback(Component.literal("§b[AIChat]§r Tone set to §e" + norm + "§r."));
        return 1;
    }

    private static ChatChannel resolveCurrentChatChannel() {
        String chStr = resolveDefaultChannel();
        return switch (chStr) {
            case "guild" -> ChatChannel.GUILD;
            case "party" -> ChatChannel.PARTY;
            default -> ChatChannel.PUBLIC;
        };
    }

    private static void sendResult(FabricClientCommandSource source, ConversationMemory.Result res) {
        if (res.ok()) {
            source.sendFeedback(Component.literal("§b[AIChat]§r " + res.message()));
        } else {
            source.sendError(Component.literal("§c[AIChat]§r " + res.message()));
        }
    }
}
