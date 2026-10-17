package com.geministuff.aichat.tools;

import com.geministuff.aichat.config.ModConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Searches the web via a SearXNG instance (default http://localhost:8080) and
 * returns clean, human-readable snippets. Both Gemini and Groq use this tool.
 */
public class WebSearchTool {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final int MAX_RESULTS = 5;
    private static final int MAX_SNIPPET = 250;

    public static String search(String query) {
        if (query == null || query.isBlank()) {
            return "Search failed: empty query.";
        }
        String base = ModConfig.get().searxngUrl;
        if (base == null || base.isBlank()) {
            base = "http://localhost:8080";
        }
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        try {
            String url = base + "/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&format=json";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "AIChatMod/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "Search failed: SearXNG returned HTTP " + response.statusCode()
                        + (response.body() == null ? "" : " - " + clip(response.body(), 200));
            }
            return digestSearx(query, response.body());
        } catch (java.net.ConnectException e) {
            return "Search failed: could not connect to SearXNG at " + base
                    + ". Make sure SearXNG is running (e.g. docker run -d -p 8080:8080 searxng/searxng).";
        } catch (java.net.http.HttpTimeoutException e) {
            return "Search failed: SearXNG request timed out.";
        } catch (Exception e) {
            return "Search failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " (SearXNG URL: " + base + ")";
        }
    }

    private static String digestSearx(String query, String body) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return "Search returned non-JSON response from SearXNG: " + clip(body, 300);
        }

        StringBuilder out = new StringBuilder();
        out.append("Web search results for: \"").append(query).append("\"\n\n");

        // Direct answer if available
        if (root.has("answers") && root.get("answers").isJsonArray()) {
            JsonArray answers = root.getAsJsonArray("answers");
            for (JsonElement el : answers) {
                String ans = asPlain(el);
                if (!ans.isBlank()) {
                    out.append("Direct Answer: ").append(clip(ans, 400)).append("\n\n");
                    break;
                }
            }
        }

        // Infobox overview if available
        if (root.has("infoboxes") && root.get("infoboxes").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("infoboxes")) {
                if (!el.isJsonObject()) continue;
                JsonObject box = el.getAsJsonObject();
                String content = box.has("content") ? asPlain(box.get("content")) : "";
                if (!content.isBlank()) {
                    out.append("Overview: ").append(clip(content, 400)).append("\n\n");
                    break;
                }
            }
        }

        // Search snippets
        if (root.has("results") && root.get("results").isJsonArray()) {
            JsonArray results = root.getAsJsonArray("results");
            int count = 0;
            for (JsonElement el : results) {
                if (count >= MAX_RESULTS || !el.isJsonObject()) break;
                JsonObject r = el.getAsJsonObject();
                String title = r.has("title") ? asPlain(r.get("title")) : "";
                String content = r.has("content") ? asPlain(r.get("content")) : "";
                String url = r.has("url") ? asPlain(r.get("url")) : "";

                if (title.isBlank() && content.isBlank()) continue;

                count++;
                out.append("[").append(count).append("] ");
                if (!title.isBlank()) out.append(clip(title, 120)).append("\n");
                if (!url.isBlank()) out.append("Source: ").append(url).append("\n");
                if (!content.isBlank()) out.append(clip(content, MAX_SNIPPET)).append("\n");
                out.append("\n");
            }
            if (count == 0) {
                out.append("No results found.\n");
            }
        } else {
            out.append("No results found.\n");
        }

        return out.toString().strip();
    }

    private static String asPlain(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').strip();
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
