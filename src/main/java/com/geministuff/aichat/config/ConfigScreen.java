package com.geministuff.aichat.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * /aiconfig. Provider button (Gemini/Groq), API key + model boxes for the
 * selected provider, a "Recent models" button (click to cycle through models
 * you've used before), and the checkboxes. Nothing persists until Save.
 */
public class ConfigScreen extends Screen {

    private final ModConfig config;

    // Local working copies so switching provider doesn't lose unsaved typing.
    private String provider;
    private String gKey, gModel, qKey, qModel;
    private String currentTone;
    private int recentIndex = -1;

    private Button providerButton;
    private Button recentButton;
    private Button toneButton;
    private EditBox apiKeyBox;
    private EditBox modelBox;
    private EditBox hypixelKeyBox;
    private EditBox searxngBox;
    private Checkbox internetBox;
    private Checkbox postToChatBox;
    private Checkbox guildBox;
    private Checkbox partyBox;
    private Checkbox publicBox;

    private int titleY, keyLabelY, modelLabelY, hypixelLabelY, searxngLabelY;

    public ConfigScreen() {
        super(Component.literal("AIChat Settings"));
        this.config = ModConfig.get();
        this.provider = config.provider;
        this.gKey = config.geminiApiKey;
        this.gModel = config.geminiModel;
        this.qKey = config.groqApiKey;
        this.qModel = config.groqModel;
        this.currentTone = config.getTone();
    }

    private boolean groq() { return "groq".equalsIgnoreCase(provider); }

    private List<String> recent() {
        return groq() ? config.recentGroqModels : config.recentGeminiModels;
    }

    private void stashBoxes() {
        if (groq()) { qKey = apiKeyBox.getValue().trim(); qModel = modelBox.getValue().trim(); }
        else { gKey = apiKeyBox.getValue().trim(); gModel = modelBox.getValue().trim(); }
    }

    private void loadBoxes() {
        apiKeyBox.setValue(groq() ? qKey : gKey);
        modelBox.setValue(groq() ? qModel : gModel);
        recentIndex = -1;
        refreshLabels();
    }

    private void refreshLabels() {
        providerButton.setMessage(Component.literal("Provider: " + (groq() ? "Groq" : "Gemini") + "  (click to switch)"));
        List<String> r = recent();
        recentButton.setMessage(Component.literal(r.isEmpty()
                ? "Recent models: none yet"
                : "Recent models (" + r.size() + "): " + r.get(Math.max(recentIndex, 0)) + "  (click to cycle)"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = Math.max(18, this.height / 2 - 150);
        titleY = y - 16;

        providerButton = Button.builder(Component.literal("Provider"), b -> {
                    stashBoxes();
                    provider = groq() ? "gemini" : "groq";
                    loadBoxes();
                })
                .pos(cx - 150, y).size(300, 20).build();
        addRenderableWidget(providerButton);
        y += 34;

        keyLabelY = y - 11;
        apiKeyBox = new EditBox(this.font, cx - 150, y, 300, 20, Component.literal("API key"));
        apiKeyBox.setMaxLength(200);
        addRenderableWidget(apiKeyBox);
        y += 36;

        modelLabelY = y - 11;
        modelBox = new EditBox(this.font, cx - 150, y, 300, 20, Component.literal("Model"));
        modelBox.setMaxLength(100);
        addRenderableWidget(modelBox);
        y += 24;

        recentButton = Button.builder(Component.literal("Recent"), b -> {
                    List<String> r = recent();
                    if (r.isEmpty()) return;
                    recentIndex = (recentIndex + 1) % r.size();
                    modelBox.setValue(r.get(recentIndex));
                    refreshLabels();
                })
                .pos(cx - 150, y).size(300, 20).build();
        addRenderableWidget(recentButton);
        y += 28;

        hypixelLabelY = y - 11;
        hypixelKeyBox = new EditBox(this.font, cx - 150, y, 300, 20, Component.literal("Hypixel API key"));
        hypixelKeyBox.setMaxLength(100);
        hypixelKeyBox.setValue(config.hypixelApiKey);
        hypixelKeyBox.setHint(Component.literal("from developer.hypixel.net - for player stat lookups"));
        addRenderableWidget(hypixelKeyBox);
        y += 28;

        searxngLabelY = y - 11;
        searxngBox = new EditBox(this.font, cx - 150, y, 300, 20, Component.literal("SearXNG URL"));
        searxngBox.setMaxLength(200);
        searxngBox.setValue(config.searxngUrl == null || config.searxngUrl.isBlank()
                ? "http://localhost:8080" : config.searxngUrl);
        searxngBox.setHint(Component.literal("http://localhost:8080"));
        addRenderableWidget(searxngBox);
        y += 24;

        internetBox = Checkbox.builder(Component.literal("Internet search"), this.font)
                .pos(cx - 150, y).selected(config.internetOn()).build();
        addRenderableWidget(internetBox);

        toneButton = Button.builder(Component.literal("Tone: " + currentTone), b -> {
                    List<String> tones = config.getAvailableTones();
                    int idx = tones.indexOf(currentTone);
                    idx = (idx == -1) ? 0 : (idx + 1) % tones.size();
                    currentTone = tones.get(idx);
                    b.setMessage(Component.literal("Tone: " + currentTone));
                })
                .pos(cx + 5, y - 2).size(145, 20).build();
        addRenderableWidget(toneButton);
        y += 24;

        postToChatBox = Checkbox.builder(Component.literal("Post replies to chat (unchecked = only show to me)"), this.font)
                .pos(cx - 150, y).selected(config.postToChat).build();
        addRenderableWidget(postToChatBox);
        y += 24;

        guildBox = Checkbox.builder(Component.literal("Enable in Guild chat"), this.font)
                .pos(cx - 150, y).selected(config.guildEnabled).build();
        addRenderableWidget(guildBox);
        y += 22;

        partyBox = Checkbox.builder(Component.literal("Enable in Party chat"), this.font)
                .pos(cx - 150, y).selected(config.partyEnabled).build();
        addRenderableWidget(partyBox);
        y += 22;

        publicBox = Checkbox.builder(Component.literal("Enable in Public chat"), this.font)
                .pos(cx - 150, y).selected(config.publicEnabled).build();
        addRenderableWidget(publicBox);
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
                    stashBoxes();
                    config.provider = provider;
                    config.geminiApiKey = gKey;
                    config.geminiModel = gModel.isEmpty() ? "gemini-3.5-flash-lite" : gModel;
                    config.groqApiKey = qKey;
                    config.groqModel = qModel.isEmpty() ? "llama-3.3-70b-versatile" : qModel;
                    config.hypixelApiKey = hypixelKeyBox.getValue().trim();
                    config.searxngUrl = searxngBox.getValue().trim();
                    if (config.searxngUrl.isBlank()) config.searxngUrl = "http://localhost:8080";
                    config.internetEnabled = internetBox.selected();
                    config.tone = currentTone;
                    // Remember models into each provider's recent list (only if that provider has a key).
                    if (!gKey.isBlank()) ModConfig.remember(config.recentGeminiModels, config.geminiModel);
                    if (!qKey.isBlank()) ModConfig.remember(config.recentGroqModels, config.groqModel);
                    config.postToChat = postToChatBox.selected();
                    config.guildEnabled = guildBox.selected();
                    config.partyEnabled = partyBox.selected();
                    config.publicEnabled = publicBox.selected();
                    config.save();
                    onClose();
                })
                .pos(cx - 150, y).size(145, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .pos(cx + 5, y).size(145, 20).build());

        loadBoxes();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
        gfx.centeredText(this.font, this.title, this.width / 2, titleY, 0xFFFFFF);
        gfx.text(this.font, Component.literal((groq() ? "Groq" : "Gemini") + " API key:"), this.width / 2 - 150, keyLabelY, 0xAAAAAA, false);
        gfx.text(this.font, Component.literal("Model:"), this.width / 2 - 150, modelLabelY, 0xAAAAAA, false);
        gfx.text(this.font, Component.literal("Hypixel API key (optional, for stat lookups):"), this.width / 2 - 150, hypixelLabelY, 0xAAAAAA, false);
        gfx.text(this.font, Component.literal("SearXNG URL (Gemini internet; Groq llama fallback):"), this.width / 2 - 150, searxngLabelY, 0xAAAAAA, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
