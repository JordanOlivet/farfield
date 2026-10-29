# Client jar deploy: why each step

- The backup goes beside `mods/` so the loader scans one Farfield jar only. A backup inside `mods/` loads twice.
- The hash compare is the proof: a copy that did not finish looks like a deploy otherwise.
- The user tests in several instances. Only the named one changes; the others keep their jar.
- The game log for the test is that instance's `logs/latest.log`, not the run dir's.
- Whether the server needs the jar is a protocol question, answered before the copy, not after.
