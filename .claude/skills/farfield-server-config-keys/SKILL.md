---
name: farfield-server-config-keys
description: Use when adding a ServerConfig server setting key.
category: minecraft-modding
---

# Farfield server config keys

Adding a `farfield-server.properties` key touches six places in `ServerConfig`. Change all of them in one edit, or the default drifts silently.

1. Add the record component at the end of the header, and a `static final String` key constant beside the others.
2. Set its default in `DEFAULTS`, then read it in `read` with the helper of its type (`bool`, `integer`, `getProperty`), defaulting from `DEFAULTS`.
3. Keep the previous constructor arity as an overload that passes the old default, so existing callers and tests compile unchanged.
4. Clamp a ranged value in the compact constructor (see `MAX_PREGEN_RADIUS`).
5. Write the key in `write` with a `#` comment, using the file's own line separator, so the file keeps its line endings.
6. Test in `ServerConfigTest`: the default on a file without the key, the round trip (`assertEquals(config, ServerConfig.read(file))`), a value out of range clamped, and one non-default value read back.

Make the multi-line edit with a Python script that asserts each anchor before writing (see `bash-heredoc-edit-scripts`). Then `./gradlew.bat :core:test --no-daemon`. If the key changes behavior, grep its accessor in `common/` and log the effect on the success branch.
