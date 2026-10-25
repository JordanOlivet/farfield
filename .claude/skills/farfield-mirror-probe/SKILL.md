---
name: farfield-mirror-probe
description: Use when a render bug needs stored LOD mirror data.
category: debugging
---

# Farfield mirror probe

Answer a render question from the stored data, not from a guess: copy the store, read the copy from a temporary test, print counts.

1. Copy the store folder to the scratchpad: on the client, the `stream/minecraft_overworld` folder under `farfield/servers`; on a save, its `farfield` store. Never open the live store.
2. Copy `scripts/MirrorLightProbe.java` to `core/src/test/java/io/lakio/farfield/core/world/`, adapt the counts to the question, then run: `FF_MIRROR=<copy> FF_X=<x> FF_Z=<z> ./gradlew.bat :core:test --tests "*MirrorLightProbe*" --no-daemon -q -i 2>&1 | grep -a PROBE`.
3. Read counts per level, not voxel dumps. Check the claim against the code path before changing code.
4. Delete the probe before any commit; `git status` must not list it.

Probe template: `scripts/MirrorLightProbe.java`.
