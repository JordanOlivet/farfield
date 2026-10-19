---
name: farfield-jar-contents-check
description: Use when checking farfield jars for leaked bench deps or a mixin missing from the refmap.
category: build
---

# Check shipped farfield jars for dev-only code

Before committing a change that touches `core/build.gradle` or adds bench code, prove the shipped jars contain no zstd, lmdb or bench classes. An empty grep is evidence only if a positive control matched first.

1. Build: `.\gradlew.bat build --no-daemon -q`.
2. List both shippable jars: `unzip -l neoforge/build/libs/farfield-neoforge-1.21.1-*.jar` and `unzip -l fabric/build/libs/farfield-fabric-1.21.1-*.jar`.
3. Positive control: grep the same listing for `io/lakio/farfield/core/storage/`. It must match. If not, the listing or the glob is wrong; stop.
4. Leak check: grep the listing for `-i -E "zstd|lmdb|bench"`. It must return nothing.
5. A hit means a jmh-only dependency or source set leaked into the main scope. Fix the Gradle scope, not the grep.
6. Mixin registration (a different check, same jars): grep the jar's `farfield.refmap.json` for the mixin's simple name, e.g. `unzip -p fabric/build/libs/farfield-fabric-1.21.1-*.jar farfield.refmap.json | grep -c LevelRendererMixin`. Zero means the mixin is not in the shipped build. Confirm the count is at least 1 against a mixin already known to ship before trusting a zero elsewhere.
