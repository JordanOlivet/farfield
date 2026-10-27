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
- Test a command in a dev client by grepping the game log for its `[CHAT]` reply. No reply line means the command did not run. Launch and capture rules live in `farfield-dev-client-check`.
