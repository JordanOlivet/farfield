# Farfield release: details behind the rules

## Tag and version
- Tag form: `v<major>.<minor>.<patch>[-alpha.N|beta.N|rc.N]`. The workflow derives the jar version from the tag.
- `version=` in `gradle.properties` is the core of the release being tagged. The Prepare job compares it to the tag and stops with `Tag ... does not match version=... in gradle.properties` on mismatch, before any upload.
- The hand-made tag `0.1.0-pre-alpha` predates the workflow and stays as it is.

## Repository setup (Farfield on GitHub)
- Variables: `MODRINTH_ID` (project id, from the Modrinth project page), `CURSEFORGE_ID` (numeric id, from the CurseForge About Project panel).
- Secrets: `MODRINTH_TOKEN` (Modrinth personal access token with create-version rights), `CURSEFORGE_TOKEN` (CurseForge API token).
- Set them with the secret-entry procedure (`github-secret-entry` skill), never through the chat.
- The reusable workflow lives in JordanOlivet/ci-workflows as `publish-mod.yml@v1`. Its PR must be merged before a tag can run; the auto-tag moves `v1`.

## Run signals (first release, run 38899796445, 2026-10-16)
- Five jobs: Prepare release, Build mod, GitHub Release, Publish NeoForge, Publish Fabric. All green.
- Each Publish job logs `Successfully published the assets to CurseForge, Modrinth`.
- GitHub Release `Farfield 0.1.0-alpha.1`, pre-release, two jars, changelog = commit subjects since the previous tag.
- Commands used: `gh run watch RUN_ID --exit-status --interval 15`, `gh run view RUN_ID --log`, `gh release view TAG --json name,isPrerelease,assets,body`.

## Not verifiable from the public APIs
- `api.modrinth.com/v2/project/PROJECT_ID/version` answered 404 for the unlisted, unapproved project. Modrinth and CurseForge version pages (loaders, game version, dependencies, environments) must be checked by the user. Report them as unverified.

## Non-blocking warnings seen
- Node.js 20 actions forced to Node 24 (`actions/checkout@v4`, `actions/download-artifact@v4`).
- `actions/setup-java@v4` deprecated in favor of v5.
- Fix these in ci-workflows, not in Farfield.
