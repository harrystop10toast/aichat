package com.geministuff.aichat.tools;

import com.geministuff.aichat.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Looks up a player's Hypixel SkyBlock stats via the OFFICIAL Hypixel
 * Developer API (api.hypixel.net) instead of a third-party site - no bot
 * blocking, since it's a real authenticated API. Two calls are needed:
 *
 *   1. Mojang API to turn a username into a UUID (api.hypixel.net needs UUIDs).
 *   2. GET https://api.hypixel.net/v2/skyblock/profiles?uuid=<uuid>
 *      with header "API-Key: <your key>" - get a free one at
 *      https://developer.hypixel.net (requires linking your Discord).
 *
 * Response shape (confirmed against the hypixel-sdk Rust crate's published
 * models, which mirror the official API's real field names):
 *   {"success":true,"profiles":[{"profile_id":..,"cute_name":..,"selected":bool,
 *     "members":{"<uuid>":{ "dungeons":{"secrets":123,"dungeon_types":{...},
 *       "player_classes":{...}}, "player_data":{"experience":{...},
 *       "unlocked_coll_tiers":[...]}, "leveling":{"experience":..},
 *       "currencies":{"coin_purse":..,"essence":{...}},
 *       "slayer":{"slayer_bosses":{...}}, "pets_data":{"pets":[...]},
 *       "mining_core":{...}, ...much more... }}}]}
 *
 * Rather than hand-picking every field (which would break the moment Hypixel
 * adds/renames something), the member's data is pruned generically (strip
 * huge strings/arrays - inventory NBT blobs etc) and handed to the AI whole,
 * with a short map of where the common stats live to help it along.
 */
public class HypixelApiTool {

    private static final Logger LOG = LoggerFactory.getLogger("AIChat");
    private static final Path DEBUG_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("aichat_last_skyblock.json");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private static final int MAX_STRING_LEN = 200;
    private static final int MAX_ARRAY_LEN = 25;
    private static final int MAX_OUTPUT_CHARS = 4_000;
    private static final int MAX_MATCH_VALUE = 800;
    private static final Set<String> BLOAT_KEYS = Set.of(
            "best_runs", "treasures", "dungeon_journal", "last_dungeon_run", "rooms"
    );

    public record Result(boolean ok, String summary) {}

