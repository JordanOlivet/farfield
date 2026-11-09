---
name: farfield-chat-commands
description: Use when editing a Farfield chat command or its arguments.
category: minecraft-modding
---

# Farfield chat commands

Farfield's client commands live under their own root, `/farfieldclient`. Never register a client command under `/farfield`: a server registers that root too, and the two clash.

- Register the tree once in common `FarfieldClientCommands`, generic over the command source. Each loader only hooks its event: Fabric `ClientCommandRegistrationCallback`, NeoForge `RegisterClientCommandsEvent`.
- A free-text argument (a world name, a label) uses `greedyString()`. `word()` takes one token: `world name Home Server` silently kept only `Home`. Sanitize the value in core (`RemoteWorldNames.sanitize`), not in the command.
- A command that moves or closes a store closes it first and waits, then reopens it. Log one `info` line on the success branch so a run proves the path ran.
- Debug subcommands (`/farfieldclient debug ...`) are chained in `common/src/main/java/io/lakio/farfield/lod/RenderCommands.java`. Find one with `grep -n 'literal("<name>")'`.
- A debug level lock (`debug level <n>`) is a floor over the whole displayed distance, not a radius: no section is cut, and no section is drawn finer than level `n`. The preview obeys the same floor. Do not clip the range to a fixed block count.
- A command that reloads a shader pack runs its reload on the render thread: Iris loads packs there, but a `/farfield` command runs on the server thread in single player. Call `Minecraft.getInstance().execute(link::reloadPack)`, never the reload inline. A forced setting (`/farfield debug shaderpack auto|voxy|dh`, not saved) is confirmed by the log line `Shader pack LODs: contract forced to ...` and the contract the pack then follows.
- Adding a `.then(...)` branch to a long Brigadier chain (the server's `/farfield` tree in `FarfieldCommands`, or the client tree) can break the parentheses at the chain's end. Each `.then(` opens one level and closes with its own `)`. Count the closing run on the last line of the branch you added, then compile (`./gradlew.bat :common:compileJava`) before any other edit. The compile error points at the tail of the chain, not at the branch that caused it.
- Test a command in a dev client by grepping the game log for its `[CHAT]` reply. No reply line means the command did not run. Launch and capture rules live in `farfield-dev-client-check`.
