---
name: farfield-client-jar-deploy
description: Use when "déploie" copies a Farfield jar to the client.
category: minecraft-modding
---

# Farfield client jar deploy

A build is not a deploy. Copy a jar into the user's client instance only after his "déploie", for the instance he names. Never start the game or the server for it.

1. Take the jar from `neoforge/build/libs/` (name starts `farfield-neoforge-1.21.1-`) and note its `sha256sum`.
2. Confirm the instance folder the user names (`Instances/<name>/mods`). If he names none, ask.
3. Copy the Farfield jar already in that `mods` folder to a backup beside the `mods` folder, not inside it, with a timestamp (`.jar.<yyyymmdd-hhmmss>.bak`), so the loader never sees two Farfield jars.
4. Copy the new jar into `mods/`, then `sha256sum` both files. Stop on a mismatch.
5. Decide on the server: a client-only change (render or preview, with `StreamProtocol.VERSION` unchanged since the server's jar) needs no server deploy. Otherwise the server needs it too, with its own yes.
6. Report the backup path, the matching hash, and "redémarre le client": a jar loads only at launch.

Procedure and the instance rule: see `references/deploy-steps.md`. On the user's PC, the machine-local `farfield-deploy` skill runs these steps (and the server's) with one script; it is gitignored, so another PC follows the steps above.
