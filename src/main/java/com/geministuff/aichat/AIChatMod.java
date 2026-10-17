package com.geministuff.aichat;

import com.geministuff.aichat.chat.ChatListener;
import com.geministuff.aichat.command.GeminiConfigCommand;
import com.geministuff.aichat.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;

public class AIChatMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ModConfig.load();
        ChatListener.register();
        GeminiConfigCommand.register();
        com.geministuff.aichat.command.AiCommand.register();
        com.geministuff.aichat.command.ChatManageCommands.register();
        System.out.println("[AIChat] loaded. Run /aiconfig to set your API key and model.");
    }
}
