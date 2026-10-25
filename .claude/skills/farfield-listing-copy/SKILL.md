---
name: farfield-listing-copy
description: Use when writing Modrinth text, README, or banner PNG.
category: workflow
---

# Farfield listing copy

Listing text states only what the released build does today. Anything planned goes under "Not there yet", never in the feature list.

- Source of the listing text: `docs/listing/description.md`, in English like the pages. The README links to it; change both when a feature lands or is dropped.
- Before writing a feature claim, check the code and the lang files that show it (the preview's claim sits in the `noise_preview` tooltip, see `farfield-noise-preview`). A stale claim is worse than a missing one.
- The README status line states the version and what is planned, never "private" or "early development" once the repo is public.
- Banners: `python docs/brand/render_banners.py` regenerates `banner-scene.png` (Modrinth, CurseForge) and `banner-icon.png` (README). It draws at 2x and box-averages down, with the Silkscreen and Outfit fonts from `docs/brand/fonts/` (SIL OFL). The icon comes from `docs/brand/render_logo.py`. Open the PNG to check it before it is used.
- Modrinth and CurseForge: edit or upload only after the user has validated the text. Until then, report what is drafted, not what is published.
