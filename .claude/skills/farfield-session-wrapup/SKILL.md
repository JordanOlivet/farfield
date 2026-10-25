---
name: farfield-session-wrapup
description: Use when closing Farfield work: progress log, commit, push.
category: workflow
---

# Farfield session wrap-up

Close a work block in this order, and only when the user asked for a change:

1. Tick a plan item `[x]` only for what was verified. A user verdict on a pack ("nickel") counts; quote it in the log. A reply such as "c'est good, enchaine" approves a report, not a pack result: it does not tick an item that still waits for a test in the pack. Write the scope left out into the item itself, or mark it `[-]` with the reason.
2. Complete `docs/progress/<today>.md`: commits, what was verified, what was not, prioritized next steps. Add the commit to its table. A commit cannot contain its own hash: write the row's short hash after the commit, in the same wrap-up's next commit or in the next wrap-up's table. Never push the literal text `(this commit)`. If the session passed midnight, the work done after midnight goes in the new date's file, and the earlier day's file keeps what it recorded.
3. Stage with `git add -A`, then check `git diff --cached --name-only` for machine-local files: `local.properties`, anything under `runs/`, `.claude/**/.ledger.jsonl`, `.claude/**/.sidecar.json`. Unstage any hit (`git reset -q -- <path>`) before committing. Then commit with the Co-Authored-By trailer, push, then state one next action the user can take.

Stage with `git add -A` even for a revert or a deleted file. An explicit path to a deleted file fails the whole `git add`, and a commit chained after it with `;` still runs on whatever was staged. Chain add, commit and push with `&&`, and read `git show --stat HEAD` before pushing: the message must match the stat.

A reverted or rejected decision is a new decision too: append an ADR that says so. Do not edit the status or body of the ADR it reverses.

A question turn ("est-il possible", "dis-moi ce que t'en penses", "tu en penses quoi") gets an answer only. Do not commit, push, or write the progress log in that turn. Record the open question as a next step in the next real wrap-up, or wait for the go-ahead.

New decisions are a new ADR appended to `docs/DECISIONS.md`; never rewrite an existing one.
