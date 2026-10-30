gh release view v0.2.0 --json body --jq .body | head -15
Changes since v0.1.0:

- build: both loaders' jars copied to output/ by every build (ADR-110)
- fix(preview): real chunks beside the preview stand at their lowest ground (ADR-109)
- fix(preview): features under the sea take the place of its water
- feat(preview): kelp and seagrass in the preview's water; silent agent clients