---
name: farfield-listing-copy
description: Use when writing Modrinth text, README, banner PNG, or uploading gallery screenshots to Modrinth or CurseForge.
category: workflow
---

# Farfield listing copy

Listing text states only what the released build does today. Anything planned goes under "Not there yet", never in the feature list.

- Source of the listing text: `docs/listing/description.md`, in English like the pages. The README links to it; change both when a feature lands or is dropped.
- Before writing a feature claim, check the code and the lang files that show it (the preview's claim sits in the `noise_preview` tooltip, see `farfield-noise-preview`). A stale claim is worse than a missing one. Commands: grep their registrations before listing them, and split single player from server by the registering class (`literal("farfieldclient")` in `FarfieldClientCommands` is the per-player drawing side on a server; `/farfield import` is operator-only on a server). Check the "Not there yet" section on each release: a feature that shipped moves out of it.
- The README status line states the version and what is planned, never "private" or "early development" once the repo is public.
- Banners: `python docs/brand/render_banners.py` regenerates `banner-scene.png` (Modrinth, CurseForge) and `banner-icon.png` (README). It draws at 2x and box-averages down, with the Silkscreen and Outfit fonts from `docs/brand/fonts/` (SIL OFL). The icon comes from `docs/brand/render_logo.py`. Open the PNG to check it before it is used.
- Modrinth and CurseForge: edit or upload only after the user has validated the text. Until then, report what is drafted, not what is published. The user pastes the CurseForge page himself: hand him the final `description.md` and name the sections that changed, do not try to edit that page.
- Screenshots cover the biomes the mod is about, not one biome. If a set comes back all ice or all one biome, add the other families (badlands, desert, savanna, dark forest, mangrove, jungle, cherry, sea) and keep the ones already good. Each picture is a fresh-world capture at 1920x1080: set `overrideWidth:1920` and `overrideHeight:1080` in the run dir's `options.txt` for the session and restore the `.orig` copy after. Files go in `docs/listing/screenshots/`, one `<!-- image: screenshots/<name>.png (caption) -->` marker per picture in `description.md`.
- Pick pictures from a contact sheet (PIL, three per row, 960x540 each) to judge them at a glance, then read each candidate at full size. Thin dark lines at a distant water edge only show at full size: a sea view was dropped for them, and two chosen shots were kept with the defect named in the report. Before writing a caption, check the code claim the picture illustrates (coral reefs, surface rules, kelp).
- Compatibility lists only the packs and mods actually run for this release.
- Gallery uploads: Modrinth takes JPEG. Export each picture as JPEG quality 90: a 4K copy for the Modrinth gallery (0.5 to 1.7 MB), and a 1920x1010 copy in `docs/listing/screenshots/` (`view-*.jpg`, `shader-*.jpg`). CurseForge caps each gallery image at 2 MB, so check the sizes before the user uploads there.
- Upload with `docs/listing/modrinth.py add-image` (JPEG accepted). `modrinth.py show` prints each gallery image's full-size URL. Embed those URLs in `description.md` as Markdown images under the section they illustrate; a picture that only repeats a comparison already in the text stays in the gallery.
- After any `description.md` change, push the body with the script and read it back with `python -X utf8 -I docs/listing/modrinth.py show`. Diff it against the file: a trailing blank line from `print` is the only allowed difference. Modrinth keeps the last pushed body, so an unpushed 0.x text stays live.
- Modrinth status `processing` (under review): the public API answers 404 for the project. Read it through the token script, not the public API, before treating a 404 as a bug.
- CurseForge: the user publishes the text and the gallery himself. Hand him the final `description.md`, the JPEG paths and their sizes, and name the sections that changed.
