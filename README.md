# AIChat

AIChat is a client-side Fabric mod for Minecraft 26.1.2. It connects chat prompts to either Google Gemini or Groq, so the mod's name and commands are provider-neutral.

## Features

- In guild, party, and public chat, `!ai <prompt>` sends the prompt to the selected provider. Formatting codes are ignored, and the command can appear anywhere in a chat line.
- Choose Gemini or Groq, set separate API keys and models, and enable or disable each chat channel from the `/aiconfig` screen.
- With **Post replies to chat** enabled, the mod posts one `AI: <answer>` reply to the originating channel after a randomized 4–7 second delay. Replies are limited to 250 characters. A 4-second per-channel cooldown filters rapid prompts.
- With **Post replies to chat** disabled, chat triggers are off and `/ai <prompt>` provides private, longer answers in your own chat.
- Conversation memory is kept separately for each provider and channel. Each channel supports up to six named chats, with up to 12 exchanges retained per chat.
- Optional tools provide web search through a configured SearXNG instance and Hypixel SkyBlock profile lookups through the official Hypixel API. SkyBlock lookups require a Hypixel API key.
- Built-in and custom response tones can be selected with `/settone`.

## Commands

- `/aiconfig` opens settings. `/aiconfig set <key> <value>` changes a setting without opening the screen. Supported keys include `provider`, `apikey`, `hypixelkey`, `searxng`, `internet`, `model`, `tone`, `posttochat`, `guild`, `party`, and `public`.
- `/ai <prompt>` runs a private prompt when **Post replies to chat** is disabled.
- `/newchat [channel]`, `/destroy [channel]`, `/allchats [channel]`, `/settitle <title>`, and `/selectchat <id> [channel]` manage conversation sessions. Channels are `guild`, `party`, `public`, or `private`.
- `/settone [tone]`, `/settone add <tone>[: instructions]`, and `/settone remove <tone>` manage response styles.
- When **Post replies to chat** is enabled, the corresponding chat commands are also available with a `!` prefix, such as `!newchat`, `!destroy`, `!allchats`, `!settitle`, `!selectchat`, and `!settone`.

The former `/geminiconfig` command remains as a compatibility alias. Existing `geminichat.json` settings and `geminichat_memory` data are also read during migration to the current `aichat` config paths.

## Build

Requirements: Java 25 and an internet connection for Gradle to download Minecraft and Fabric dependencies.

```sh
./gradlew build
```

The mod jar is `build/libs/aichat-1.0.0.jar`. Install it with Fabric Loader 0.18.4 or newer and Fabric API for Minecraft 26.1.2.

## Server rules

This mod reads multiplayer chat and can post automated replies. Check the rules of the server you play on before enabling chat replies.
