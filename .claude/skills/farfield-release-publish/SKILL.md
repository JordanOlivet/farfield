---
name: farfield-release-publish
description: Use when publishing a Farfield release or store listing.
category: minecraft-modding
---

# Farfield release publish

A release is a pushed tag `v<version>[-alpha.N]`. Do not tag until the user says every feature planned for the release is complete: finishing one work item is not a reason to tag. `.github/workflows/release.yml` builds both loader jars and publishes them. Never publish by hand, and never report a platform as published without its log line.

1. Check `version=` in `gradle.properties` is the core of the tag (`0.1.0` for `v0.1.0-alpha.1`). A mismatch stops the Prepare job before any upload. Pick the bump from what changed since the last tag: a stream protocol version change makes it a minor bump (0.2.1 to 0.3.0), since servers and clients of different versions cannot stream; only same-protocol fixes are a patch.
2. Check the ci-workflows PR that provides `publish-mod.yml@v1` is merged, and that the variables `MODRINTH_ID`, `CURSEFORGE_ID` and the secrets `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN` exist (`gh variable list`, `gh secret list`). Missing ones: use the `git-github-config` skill.
3. Lint the workflows before tagging: `actionlint .github/workflows/*.yml`. actionlint is not on PATH on this machine; use `uvx --from actionlint-py actionlint .github/workflows/*.yml` (it passed on 0.2.0). Then sweep the user-facing text for claims the release changes: the lang files' tooltips (`en_us.json` and `fr_fr.json`, where a stale "Single player only" survived until 0.2.0), `docs/listing/description.md`, the README status line and plan link, and `version=` in `gradle.properties`.
4. Tag and push: `git tag vX.Y.Z-alpha.N && git push origin vX.Y.Z-alpha.N`.
5. Watch the run: `gh run list --limit 3` for RUN_ID, then `gh run watch RUN_ID --exit-status --interval 15`.
6. Verify, in this order: five jobs green (Prepare release, Build mod, GitHub Release, Publish NeoForge, Publish Fabric), e.g. `gh run view RUN_ID --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'`; each Publish job logs `Successfully published the assets to CurseForge, Modrinth` (`gh run view RUN_ID --log | grep -a "Successfully published"`); the log line `version-type: release|alpha|beta` matches the tag; `gh release view TAG --json name,isPrerelease,assets` lists both jars, and `isPrerelease` is true only for alpha and beta.
7. The Modrinth public API returns 404 for an unlisted, unapproved project. Modrinth and CurseForge pages stay unverified: ask the user to check loaders, game version, dependencies. Say so in the report.
8. Log the release in the day's `docs/progress` file (session wrap-up skill). The GitHub Release body defaults to the commit list: prepend a readable summary with `gh release edit TAG --notes-file FILE`, where FILE holds the summary followed by the old body from `gh release view TAG --json body --jq .body`.

Details, run ids and warnings: see `references/release-checklist-details.md`.

## Store listing text and gallery

Listing text states only what the released build does today. Anything planned goes under "Not there yet", never in the feature list. Check each feature claim in the code and the lang files first: a stale claim is worse than a missing one.

- Source: `docs/listing/description.md` (English). The README links to it: change both when a feature lands or is dropped. Re-check "Not there yet" on each release.
- The README status line states the version and what is planned, never "private" or "early development" once the repo is public.
- Banners: `python docs/brand/render_banners.py` regenerates `banner-scene.png` and `banner-icon.png` (the icon comes from `docs/brand/render_logo.py`). Open each PNG before use.
- Screenshots cover the biomes the mod is about, not one biome: badlands, desert, savanna, dark forest, mangrove, jungle, cherry, sea, with the ice ones kept. Fresh-world captures at 1920x1080: set `overrideWidth:1920` and `overrideHeight:1080` in the run dir's `options.txt` for the session, and restore the `.orig` copy after. Pick from a contact sheet, then read each candidate at full size: thin dark lines at a distant water edge show only there.
- Gallery: Modrinth takes JPEG quality 90, a 4K copy for the gallery and a 1920x1010 copy in `docs/listing/screenshots/`. CurseForge caps each image at 2 MB: check sizes first.
- Upload with `docs/listing/modrinth.py add-image`; `modrinth.py show` prints each image's full-size URL, embedded under the section it illustrates. After any `description.md` change, push with the script, read back with `python -X utf8 -I docs/listing/modrinth.py show`, and diff against the file.
- Modrinth status `processing`: the public API answers 404. Read through the token script before calling a 404 a bug.
- Compatibility lists only the packs and mods run for this release.
- Edit or upload only after the user validates the text. CurseForge: the user pastes the page and the gallery himself; hand him the final `description.md`, the JPEG paths and sizes, and the changed sections.