    public static Result fetchStats(String username, java.util.List<String> keywords) {
        if (username == null || username.isBlank()) {
            return new Result(false, "No username was given.");
        }
        String apiKey = ModConfig.get().hypixelApiKey.trim();
        if (apiKey.isBlank()) {
            return new Result(false, "No Hypixel API key set. Get a free one at https://developer.hypixel.net "
                    + "(links to your Discord) and set it in /aiconfig.");
        }

        try {
            String uuid = resolveUuid(username.trim());
            if (uuid == null) {
                return new Result(false, "Could not find a Minecraft account named \"" + username + "\".");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.hypixel.net/v2/skyblock/profiles?uuid=" + uuid))
                    .timeout(Duration.ofSeconds(15))
                    .header("API-Key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 403) {
                return new Result(false, "Hypixel API rejected the API key (HTTP 403) - check it's correct in /aiconfig.");
            }
            if (response.statusCode() == 429) {
                return new Result(false, "Hypixel API rate-limited this key (HTTP 429). Try again shortly.");
            }
            if (response.statusCode() != 200) {
                return new Result(false, "Hypixel API returned HTTP " + response.statusCode() + ".");
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                String cause = root.has("cause") && !root.get("cause").isJsonNull() ? root.get("cause").getAsString() : "unknown reason";
                return new Result(false, "Hypixel API request failed: " + cause);
            }
            if (!root.has("profiles") || root.get("profiles").isJsonNull() || root.getAsJsonArray("profiles").isEmpty()) {
                return new Result(false, "\"" + username + "\" has no SkyBlock profiles.");
            }

            JsonArray profiles = root.getAsJsonArray("profiles");
            JsonObject chosenProfile = null;
            String cuteName = "";
            for (JsonElement el : profiles) {
                JsonObject p = el.getAsJsonObject();
                if (p.has("selected") && !p.get("selected").isJsonNull() && p.get("selected").getAsBoolean()) {
                    chosenProfile = p;
                    cuteName = p.has("cute_name") ? p.get("cute_name").getAsString() : "";
                    break;
                }
            }
            if (chosenProfile == null) { // no profile flagged "selected" - fall back to the first one returned
                chosenProfile = profiles.get(0).getAsJsonObject();
                cuteName = chosenProfile.has("cute_name") ? chosenProfile.get("cute_name").getAsString() : "";
            }

            if (!chosenProfile.has("members") || !chosenProfile.getAsJsonObject("members").has(uuid)) {
                return new Result(false, "Could not find member data for \"" + username + "\" in their profile.");
            }
            JsonObject memberData = chosenProfile.getAsJsonObject("members").getAsJsonObject(uuid);

            JsonObject curated = new JsonObject();
            for (String key : new String[] {
                    "dungeons", "player_data", "leveling", "currencies",
                    "slayer", "pets_data", "mining_core", "collection"
            }) {
                if (memberData.has(key)) curated.add(key, memberData.get(key));
            }
            stripBloat(curated);

            String dungeonLines = summarizeDungeons(curated.has("dungeons") ? curated.get("dungeons") : null);

            StringBuilder quickStats = new StringBuilder();
            JsonObject dungeonsObj = curated.has("dungeons") && curated.get("dungeons").isJsonObject()
                    ? curated.getAsJsonObject("dungeons") : null;
            if (dungeonsObj != null && dungeonsObj.has("secrets") && !dungeonsObj.get("secrets").isJsonNull()) {
                quickStats.append("Dungeon secrets found: ").append(dungeonsObj.get("secrets").getAsLong()).append("\n");
            }
            if (curated.has("leveling")) {
                JsonObject leveling = curated.getAsJsonObject("leveling");
                if (leveling.has("experience") && !leveling.get("experience").isJsonNull()) {
                    long level = (long) (leveling.get("experience").getAsDouble() / 100);
                    quickStats.append("SkyBlock level: ").append(level).append("\n");
                }
            }
            if (curated.has("currencies")) {
                JsonObject currencies = curated.getAsJsonObject("currencies");
                if (currencies.has("coin_purse") && !currencies.get("coin_purse").isJsonNull()) {
                    quickStats.append("Purse coins: ").append((long) currencies.get("coin_purse").getAsDouble()).append("\n");
                }
            }
            quickStats.append(dungeonLines);

            java.util.List<String> keywordsLower = new java.util.ArrayList<>();
            keywordsLower.add("secret");
            keywordsLower.add("master");
            keywordsLower.add("catacomb");
            keywordsLower.add("tier_completion");
            keywordsLower.add("times_played");
            keywordsLower.add("mobs_killed");
            if (keywords != null) {
                for (String k : keywords) {
                    if (k != null && !k.isBlank()) {
                        String kl = k.toLowerCase().trim();
                        if (kl.length() == 1 && kl.charAt(0) >= '0' && kl.charAt(0) <= '9') continue;
                        keywordsLower.add(kl);
                    }
                }
            }
            java.util.List<String> keywordMatches = new java.util.ArrayList<>();
            findMatches(memberData, "", keywordsLower, keywordMatches, 20);

            JsonElement pruned = prune(curated);
            String json = GSON.toJson(pruned);
            boolean truncated = false;
            if (json.length() > MAX_OUTPUT_CHARS) {
                json = json.substring(0, MAX_OUTPUT_CHARS);
                truncated = true;
            }

            writeDebugDump(username, uuid, cuteName, keywords, memberData, curated, dungeonLines, keywordMatches, json);

            StringBuilder sb = new StringBuilder();
            sb.append("Hypixel SkyBlock data for player \"").append(username)
                    .append("\", their currently-selected profile (\"").append(cuteName).append("\").\n\n");
            sb.append("READ THIS FIRST for dungeons. Hypixel stores MM separately from normal catacombs:\n");
            sb.append("- Normal floors F0-F7 = dungeon_types.catacombs.tier_completions / times_played\n");
            sb.append("- Master Mode M1-M7 = dungeon_types.master_catacombs.tier_completions / times_played\n");
            sb.append("- M7 completions = master_catacombs.tier_completions[\"7\"] (NOT catacombs floor 7, that is F7/Necron)\n");
            sb.append("- If master_catacombs is missing, the player has no MM data on this profile.\n\n");
            if (!quickStats.isEmpty()) {
                sb.append("Decoded stats (trust these over the JSON dump):\n").append(quickStats).append("\n");
            }
            if (!keywordMatches.isEmpty()) {
                sb.append("Extra fields matching keywords:\n");
                for (String line : keywordMatches) sb.append("- ").append(line).append("\n");
                sb.append("\n");
            }
            sb.append("Slim JSON dump (best_runs/treasures stripped so MM stats are not truncated):\n")
                    .append(json);
            if (truncated) sb.append("\n...(truncated)");

            LOG.info("[AIChat] skyblock {} profile={} toolChars={} dungeonPreview={}",
                    username, cuteName, sb.length(), dungeonLines.replace('\n', '|'));

            return new Result(true, sb.toString());

        } catch (Exception e) {
            LOG.warn("[AIChat] skyblock lookup failed for {}: {}", username, e.toString());
            return new Result(false, "Error looking up \"" + username + "\": " + e.getClass().getSimpleName());
        }
    }

    /** Drop run logs so master_catacombs / tier_completions survive in the dump. */
    private static void stripBloat(JsonElement el) {
        if (el == null || el.isJsonNull()) return;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (String k : new ArrayList<>(o.keySet())) {
                if (BLOAT_KEYS.contains(k)) o.remove(k);
                else stripBloat(o.get(k));
            }
        } else if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) stripBloat(child);
        }
    }

    /**
     * Hypixel keeps normal catacombs and master_catacombs as sibling objects.
     * Completions live in tier_completions["7"], not a field named "m7" or "kills".
     */
    private static String summarizeDungeons(JsonElement dungeonsEl) {
        StringBuilder sb = new StringBuilder();
        if (dungeonsEl == null || !dungeonsEl.isJsonObject()) {
            sb.append("No dungeons object in this profile.\n");
            return sb.toString();
        }
        JsonObject dungeons = dungeonsEl.getAsJsonObject();
        if (!dungeons.has("dungeon_types") || !dungeons.get("dungeon_types").isJsonObject()) {
            sb.append("dungeons.dungeon_types missing. dungeons keys: ").append(dungeons.keySet()).append("\n");
            return sb.toString();
        }
        JsonObject types = dungeons.getAsJsonObject("dungeon_types");
        sb.append("dungeon_types keys: ").append(types.keySet()).append("\n");
        if (types.has("catacombs") && types.get("catacombs").isJsonObject()) {
            sb.append(summarizeDungeonKind("Normal Catacombs (F floors, F7 = Necron)", "F",
                    types.getAsJsonObject("catacombs")));
        } else {
            sb.append("Normal catacombs object: MISSING\n");
        }
        if (types.has("master_catacombs") && types.get("master_catacombs").isJsonObject()) {
            sb.append(summarizeDungeonKind("Master Mode (M floors, M7 = Master Necron)", "M",
                    types.getAsJsonObject("master_catacombs")));
        } else {
            sb.append("master_catacombs object: MISSING (no Master Mode data on this profile)\n");
        }
        return sb.toString();
    }

    private static String summarizeDungeonKind(String title, String floorPrefix, JsonObject type) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(":\n");
        sb.append("  keys: ").append(type.keySet()).append("\n");
        addNum(sb, type, "experience", "  experience");
        addNum(sb, type, "highest_tier_completed", "  highest_tier_completed");
        addNum(sb, type, "mobs_killed", "  mobs_killed (if scalar)");
        addFloorMap(sb, type, "tier_completions", "  " + floorPrefix + " completions (tier_completions)");
        addFloorMap(sb, type, "times_played", "  " + floorPrefix + " times_played");
        addFloorMap(sb, type, "milestone_completions", "  " + floorPrefix + " milestone_completions");
        addFloorMap(sb, type, "mobs_killed", "  " + floorPrefix + " mobs_killed by floor");
        addFloorMap(sb, type, "watcher_kills", "  " + floorPrefix + " watcher_kills");
        if (type.has("tier_completions") && type.get("tier_completions").isJsonObject()) {
            JsonObject tc = type.getAsJsonObject("tier_completions");
            if (tc.has("7") && !tc.get("7").isJsonNull()) {
                sb.append("  >>> ").append(floorPrefix).append("7 completions = ").append(asNumber(tc.get("7"))).append("\n");
            } else {
                sb.append("  >>> ").append(floorPrefix).append("7 completions = 0 or absent (no floor 7 key)\n");
            }
        }
        return sb.toString();
    }

    private static void addNum(StringBuilder sb, JsonObject o, String key, String label) {
        if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive()) return;
        sb.append(label).append(": ").append(asNumber(o.get(key))).append("\n");
    }

    private static void addFloorMap(StringBuilder sb, JsonObject type, String field, String label) {
        if (!type.has(field) || !type.get(field).isJsonObject()) return;
        JsonObject map = type.getAsJsonObject(field);
        if (map.isEmpty()) return;
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.comparingInt(HypixelApiTool::floorKeyOrder));
        sb.append(label).append(": ");
        for (String k : keys) {
            if (map.get(k).isJsonNull()) continue;
            sb.append(k).append("=").append(asNumber(map.get(k))).append(" ");
        }
        sb.append("\n");
    }

    private static int floorKeyOrder(String k) {
        try { return Integer.parseInt(k); } catch (NumberFormatException e) { return 99; }
    }

    private static String asNumber(JsonElement el) {
        if (el == null || el.isJsonNull()) return "null";
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
                return Double.toString(d);
            }
            return p.getAsString();
        }
        return GSON.toJson(el);
    }

    private static void writeDebugDump(String username, String uuid, String profile,
                                       List<String> keywords, JsonObject memberData, JsonObject curated,
                                       String dungeonLines, List<String> keywordMatches, String slimJson) {
        try {
            JsonObject dump = new JsonObject();
            dump.addProperty("username", username);
            dump.addProperty("uuid", uuid);
            dump.addProperty("profile", profile);
            dump.addProperty("note",
                    "Look at dungeon_types_slim.master_catacombs.tier_completions.7 for M7 completions. "
                            + "Also see latest.log lines starting with [AIChat].");
            JsonArray kw = new JsonArray();
            if (keywords != null) for (String k : keywords) kw.add(k);
            dump.add("model_keywords", kw);
            dump.addProperty("decoded_dungeon_lines", dungeonLines);
            JsonArray matches = new JsonArray();
            for (String m : keywordMatches) matches.add(m);
            dump.add("keyword_matches", matches);

            if (memberData.has("dungeons") && memberData.get("dungeons").isJsonObject()) {
                dump.add("raw_dungeons_keys", keysArray(memberData.getAsJsonObject("dungeons")));
                JsonObject rawTypes = memberData.getAsJsonObject("dungeons").has("dungeon_types")
                        && memberData.getAsJsonObject("dungeons").get("dungeon_types").isJsonObject()
                        ? memberData.getAsJsonObject("dungeons").getAsJsonObject("dungeon_types")
                        : new JsonObject();
                dump.add("raw_dungeon_types_keys", keysArray(rawTypes));
            }
            if (curated.has("dungeons") && curated.get("dungeons").isJsonObject()) {
                JsonObject d = curated.getAsJsonObject("dungeons");
                if (d.has("dungeon_types") && d.get("dungeon_types").isJsonObject()) {
                    dump.add("dungeon_types_slim", slimDungeonTypes(d.getAsJsonObject("dungeon_types")));
                }
            }
            dump.addProperty("slim_json_sent_to_model", slimJson);

            Files.createDirectories(DEBUG_FILE.getParent());
            Files.writeString(DEBUG_FILE, PRETTY.toJson(dump), StandardCharsets.UTF_8);
            LOG.info("[AIChat] wrote skyblock debug JSON to {}", DEBUG_FILE.toAbsolutePath());
        } catch (Exception e) {
            LOG.warn("[AIChat] failed to write skyblock debug JSON: {}", e.toString());
        }
    }

    private static JsonArray keysArray(JsonObject o) {
        JsonArray a = new JsonArray();
        for (String k : o.keySet()) a.add(k);
        return a;
    }

    private static JsonObject slimDungeonTypes(JsonObject types) {
        JsonObject out = new JsonObject();
        for (var e : types.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                out.add(e.getKey(), e.getValue());
                continue;
            }
            JsonObject src = e.getValue().getAsJsonObject();
            JsonObject slim = new JsonObject();
            for (String keep : new String[] {
                    "experience", "highest_tier_completed", "tier_completions", "times_played",
                    "milestone_completions", "mobs_killed", "watcher_kills", "fastest_time",
                    "fastest_time_s", "fastest_time_s_plus"
            }) {
                if (src.has(keep)) slim.add(keep, src.get(keep));
            }
            slim.add("all_keys", keysArray(src));
            out.add(e.getKey(), slim);
        }
        return out;
    }

    /**
     * Recursively searches for any object key whose name contains one of the given
     * keywords (case-insensitive substring match). Huge parent objects (catacombs)
     * are recursed into instead of dumped whole, so master_catacombs is not skipped.
     */
    private static void findMatches(JsonElement el, String path, java.util.List<String> keywordsLower,
                                    java.util.List<String> out, int maxMatches) {
        if (out.size() >= maxMatches || el == null || el.isJsonNull()) return;

        if (el.isJsonObject()) {
            for (var e : el.getAsJsonObject().entrySet()) {
                if (out.size() >= maxMatches) return;
                String key = e.getKey();
                if (BLOAT_KEYS.contains(key)) continue;
                String keyLower = key.toLowerCase();
                String childPath = path.isEmpty() ? key : path + "." + key;
                boolean matched = keywordsLower.stream().anyMatch(keyLower::contains);
                JsonElement val = e.getValue();
                boolean hugeObject = val.isJsonObject() && GSON.toJson(val).length() > MAX_MATCH_VALUE;
                if (matched && !hugeObject) {
                    out.add(childPath + " = " + compact(val));
                }
                findMatches(val, childPath, keywordsLower, out, maxMatches);
            }
        } else if (el.isJsonArray()) {
            int i = 0;
            for (JsonElement child : el.getAsJsonArray()) {
                if (out.size() >= maxMatches) return;
                findMatches(child, path + "[" + i + "]", keywordsLower, out, maxMatches);
                i++;
            }
        }
    }

    private static String compact(JsonElement el) {
        String s = el.isJsonPrimitive() ? el.getAsString() : GSON.toJson(el);
        return s.length() > 1500 ? s.substring(0, 1500) + "...(truncated)" : s;
    }

    private static String resolveUuid(String username) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/"
                        + URLEncoder.encode(username, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
            return null;
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.has("id") ? json.get("id").getAsString() : null;
    }

    /** Same generic "strip anything bulky" approach used before - keeps normal stat fields, drops junk. */
    private static JsonElement prune(JsonElement el) {
        if (el == null || el.isJsonNull()) return el;

        if (el.isJsonObject()) {
            JsonObject in = el.getAsJsonObject();
            JsonObject out = new JsonObject();
            for (var e : in.entrySet()) {
                out.add(e.getKey(), prune(e.getValue()));
            }
            return out;
        }

        if (el.isJsonArray()) {
            JsonArray in = el.getAsJsonArray();
            JsonArray out = new JsonArray();
            int limit = Math.min(in.size(), MAX_ARRAY_LEN);
            for (int i = 0; i < limit; i++) {
                out.add(prune(in.get(i)));
            }
            if (in.size() > MAX_ARRAY_LEN) {
                out.add(new JsonPrimitive("...(" + (in.size() - MAX_ARRAY_LEN) + " more entries omitted)"));
            }
            return out;
        }

        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString() && p.getAsString().length() > MAX_STRING_LEN) {
                return new JsonPrimitive("[long value omitted]");
            }
            return p;
        }

        return el;
    }
}