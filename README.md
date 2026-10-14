# Farfield

Far-distance terrain rendering for Minecraft, using levels of detail (LOD):
render hundreds to thousands of chunks at a low cost, seamlessly.

Status: early development, private. Minecraft 1.21.1, NeoForge and Fabric.

- Vision: [`docs/VISION.md`](docs/VISION.md)
- Decisions: [`docs/DECISIONS.md`](docs/DECISIONS.md)
- Current plan: [`docs/plans/milestone-1.md`](docs/plans/milestone-1.md)
- State of the art: [`docs/analysis/lod-engine-state-of-the-art.md`](docs/analysis/lod-engine-state-of-the-art.md)

## Building

Requires a JDK to launch Gradle; Java 21 is provisioned automatically.

```
./gradlew build
```

Jars: `neoforge/build/libs/` and `fabric/build/libs/`.

## License

All rights reserved for now; a license will be chosen before the first public
release (ADR-003).
