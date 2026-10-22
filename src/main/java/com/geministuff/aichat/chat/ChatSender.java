package com.geministuff.aichat.chat;

import com.geministuff.aichat.config.ModConfig;
import com.geministuff.aichat.ai.AiService;
import com.geministuff.aichat.ai.ConversationMemory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Waits 4-7 random seconds (4-6s extended by 1s), then sends ONE message -
 * never a burst of rapid replies. Runs the actual send on the client thread via Minecraft.execute().
 */
public class ChatSender {

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aichat-delay");
        t.setDaemon(true);
        return t;
    });

    public static void sendDelayed(ChatChannel channel, String username, String prompt, AiService.Result result) {
        // Random time between 4-6 seconds, extended 1 second -> 4 to 7 seconds (4000 to 7000 ms)
        long delayMs = ThreadLocalRandom.current().nextLong(4000, 7001);
        SCHEDULER.schedule(() -> Minecraft.getInstance().execute(() -> deliver(channel, username, prompt, result)),
                delayMs, TimeUnit.MILLISECONDS);
    }

    public static void sendDirectMessage(ChatChannel channel, String text) {
        // Quick delay (1.5-2.5s) for command feedback so it feels natural in chat
        long delayMs = ThreadLocalRandom.current().nextLong(1500, 2501);
        SCHEDULER.schedule(() -> Minecraft.getInstance().execute(() -> deliverText(channel, text)),
                delayMs, TimeUnit.MILLISECONDS);
    }

    private static void deliver(ChatChannel channel, String username, String prompt, AiService.Result result) {
        if (result.ok()) {
            ConversationMemory.addExchange(channel.key, username, prompt, result.text());
        }
        deliverText(channel, result.text());
    }

    private static void deliverText(ChatChannel channel, String text) {
        String outbound = "AI: " + text;
        if (outbound.length() > 250) {
            outbound = outbound.substring(0, 247) + "...";
        }

        ModConfig cfg = ModConfig.get();

        if (!cfg.postToChat) {
            // Unchecked box: show it only to you, never sent to the server.
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§b[AI]§r " + text));
            }
            return;
        }

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;

        // Hypixel enforces its own chat length cap; 250 chars comfortably fits inside it.
        switch (channel) {
            case GUILD -> connection.sendCommand("gc " + outbound);
            case PARTY -> connection.sendCommand("pc " + outbound);
            case PUBLIC -> connection.sendChat(outbound);
        }
    }
}
