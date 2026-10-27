---
name: farfield-release-publish
description: Use when tagging or publishing a Farfield release.
category: minecraft-modding
---

# Farfield release publish

A release is a pushed tag `v<version>[-alpha.N]`. Do not tag until the user says every feature planned for the release is complete: finishing one work item is not a reason to tag. `.github/workflows/release.yml` builds both loader jars and publishes them. Never publish by hand, and never report a platform as published without its log line.

1. Check `version=` in `gradle.properties` is the core of the tag (`0.1.0` for `v0.1.0-alpha.1`). A mismatch stops the Prepare job before any upload.
2. Check the ci-workflows PR that provides `publish-mod.yml@v1` is merged, and that the variables `MODRINTH_ID`, `CURSEFORGE_ID` and the secrets `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN` exist (`gh variable list`, `gh secret list`). Missing ones: use the `git-github-config` skill.
3. Lint the workflows before tagging (actionlint on `.github/workflows/*.yml`).
4. Tag and push: `git tag vX.Y.Z-alpha.N && git push origin vX.Y.Z-alpha.N`.
5. Watch the run: `gh run list --limit 3` for RUN_ID, then `gh run watch RUN_ID --exit-status --interval 15`.
6. Verify, in this order: five jobs green (Prepare release, Build mod, GitHub Release, Publish NeoForge, Publish Fabric), e.g. `gh run view RUN_ID --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"'`; each Publish job logs `Successfully published the assets to CurseForge, Modrinth` (`gh run view RUN_ID --log | grep -a "Successfully published"`); the log line `version-type: release|alpha|beta` matches the tag; `gh release view TAG --json name,isPrerelease,assets` lists both jars, and `isPrerelease` is true only for alpha and beta.
7. The Modrinth public API returns 404 for an unlisted, unapproved project. Modrinth and CurseForge pages stay unverified: ask the user to check loaders, game version, dependencies. Say so in the report.
8. Log the release in the day's `docs/progress` file (session wrap-up skill).

Details, run ids and warnings: see `references/release-checklist-details.md`.
