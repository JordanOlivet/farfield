---
name: farfield-lod-store-migration
description: Use when changing LodKey bits or LOD store on-disk files.
category: minecraft-modding
---

# Farfield LOD store migration

A change to stored keys or file layout converts each existing store once, at open, behind a marker file. Never rely on a wipe, and never let a legacy cleanup run on a store that already has the new layout.

1. Write a marker at the new layout (`sections/keys.version`). An open without it converts the region files' records to the new keys (`LogStore.rekey`, `LodKey.fromLayout1` for the old keys) and logs `N region files converted to the keys of ADR-N, once`.
2. Gate every legacy cleanup on the marker's absence. The old preview's `sections/L8` delete ran at every open and erased the real level 8 once level 8 became real. Only the unit test of a reopen caught it.
3. A mirror whose key space changed renames its versions file (`versions2.bin`), so it fetches its sections again once instead of trusting stale versions.
4. Test the reopen, not only the first open: a converted store read back, a level kept across a second open, the marker written once.
5. After a dev run, read the store on disk: `keys.version`, `mips.levels`, and the `sections/L<n>` folders, before calling the migration done.
6. A layout change that alters a stream message or a tile format also bumps the stream protocol (see `farfield-lod-streaming`, item 9).
