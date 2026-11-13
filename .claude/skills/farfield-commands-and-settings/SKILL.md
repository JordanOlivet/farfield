---
name: farfield-commands-and-settings
description: Use when editing a Farfield command or server config key.
category: minecraft-modding
---

# Farfield commands and settings

Client commands live under `/farfieldclient`, never under `/farfield`: a server registers that root too, and the two clash. A server setting is a `farfield-server.properties` key, and it touches six places in `ServerConfig`: change them all in one edit, or the default drifts silently.

## Chat commands

- Register the tree once in common `FarfieldClientCommands`, generic over the command source. Each loader hooks only its event: Fabric `ClientCommandRegistrationCallback`, NeoForge `RegisterClientCommandsEvent`.
- A free-text argument uses `greedyString()`: `word()` kept only `Home` from `world name Home Server`. Sanitize the value in core (`RemoteWorldNames.sanitize`), not in the command.
- A command that moves or closes a store closes it first and waits, then reopens it. Log one `info` line on the success branch.
- Debug subcommands are chained in `common/src/main/java/io/lakio/farfield/lod/RenderCommands.java`; find one with `grep -n 'literal(' RenderCommands.java`.
- A debug level lock (`debug level N`) is a floor over the whole displayed distance, not a radius, and the preview obeys it too. Do not clip the range to a block count.
- A setting whose `off` value also means something else breaks both meanings. The LOD fog used `fog_distance=off` to hide the fog, and that also held the LODs to 4,096 blocks. Keep the visibility toggle as its own key (`fog`, with a `fog on|off` subcommand) and the distance as a number. An old file's `fog_distance=off` must still read as "fog hidden, default distance" (`RenderConfig`, ADR-140), and a test must pin that.
- A command that reloads a shader pack runs the reload on the render thread: `Minecraft.getInstance().execute(link::reloadPack)`, never inline. Confirm a forced setting with the log line `Shader pack LODs: contract forced to`.
- Adding a `.then(` branch to the long Brigadier chain can break the closing parentheses: count the run on the branch's last line, then compile `:common:compileJava` before any other edit.
- Test in a dev client by grepping the game log for its `[CHAT]` reply; no reply line means the command did not run. Launch rules: `farfield-dev-client-check`.

## Server config keys (ServerConfig)

1. Add the record component at the end of the header, and a `static final String` key constant beside the others.
2. Set its default in `DEFAULTS`, then read it in `read` with the helper for its type, defaulting from `DEFAULTS`.
3. Keep the previous constructor arity as an overload that passes the old default, so existing callers and tests compile unchanged.
4. Clamp a ranged value in the compact constructor.
5. Write the key in `write` with a `#` comment, using the file's own line separator.
6. Test in `ServerConfigTest`: the default on a file without the key, the round trip (`assertEquals(config, ServerConfig.read(file))`), an out-of-range value clamped, and one non-default value read back.

Make the multi-line edit with a Python script that asserts each anchor before writing (see `bash-heredoc-edit-scripts`), then run `./gradlew.bat :core:test --no-daemon`. If the key changes behavior, grep its accessor in `common/` and log the effect on the success branch.