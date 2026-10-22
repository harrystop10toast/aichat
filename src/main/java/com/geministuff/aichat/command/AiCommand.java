package com.geministuff.aichat.command;

import com.geministuff.aichat.config.ModConfig;
import com.geministuff.aichat.ai.AiService;
import com.geministuff.aichat.ai.ConversationMemory;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/** /ai <prompt> - private, unlimited-length. Only active in "only show to me" mode. */
public class AiCommand {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("ai")
                        .then(ClientCommands.argument("prompt", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    if (ModConfig.get().postToChat) {
                                        ctx.getSource().sendError(Component.literal(
                                                "/ai is off while \"Post replies to chat\" is ticked. Untick it in /AIconfig."));
                                        return 0;
                                    }
                                    String prompt = StringArgumentType.getString(ctx, "prompt");
                                    Minecraft mc = Minecraft.getInstance();
                                    mc.player.sendSystemMessage(Component.literal("§7[You] " + prompt));

                                    CompletableFuture
                                            .supplyAsync(() -> AiService.generate("private", "me", prompt, false))
                                            .exceptionally(ex -> new AiService.Result(false,
                                                    "ERROR: unexpected " + ex.getClass().getSimpleName()
                                                            + " - check .minecraft/logs/latest.log for details"))
                                            .thenAccept(result -> mc.execute(() -> {
                                                if (result.ok()) {
                                                    ConversationMemory.addExchange("private", "me", prompt, result.text());
                                                }
                                                if (mc.player != null) {
                                                    mc.player.sendSystemMessage(Component.literal("§b[AI]§r " + result.text()));
                                                }
                                            }));
                                    return 1;
                                }))));
    }
}
