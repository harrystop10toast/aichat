# GeminiChat (personal Fabric mod for MC 26.1.2)

Client-side only. Watches your chat, and whenever a line contains `!ai <prompt>`
it sends that prompt to Gemini and replies with `Gemini: <answer>`.

## What it does

- **Channel detection (loose, no bracket parsing):** colour/format codes
  (`§x` and `&x`) are stripped first, then:
  - line starts with `Guild >` → guild
  - line starts with `Party >` → party
  - anything else → public
- **Trigger:** the word `!ai ` anywhere in the (code-stripped) line. Everything
  after it is the prompt.
- **Reply:** waits a random 2-6s, then sends **one** message:
  - Guild → `/gc Gemini: <answer>`
  - Party → `/pc Gemini: <answer>`
  - Public → plain chat, no slash command
  - If "Post replies to chat" is unchecked in `/geminiconfig`, nothing is sent
    to the server at all - the answer only shows in your own chat as `[AI] <answer>`.
- **Memory:** a small rolling window (last 6 exchanges) per channel, persisted
  to `config/geminichat_memory.json`, replayed as context on every Gemini call
  so it isn't starting fresh each time.
- **Brevity + guard rail:** Gemini is instructed, as its absolute top-priority
  rule, to answer in 1-3 sentences and to refuse (with a fixed `ERROR: request
  blocked` reply) if a chat message tries to override its instructions. See
  `GeminiService.SYSTEM_PROMPT`.

## Commands

- `/geminiconfig` - opens a screen: API key box, model box, "post to chat"
  checkbox, and three checkboxes (guild/party/public enable).
- `/geminiconfig set <key> <value>` - headless alternative, e.g.
  `/geminiconfig set model gemini-2.5-pro`. Keys: `apikey`, `model`,
  `postToChat`, `guild`, `party`, `public`.

## Building

You need **Java 25** and an internet connection (Gradle pulls the Minecraft
jar, Fabric Loader, and Fabric API - none of that can be vendored/offline).

```
./gradlew build
```

The finished jar is `build/libs/geminichat-1.0.0.jar` - **this is the real,
final jar**, MC 26.1.2 does not use remapping like older versions did, so
there's no separate "-dev" vs "remapped" copy to worry about.

Drop it in your `mods` folder alongside Fabric API 0.153.0+26.1.2 (or newer -
check https://fabricmc.net/develop/ for the current recommended number) and
Fabric Loader 0.18.4+.

I could not produce a compiled jar directly in this conversation: building
requires downloading the Minecraft 26.1.2 client jar and Fabric tooling from
Mojang/Fabric's servers, and the sandbox this was written in has no general
internet access - only you, running `./gradlew build` on your own machine,
can actually compile it.

## If something doesn't compile

26.1.2 is a *very* new modding target - it's the first unobfuscated Minecraft
release, and Fabric API's public names were reshuffled to match Mojang's
official names for the first time. I used the class/method names documented
on fabricmc.net's "Porting to 26.1" pages as of writing, but a handful of
signatures in a target this fresh could still have shifted slightly by the
time you build. If `./gradlew build` throws a "cannot find symbol" error:

1. Read which class/method it's complaining about.
2. In IntelliJ, ctrl/cmd-click the surrounding type (e.g. `Checkbox`,
   `EditBox`, `ClientReceiveMessageEvents`, `ClientCommands`) to jump to its
   real, current decompiled source and see the actual constructor/field names.
3. Swap the name in the file the error points to. The logic (regex matching,
   delay scheduling, memory, HTTP call) doesn't depend on any of these
   specific GUI/event class names being exactly right - only the few lines
   that directly touch them do.

Most likely spots for drift, in rough order of risk:
- `ConfigScreen.java` - `Checkbox.builder(...)` / `EditBox` constructor shape.
- `ChatListener.java` - the exact static field name on `ClientReceiveMessageEvents`.
- `GeminiConfigCommand.java` - `ClientCommands` (renamed from
  `ClientCommandManager` in 26.1 - see comment in the file).
- `ChatSender.java` - `ClientPacketListener.sendCommand` / `.sendChat` method names.

## Hypixel rules

Hypixel's automation/macro rules do get updated periodically, and where
exactly an auto-responding chat bot like this sits under them is a judgment
call for their support team, not something I can verify for you here since
that's a live policy decision — worth a quick check of hypixel.net/rules or
a support ticket if you want certainty before running this on your main account.
