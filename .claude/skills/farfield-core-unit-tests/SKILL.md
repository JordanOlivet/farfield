---
name: farfield-core-unit-tests
description: Use when running :core:test or a Farfield regression test.
category: debugging
---

# Farfield core unit tests

A regression test for an engine fix must fail without the fix. Prove it before calling the fix tested.

1. Find the test by its method name, never by a guessed class name: `grep -rln "<methodName>" core/src/test`. The preview tests live in `PreviewTest.java`; `PreviewVoxelsTest.java` does not exist.
2. Run one class: `./gradlew.bat :core:test --no-daemon -q --tests "*PreviewTest*"`. Read failures from `core/build/test-results/test/TEST-<fully.qualified.Class>.xml` with `grep -o 'message="[^"]*"'`, not from the Gradle tail.
3. Give every assertion a message that names the voxel or case (for example `"blocks 52..55"`), so the failure reads without a debugger.
4. Prove the test fails without the fix: copy the source file to a scratch path, revert the one changed line with `sed -i` (match a unique substring; use `\r\?$` when the file has CRLF endings), run the test, expect the assertion, then restore from the copy and check `git diff` shows only the intended change.

Light and fill rules of the preview: see `farfield-noise-preview`.