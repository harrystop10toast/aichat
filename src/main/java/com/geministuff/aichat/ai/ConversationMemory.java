package com.geministuff.aichat.ai;

import com.geministuff.aichat.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-chat memory system for both Gemini and Groq, separated across channels
 * (guild, party, public, private).
 *
 * Each (provider, channel) pair supports up to 6 distinct chat sessions (slots 1 to 6).
 * Each session is stored in its own JSON file:
 *   config/aichat_memory/<provider>_<channel>_chat<id>.json
 * For example:
 * 2 providers x 4 channels x 6 chats = 48 files max.
 *
 * Memory is doubled from 6 exchanges to 12 exchanges (24 turns max per chat).
 */
public class ConversationMemory {

    /** Doubled memory: 12 user+model pairs = 24 turns per chat. */
    public static final int MAX_TURNS = 12;
    public static final int MAX_CHATS_PER_CHANNEL = 6;
    public static final int MAX_TITLE_LEN = 20;

    private static final Path BASE_DIR = FabricLoader.getInstance().getConfigDir().resolve("aichat_memory");
    private static final Path LEGACY_BASE_DIR = FabricLoader.getInstance().getConfigDir().resolve("geminichat_memory");
    private static final Path ACTIVE_FILE = BASE_DIR.resolve("active_chats.json");
    private static final Path LEGACY_FILE = FabricLoader.getInstance().getConfigDir().resolve("geminichat.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Turn(String role, String username, String text) {}
    public record Result(boolean ok, String message) {}

    public static class ChatSession {
        public int id;
        public String title;
        public Deque<Turn> turns = new ArrayDeque<>();

        public ChatSession() {}

        public ChatSession(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private static final Type ACTIVE_MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();

    // Key: "<provider>_<channel>" -> Map<Integer (1..6), ChatSession>
    private static final Map<String, Map<Integer, ChatSession>> SESSIONS = new ConcurrentHashMap<>();
    // Key: "<provider>_<channel>" -> currently active chat ID (1..6)
    private static final Map<String, Integer> ACTIVE_CHATS = new ConcurrentHashMap<>();

    static {
        loadAll();
    }

    private static String currentProvider() {
        ModConfig cfg = ModConfig.get();
        return (cfg != null && cfg.isGroq()) ? "groq" : "gemini";
    }

    private static String sessionKey(String provider, String channel) {
        return provider.toLowerCase() + "_" + channel.toLowerCase();
    }

    public static synchronized Deque<Turn> get(String channel) {
        return get(currentProvider(), channel);
    }

    public static synchronized Deque<Turn> get(String provider, String channel) {
        ChatSession session = getOrCreateActiveSession(provider, channel);
        return session.turns;
    }

    public static synchronized void addExchange(String channel, String username, String userText, String modelReply) {
        addExchange(currentProvider(), channel, username, userText, modelReply);
    }

    public static synchronized void addExchange(String provider, String channel, String username, String userText, String modelReply) {
        ChatSession session = getOrCreateActiveSession(provider, channel);
        session.turns.addLast(new Turn("user", username, userText));
        session.turns.addLast(new Turn("model", provider.equalsIgnoreCase("groq") ? "Groq" : "Gemini", modelReply));
        while (session.turns.size() > MAX_TURNS * 2) {
            session.turns.pollFirst();
        }
        saveSession(provider, channel, session);
    }

    public static synchronized Result newChat(String channel) {
        return newChat(currentProvider(), channel);
    }

    public static synchronized Result newChat(String provider, String channel) {
        String key = sessionKey(provider, channel);
        Map<Integer, ChatSession> map = SESSIONS.computeIfAbsent(key, k -> new ConcurrentHashMap<>());

        if (map.size() >= MAX_CHATS_PER_CHANNEL) {
            return new Result(false, "Cannot create chat: max " + MAX_CHATS_PER_CHANNEL
                    + " chats reached. Use /destroy or !destroy to delete one.");
        }

        // Find the lowest unused ID between 1 and 6
        int newId = -1;
        for (int i = 1; i <= MAX_CHATS_PER_CHANNEL; i++) {
            if (!map.containsKey(i)) {
                newId = i;
                break;
            }
        }
        if (newId == -1) {
            return new Result(false, "Cannot create chat: no available chat slot.");
        }

        ChatSession session = new ChatSession(newId, "Chat " + newId);
        map.put(newId, session);
        ACTIVE_CHATS.put(key, newId);

        saveSession(provider, channel, session);
        saveActiveChats();

        return new Result(true, "Created Chat #" + newId + " (selected).");
    }

    public static synchronized Result destroyCurrentChat(String channel) {
        return destroyCurrentChat(currentProvider(), channel);
    }

    public static synchronized Result destroyCurrentChat(String provider, String channel) {
        String key = sessionKey(provider, channel);
        Map<Integer, ChatSession> map = SESSIONS.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        int activeId = ACTIVE_CHATS.getOrDefault(key, 1);

        ChatSession removed = map.remove(activeId);
        deleteSessionFile(provider, channel, activeId);

        String oldName = (removed != null && removed.title != null) ? removed.title : ("Chat " + activeId);

        if (map.isEmpty()) {
            // If all were deleted, re-create a fresh Chat 1 so there's always an active chat
            ChatSession fresh = new ChatSession(1, "Chat 1");
            map.put(1, fresh);
            ACTIVE_CHATS.put(key, 1);
            saveSession(provider, channel, fresh);
            saveActiveChats();
            return new Result(true, "Deleted Chat #" + activeId + " (\"" + oldName + "\"). Reset to fresh Chat #1.");
        } else {
            // Select lowest remaining ID
            int nextId = map.keySet().stream().min(Integer::compareTo).orElse(1);
            ACTIVE_CHATS.put(key, nextId);
            saveActiveChats();
            ChatSession nextSession = map.get(nextId);
            String nextTitle = nextSession != null ? nextSession.title : ("Chat " + nextId);
            return new Result(true, "Deleted Chat #" + activeId + " (\"" + oldName + "\"). Switched to Chat #" + nextId + " (\"" + nextTitle + "\").");
        }
    }

    public static synchronized Result setTitle(String channel, String newTitle) {
        return setTitle(currentProvider(), channel, newTitle);
    }

    public static synchronized Result setTitle(String provider, String channel, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            return new Result(false, "Title cannot be empty.");
        }
        newTitle = newTitle.trim();
        if (newTitle.length() > MAX_TITLE_LEN) {
            newTitle = newTitle.substring(0, MAX_TITLE_LEN);
        }

        ChatSession session = getOrCreateActiveSession(provider, channel);
        session.title = newTitle;
        saveSession(provider, channel, session);

        return new Result(true, "Chat #" + session.id + " title set to \"" + newTitle + "\".");
    }

    public static synchronized Result selectChat(String channel, int id) {
        return selectChat(currentProvider(), channel, id);
    }

    public static synchronized Result selectChat(String provider, String channel, int id) {
        if (id < 1 || id > MAX_CHATS_PER_CHANNEL) {
            return new Result(false, "Chat ID must be between 1 and " + MAX_CHATS_PER_CHANNEL + ".");
        }
        String key = sessionKey(provider, channel);
        Map<Integer, ChatSession> map = SESSIONS.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        if (!map.containsKey(id)) {
            return new Result(false, "Chat #" + id + " does not exist. Use !allChats to see chats.");
        }

        ACTIVE_CHATS.put(key, id);
        saveActiveChats();

        ChatSession s = map.get(id);
        return new Result(true, "Switched to Chat #" + id + " (\"" + s.title + "\").");
    }

    public static synchronized List<ChatSession> listChats(String channel) {
        return listChats(currentProvider(), channel);
    }

    public static synchronized List<ChatSession> listChats(String provider, String channel) {
        String key = sessionKey(provider, channel);
        getOrCreateActiveSession(provider, channel); // ensure at least 1 exists
        Map<Integer, ChatSession> map = SESSIONS.getOrDefault(key, Map.of());
        List<ChatSession> list = new ArrayList<>(map.values());
        list.sort((a, b) -> Integer.compare(a.id, b.id));
        return list;
    }

    public static synchronized int getActiveChatId(String channel) {
        return getActiveChatId(currentProvider(), channel);
    }

    public static synchronized int getActiveChatId(String provider, String channel) {
        String key = sessionKey(provider, channel);
        return ACTIVE_CHATS.getOrDefault(key, 1);
    }

    /**
     * Brief format for in-game server chat (!allchats):
     * "Chats: 1: Title (*), 2: Title, 3: Title"
     * Fits in one single Minecraft chat line under 250 characters.
     */
    public static synchronized String formatAllChatsBrief(String channel) {
        return formatAllChatsBrief(currentProvider(), channel);
    }

    public static synchronized String formatAllChatsBrief(String provider, String channel) {
        List<ChatSession> sessions = listChats(provider, channel);
        int activeId = getActiveChatId(provider, channel);
        StringBuilder sb = new StringBuilder();
        sb.append("Chats: ");
        for (int i = 0; i < sessions.size(); i++) {
            ChatSession s = sessions.get(i);
            if (i > 0) sb.append(", ");
            sb.append(s.id).append(": ").append(s.title);
            if (s.id == activeId) sb.append(" (*)");
        }
        return sb.toString();
    }

    /**
     * Formatted column layout for client command (/allchats):
     * Left column ID, right column name, with active indicator.
     */
    public static synchronized List<String> formatAllChatsDetailed(String provider, String channel) {
        List<ChatSession> sessions = listChats(provider, channel);
        int activeId = getActiveChatId(provider, channel);
        List<String> lines = new ArrayList<>();
        lines.add("§b=== AIChat Chats [" + provider + " | " + channel + "] ===");
        lines.add("§eID     §7| §eActive §7| §eName");
        for (ChatSession s : sessions) {
            String activeMark = (s.id == activeId) ? "§a [*]  " : "§7 [ ]  ";
            lines.add(String.format("§f#%-5d §7|%s§7| §f%s", s.id, activeMark, s.title));
        }
        lines.add("§7Use §f/selectChat <id> §7or §f!selectChat <id> §7to switch.");
        return lines;
    }

    // ------------------------------------------------------------------ Internal Storage

    private static synchronized ChatSession getOrCreateActiveSession(String provider, String channel) {
        String key = sessionKey(provider, channel);
        Map<Integer, ChatSession> map = SESSIONS.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        int activeId = ACTIVE_CHATS.computeIfAbsent(key, k -> 1);

        ChatSession session = map.get(activeId);
        if (session == null) {
            if (!map.isEmpty()) {
                activeId = map.keySet().stream().min(Integer::compareTo).orElse(1);
                ACTIVE_CHATS.put(key, activeId);
                session = map.get(activeId);
            }
            if (session == null) {
                activeId = 1;
                session = new ChatSession(1, "Chat 1");
                map.put(1, session);
                ACTIVE_CHATS.put(key, 1);
                saveSession(provider, channel, session);
                saveActiveChats();
            }
        }
        return session;
    }

    private static Path sessionFilePath(String provider, String channel, int id) {
        return BASE_DIR.resolve(provider.toLowerCase() + "_" + channel.toLowerCase() + "_chat" + id + ".json");
    }

    private static void saveSession(String provider, String channel, ChatSession session) {
        try {
            Files.createDirectories(BASE_DIR);
            Path file = sessionFilePath(provider, channel, session.id);
            Files.writeString(file, GSON.toJson(session), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void deleteSessionFile(String provider, String channel, int id) {
        try {
            Path file = sessionFilePath(provider, channel, id);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void saveActiveChats() {
        try {
            Files.createDirectories(BASE_DIR);
            Files.writeString(ACTIVE_FILE, GSON.toJson(ACTIVE_CHATS, ACTIVE_MAP_TYPE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadAll() {
        try {
            if (!Files.exists(BASE_DIR) && Files.exists(LEGACY_BASE_DIR)) {
                try {
                    Files.createDirectories(BASE_DIR);
                    try (var s = Files.list(LEGACY_BASE_DIR)) {
                        s.forEach(src -> {
                            try {
                                Files.copy(src, BASE_DIR.resolve(src.getFileName()));
                            } catch (Exception ignored) {}
                        });
                    }
                } catch (Exception ignored) {}
            }
            Files.createDirectories(BASE_DIR);

            // 1. Load active chat selections
            if (Files.exists(ACTIVE_FILE)) {
                try {
                    String json = Files.readString(ACTIVE_FILE, StandardCharsets.UTF_8);
                    Map<String, Integer> loaded = GSON.fromJson(json, ACTIVE_MAP_TYPE);
                    if (loaded != null) ACTIVE_CHATS.putAll(loaded);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // 2. Scan and load all session files in BASE_DIR
            try (var stream = Files.list(BASE_DIR)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")
                                && !p.getFileName().toString().equals("active_chats.json"))
                        .forEach(p -> {
                            String fname = p.getFileName().toString();
                            // Expected format: <provider>_<channel>_chat<id>.json
                            try {
                                String withoutExt = fname.substring(0, fname.length() - 5);
                                int chatIdx = withoutExt.lastIndexOf("_chat");
                                if (chatIdx != -1) {
                                    String prefix = withoutExt.substring(0, chatIdx); // <provider>_<channel>
                                    int id = Integer.parseInt(withoutExt.substring(chatIdx + 5));
                                    String json = Files.readString(p, StandardCharsets.UTF_8);
                                    ChatSession session = GSON.fromJson(json, ChatSession.class);
                                    if (session != null) {
                                        session.id = id;
                                        if (session.title == null || session.title.isBlank()) {
                                            session.title = "Chat " + id;
                                        }
                                        if (session.turns == null) session.turns = new ArrayDeque<>();
                                        SESSIONS.computeIfAbsent(prefix.toLowerCase(), k -> new ConcurrentHashMap<>())
                                                .put(id, session);
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
            }

            // 3. Migrate legacy single-file memory if new directory was empty
            if (SESSIONS.isEmpty() && Files.exists(LEGACY_FILE)) {
                migrateLegacyMemory();
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void migrateLegacyMemory() {
        try {
            String json = Files.readString(LEGACY_FILE, StandardCharsets.UTF_8);
            Type legacyType = new TypeToken<Map<String, List<Turn>>>() {}.getType();
            Map<String, List<Turn>> plain = GSON.fromJson(json, legacyType);
            if (plain != null) {
                for (var entry : plain.entrySet()) {
                    String channel = entry.getKey();
                    for (String prov : new String[]{"gemini", "groq"}) {
                        ChatSession s = new ChatSession(1, "Chat 1");
                        s.turns.addAll(entry.getValue());
                        while (s.turns.size() > MAX_TURNS * 2) s.turns.pollFirst();
                        SESSIONS.computeIfAbsent(sessionKey(prov, channel), k -> new ConcurrentHashMap<>()).put(1, s);
                        ACTIVE_CHATS.put(sessionKey(prov, channel), 1);
                        saveSession(prov, channel, s);
                    }
                }
                saveActiveChats();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
