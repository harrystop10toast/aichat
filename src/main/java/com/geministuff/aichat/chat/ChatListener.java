package com.geministuff.aichat.chat;

import com.geministuff.aichat.config.ModConfig;
import com.geministuff.aichat.ai.AiService;
import com.geministuff.aichat.ai.ConversationMemory;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Handles incoming chat lines from Guild, Party, and Public chat.
 * Supported commands:
 *   !ai <prompt>           - asks the AI a question
 *   !newchat / !NewChat    - creates a fresh chat session in this channel
 *   !destroy / !Destroy    - deletes current chat session in this channel
 *   !allchats / !allChats  - lists all available chats briefly in one line
 *   !settitle <prompt>     - renames currently selected chat (max 20 chars)
 *   !selectchat <#>        - switches active chat in this channel
 */
public class ChatListener {

    private static final Pattern FORMAT_CODES = Pattern.compile("(?i)[§&][0-9A-FK-OR]");

    private static final ExecutorService AI_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aichat-worker");
        t.setDaemon(true);
        return t;
    });

    private static final Map<ChatChannel, Long> LAST_AI_TIME = new ConcurrentHashMap<>();

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                handleLine(message.getString()));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleLine(message.getString());
        });
    }

    private static void handleLine(String rawLine) {
        if (!ModConfig.get().postToChat) return; // "only show to me" mode: ! commands disabled, use / commands
        String line = FORMAT_CODES.matcher(rawLine).replaceAll("").trim();
        if (line.isEmpty()) return;

        if (line.contains(": AI:")) return; // never react to our own replies

        ChatChannel channel;
        if (startsWithIgnoreCase(line, "Guild >")) {
            channel = ChatChannel.GUILD;
        } else if (startsWithIgnoreCase(line, "Party >")) {
            channel = ChatChannel.PARTY;
        } else {
            channel = ChatChannel.PUBLIC;
        }

        ModConfig cfg = ModConfig.get();
        boolean enabled = switch (channel) {
            case GUILD -> cfg.guildEnabled;
            case PARTY -> cfg.partyEnabled;
            case PUBLIC -> cfg.publicEnabled;
        };
        if (!enabled) return;

        // Check for ChatGPT-style management commands first:
        if (containsCommand(line, "!newchat")) {
            ConversationMemory.Result res = ConversationMemory.newChat(channel.key);
            ChatSender.sendDirectMessage(channel, res.message());
            return;
        }

        if (containsCommand(line, "!destroy")) {
            ConversationMemory.Result res = ConversationMemory.destroyCurrentChat(channel.key);
            ChatSender.sendDirectMessage(channel, res.message());
            return;
        }

        if (containsCommand(line, "!allchats")) {
            String brief = ConversationMemory.formatAllChatsBrief(channel.key);
            ChatSender.sendDirectMessage(channel, brief);
            return;
        }

        int setTitleIdx = indexOfIgnoreCase(line, "!settitle ");
        if (setTitleIdx != -1) {
            String title = line.substring(setTitleIdx + "!settitle ".length()).trim();
            ConversationMemory.Result res = ConversationMemory.setTitle(channel.key, title);
            ChatSender.sendDirectMessage(channel, res.message());
            return;
        }

        int selectChatIdx = indexOfIgnoreCase(line, "!selectchat ");
        if (selectChatIdx != -1) {
            String arg = line.substring(selectChatIdx + "!selectchat ".length()).trim();
            if (arg.startsWith("#")) arg = arg.substring(1).trim();
            try {
                int id = Integer.parseInt(arg.split("\\s+")[0]);
                ConversationMemory.Result res = ConversationMemory.selectChat(channel.key, id);
                ChatSender.sendDirectMessage(channel, res.message());
            } catch (Exception e) {
                ChatSender.sendDirectMessage(channel, "Usage: !selectChat <1-6>");
            }
            return;
        }

        int setToneIdx = indexOfIgnoreCase(line, "!settone");
        if (setToneIdx != -1) {
            String arg = line.substring(setToneIdx + "!settone".length()).trim();
            if (arg.isEmpty()) {
                String msg = "Current tone: " + cfg.getTone() + ". Options: " + String.join(", ", cfg.getAvailableTones());
                ChatSender.sendDirectMessage(channel, msg);
            } else if (arg.toLowerCase().startsWith("add ") || arg.equalsIgnoreCase("add")) {
                String param = arg.length() > 4 ? arg.substring(4).trim() : "";
                if (param.isEmpty()) {
                    ChatSender.sendDirectMessage(channel, "Usage: !settone add <tone>");
                } else {
                    String toneName;
                    String instruction = null;
                    int colon = param.indexOf(':');
                    if (colon != -1) {
                        toneName = param.substring(0, colon).trim().toLowerCase();
                        instruction = param.substring(colon + 1).trim();
                    } else {
                        toneName = param.trim().toLowerCase();
                    }
                    if (toneName.isEmpty() || toneName.length() > 30) {
                        ChatSender.sendDirectMessage(channel, "Invalid tone name (1-30 chars).");
                    } else {
                        cfg.addTone(toneName, instruction);
                        ChatSender.sendDirectMessage(channel, "Added and set tone to '" + toneName + "'.");
                    }
                }
            } else if (arg.toLowerCase().startsWith("remove ") || arg.equalsIgnoreCase("remove")) {
                String param = arg.length() > 7 ? arg.substring(7).trim() : "";
                if (param.isEmpty()) {
                    ChatSender.sendDirectMessage(channel, "Usage: !settone remove <tone>");
                } else if ("default".equalsIgnoreCase(param.trim())) {
                    ChatSender.sendDirectMessage(channel, "Cannot remove the 'default' tone.");
                } else {
                    boolean ok = cfg.removeTone(param.trim());
                    if (ok) {
                        ChatSender.sendDirectMessage(channel, "Removed tone '" + param.trim().toLowerCase() + "'. Current tone: " + cfg.getTone() + ".");
                    } else {
                        ChatSender.sendDirectMessage(channel, "Tone '" + param.trim() + "' not found. Options: " + String.join(", ", cfg.getAvailableTones()));
                    }
                }
            } else {
                String norm = ModConfig.normalizeTone(arg);
                if (norm == null) {
                    ChatSender.sendDirectMessage(channel, "Unknown tone '" + arg + "'. Options: " + String.join(", ", cfg.getAvailableTones()));
                } else {
                    cfg.tone = norm;
                    cfg.save();
                    ChatSender.sendDirectMessage(channel, "Tone set to '" + norm + "'.");
                }
            }
            return;
        }

        // Standard AI query: !ai <prompt>
        int cmdIdx = indexOfIgnoreCase(line, "!ai ");
        if (cmdIdx == -1) return;

        // Cooldown check: 4-6 seconds between !ai queries on this channel
        long now = System.currentTimeMillis();
        Long last = LAST_AI_TIME.get(channel);
        if (last != null && now - last < 4000) {
            return; // ignore spam requests arriving within 4s cooldown
        }
        LAST_AI_TIME.put(channel, now);

        String prompt = line.substring(cmdIdx + 4).trim();
        if (prompt.isEmpty()) return;

        String beforeCommand = line.substring(0, cmdIdx);
        String username = guessUsername(channel, beforeCommand, line);

        CompletableFuture
                .supplyAsync(() -> AiService.generate(channel.key, username, prompt, true), AI_POOL)
                .exceptionally(ex -> new AiService.Result(false,
                        "ERROR: unexpected " + ex.getClass().getSimpleName()
                                + " - check .minecraft/logs/latest.log for details"))
                .thenAccept(result -> ChatSender.sendDelayed(channel, username, prompt, result));
    }

    private static boolean containsCommand(String line, String cmd) {
        int idx = indexOfIgnoreCase(line, cmd);
        if (idx == -1) return false;
        int after = idx + cmd.length();
        return after >= line.length() || Character.isWhitespace(line.charAt(after));
    }

    private static String guessUsername(ChatChannel channel, String beforeCommand, String fullLine) {
        int colon = beforeCommand.lastIndexOf(':');
        if (colon == -1) return "player";
        String head = beforeCommand.substring(0, colon).trim();
        int lastSpace = head.lastIndexOf(' ');
        String name = lastSpace == -1 ? head : head.substring(lastSpace + 1);
        return name.isBlank() ? "player" : name;
    }

    private static boolean startsWithIgnoreCase(String str, String prefix) {
        return str.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static int indexOfIgnoreCase(String str, String needle) {
        return str.toLowerCase().indexOf(needle.toLowerCase());
    }
}
