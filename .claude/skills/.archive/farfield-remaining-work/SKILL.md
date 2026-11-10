---
name: farfield-remaining-work
description: Use when asked what's left or next on Farfield's plan.
category: workflow
---

# Farfield remaining work

Answer from the open items of `docs/plans/milestone-*.md`, never from memory of the session.

1. List the open items with `grep -nE '^\s*- \[( |~)\]'` on the current milestone file, then read each item's Done and Remains sub-bullets: the headline alone hides what the user still has to check.
2. Group them, at most 5 items per group: (a) waiting on the user's own test, no code; (b) code work; (c) deferred by the user, quoting his words.
3. Per item: one line, a time estimate in minutes or hours, and the first concrete action. Never cite an earlier estimate or promise from memory: when the user quotes one ("tu m'avais dit que ça serait rapide"), grep the plan and the progress log for it; if it is not there, say so, then give a fresh estimate from the code (the user challenged a "rapide" claim on 2026-11-09 that no file recorded).
4. Most important first. End with one line: `Prochaine action : réponds « go N ».`
5. Never tick `[x]` in the answer. Tick only what was verified, when the work is committed.

Reply in French.
