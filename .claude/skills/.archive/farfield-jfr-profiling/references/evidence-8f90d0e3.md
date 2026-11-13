Mes comparaisons mélangeaient un run 4K et des runs minimisés, donc je lance un vrai A/B sur le même vol avec le jar du commit précédent (ADR-141). Environ 7 min.

S=/c/Users/Lakio/AppData/Local/Temp/claude/1e895702e83ac8bd/549643b2-efa1-4ea3-9d28-98761ccb9f98/scratchpad && rm -rf $S/wt-base && git worktree add -q $S/wt-base HEAD && cp local.properties $S/wt-base/ && cd $S/wt-base && ./gradlew.bat :fabric:build --no-daemon -q -x test

tool_result: "430d0c9b29314a1f\nc80f80ff98465236" ; "alloc-after total 256 GB/min, Farfield 167 GB/min (65%)\r\nalloc-now2 total 182 GB/min, Farfield 63 GB/min (35%)"

    /** Work arrays, made at their first use (ADR-141): a section decoded only to be read never needs them. */
    /** Per mesher thread, a far section's coarse tiles by place, cleared per build: a new map each was garbage. */