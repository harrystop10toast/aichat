package com.geministuff.aichat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the config screen edits. Saved to config/aichat.json. */
public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("aichat.json");
    private static final Path LEGACY_FILE = FabricLoader.getInstance().getConfigDir().resolve("geminichat.json");
    private static final int MAX_RECENT = 8;

    /** "gemini" or "groq" */
    public String provider = "gemini";

    public String geminiApiKey = "";
    public String geminiModel = "gemini-3.5-flash-lite";
    public String groqApiKey = "";
    public String groqModel = "llama-3.3-70b-versatile";

    /** Free key from https://developer.hypixel.net - used by the get_skyblock_stats tool. */
    public String hypixelApiKey = "";

    /**
     * Null/true = on (so older geminichat.json files that lack this field get
     * internet search). Set false in /AIconfig to disable the search_web tool.
     */
    public Boolean internetEnabled = Boolean.TRUE;

    /** Base URL of a SearXNG instance (Gemini, and Groq when the model has no browser_search). */
    public String searxngUrl = "http://localhost:8080";

    /**
     * AI persona / tone: default, shakespearean, nerd, toxic gamer, gen-z, chaotic, villain, philosopher, or custom.
     */
    public String tone = "default";

    public static final List<String> DEFAULT_TONES = List.of(
            "default", "shakespearean", "nerd", "toxic gamer", "gen-z", "chaotic", "villain", "philosopher"
    );

    public static final List<String> AVAILABLE_TONES = DEFAULT_TONES;

    public List<String> availableTones = new ArrayList<>(DEFAULT_TONES);
    public Map<String, String> customToneInstructions = new LinkedHashMap<>();

    public List<String> getAvailableTones() {
        if (availableTones == null || availableTones.isEmpty()) {
            availableTones = new ArrayList<>(DEFAULT_TONES);
        }
        return availableTones;
    }

    public String getTone() {
        String t = (tone == null || tone.isBlank()) ? "default" : tone.trim().toLowerCase();
        for (String av : getAvailableTones()) {
            if (av.equalsIgnoreCase(t)) return av;
        }
        return "default";
    }

    public static String normalizeTone(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase();
        ModConfig cfg = get();
        for (String av : cfg.getAvailableTones()) {
            if (av.equalsIgnoreCase(s)) return av;
        }
        String alias = switch (s) {
            case "normal", "standard" -> "default";
            case "shakespeare", "shakespearan" -> "shakespearean";
            case "erm-actually", "erm_actually", "erm actually", "ermactually" -> "nerd";
            case "toxic", "toxic gamer", "toxic_gamer", "gamer" -> "toxic gamer";
            case "genz", "gen-z", "gen_z", "gen z", "genz like", "brainrot" -> "gen-z";
            case "chaos", "unhinged" -> "chaotic";
            case "evil", "mastermind" -> "villain";
            case "philosophy", "deep" -> "philosopher";
            default -> null;
        };
        if (alias != null) {
            for (String av : cfg.getAvailableTones()) {
                if (av.equalsIgnoreCase(alias)) return av;
            }
        }
        return null;
    }

    public synchronized boolean addTone(String toneName, String instruction) {
        if (toneName == null || toneName.isBlank()) return false;
        String name = toneName.trim().toLowerCase();
        List<String> list = getAvailableTones();
        if (!list.contains(name)) {
            list.add(name);
        }
        if (instruction != null && !instruction.isBlank()) {
            if (customToneInstructions == null) customToneInstructions = new LinkedHashMap<>();
            customToneInstructions.put(name, instruction.trim());
        }
        this.tone = name;
        save();
        return true;
    }

    public synchronized boolean removeTone(String toneName) {
        if (toneName == null || toneName.isBlank()) return false;
        String name = toneName.trim().toLowerCase();
        if ("default".equals(name)) return false; // cannot delete default

        boolean removed = false;
        if (availableTones != null) {
            removed = availableTones.removeIf(t -> t.equalsIgnoreCase(name));
        }
        if (customToneInstructions != null) {
            customToneInstructions.remove(name);
        }
        if (this.tone != null && this.tone.equalsIgnoreCase(name)) {
            this.tone = "default";
        }
        if (removed) {
            save();
        }
        return removed;
    }

    /** Most-recently-used first, capped at MAX_RECENT. Feeds the "Recent models" button. */
    public List<String> recentGeminiModels = new ArrayList<>();
    public List<String> recentGroqModels = new ArrayList<>();

    // Checked  -> replies are sent to the server chat (/gc, /pc, or plain), !ai enabled, /ai disabled.
    // Unchecked -> "only show to me": /ai enabled (private, unlimited length), !ai disabled.
    public boolean postToChat = false;

    public boolean guildEnabled = true;
    public boolean partyEnabled = true;
    public boolean publicEnabled = true;

    private static ModConfig instance;

    public static ModConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static ModConfig load() {
        Path target = Files.exists(FILE) ? FILE : (Files.exists(LEGACY_FILE) ? LEGACY_FILE : null);
        if (target != null) {
            try {
                ModConfig cfg = GSON.fromJson(Files.readString(target, StandardCharsets.UTF_8), ModConfig.class);
                if (cfg != null) {
                    if (cfg.recentGeminiModels == null) cfg.recentGeminiModels = new ArrayList<>();
                    if (cfg.recentGroqModels == null) cfg.recentGroqModels = new ArrayList<>();
                    if (cfg.availableTones == null || cfg.availableTones.isEmpty()) cfg.availableTones = new ArrayList<>(DEFAULT_TONES);
                    if (cfg.customToneInstructions == null) cfg.customToneInstructions = new LinkedHashMap<>();
                    if (cfg.searxngUrl == null || cfg.searxngUrl.isBlank()) cfg.searxngUrl = "http://localhost:8080";
                    instance = cfg;
                    return cfg;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        instance = new ModConfig();
        return instance;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ---- provider helpers ----
    public boolean internetOn() { return internetEnabled == null || internetEnabled; }

    public boolean isGroq() { return "groq".equalsIgnoreCase(provider); }
    public String providerName() { return isGroq() ? "Groq" : "Gemini"; }
    public String currentKey() { return (isGroq() ? groqApiKey : geminiApiKey).trim(); }
    public String currentModel() { return (isGroq() ? groqModel : geminiModel).trim(); }
    public List<String> recentForCurrent() { return isGroq() ? recentGroqModels : recentGeminiModels; }

    public void setCurrentKey(String key) {
        if (isGroq()) groqApiKey = key; else geminiApiKey = key;
    }

    public void setCurrentModel(String model) {
        model = model.trim();
        if (isGroq()) groqModel = model; else geminiModel = model;
        remember(recentForCurrent(), model);
    }

    /** Move-to-front, de-duplicated (case-insensitive), capped. */
    public static void remember(List<String> list, String model) {
        if (model == null || model.isBlank()) return;
        list.removeIf(m -> m.equalsIgnoreCase(model));
        list.add(0, model);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
    }
}
