---
name: farfield-core-dependencies
description: Use when adding a library or collection to Farfield core.
category: minecraft-modding
---

# Farfield core: no new library for a collection

The `core` module takes no third-party library. For a primitive collection on a hot path (a `long` set, an `int` map), first search `core` for an in-house one. If none fits, write a small open-addressing structure in `core/src/main/java/io/lakio/farfield/core/util/` with a JUnit 5 test under `core/src/test`.

- Check `core/build.gradle` before assuming a library is available. `api` and `core` stay free of Minecraft and loader imports (see CLAUDE.md).
- Adding an artifact to `core` is a dependency decision: state it to the user before editing the build file.
