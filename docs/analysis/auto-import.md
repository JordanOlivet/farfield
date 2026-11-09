# Importing a world's LODs at start, unattended (plan item G.1b)

Date: 2026-11-09. Status: study, nothing decided yet.

The user (2026-11-09): "pour par exemple un monde qui avait vécu sans
farfield jusqu'ici et qui le possède maintenant, au démarrage, le serveur
lance un /farfield import [...] pour préparer les LOD même si personne
n'est encore connecté". The same idea as the preview's pregeneration
(ADR-123): use the time the server has nothing to do.

## What exists

- `/farfield import [threads]`, `import stop`, `import status`
  (`FarfieldCommands`, `LodServer.startImport`, ADR-017): the region files
  of the caller's dimension (every dimension's own `region/` folder), on a
  background thread at minimum priority, `threads` workers (default half
  the processors), nearest region first.
- Each chunk goes through `LodWorld.ingest`; a chunk loaded in the game
  right now is skipped (the live pipeline holds a newer copy).
- A checkpoint per dimension (`<store>/import.checkpoint`): one line per
  finished region, keyed by its file name, size and modification time. A
  stopped import resumes there; a region the game rewrote since is read
  again.
- `RegionImporter` already has `pause()`, `resume()`, a chunks-per-second
  limit and `cancel()`; the commands expose only stop (cancel) and status.
- One import at a time on the server. Measured on the user's server
  (2026-10-25): 1,331,683 chunks of 2,092 regions in 497 s, about 2,700
  chunks a second, the server otherwise idle.
- After an import, level 0 is written; the maintenance builds the coarse
  levels from it (`MIPS_VERSION`, ADR-082). With players online, that work
  slowed the pushes of new chunks before ADR-083 gave new chunks the
  priority.

## What "a world that lived without Farfield" is

Three cases share the same need, region files whose chunks the LOD store
never saw:

1. Farfield installed on an existing world: no store and no checkpoint.
2. Farfield removed for a while, then put back: a store and a checkpoint,
   but chunks generated or changed meanwhile.
3. The world edited outside the game (a map editor, a region copied in,
   a backup restored): the same as 2.

A live server keeps its store current on its own (every chunk loaded is
snapshotted, ADR-084), so outside those cases an import finds nothing new.

How each case can be told at start, without reading every chunk:

- Case 1: no `import.checkpoint` in the dimension's store. Cheap and
  certain. A world created with Farfield also has none: its first start
  then reads every region once (minutes, harmless: unchanged sections are
  not written again).
- Cases 2 and 3: the checkpoint's size and modification time differ for
  some regions. But the regions the server itself rewrote while playing
  differ too, so "every start, every changed region" would read again
  each start the regions played since the last one: correct (unchanged
  sections are skipped) but wasted work, proportional to the area played.

## Options

- **A. Once**: at start, a dimension without a checkpoint is imported.
  Covers case 1, the user's example; nothing at later starts.
- **B. Every start**: the import runs at each start; the checkpoint skips
  the regions unchanged since the last import. Covers 1, 2 and 3; costs a
  read of the regions played since the last start (seconds to minutes).
- **C. A mark of the last time the server ran with Farfield**: at stop,
  the store writes the time; at start, regions modified after it were
  changed without Farfield (cases 2 and 3) and are imported, plus case 1.
  Covers all three without the waste of B. The catch: a crash leaves no
  stop mark (then fall back to B for that start), and region times can
  move with a backup tool that rewrites files.

## Shared with the pregeneration (ADR-123)

- The player rule: paused while players are online (`pause()`), resumed
  when the server empties; an operator's command wins (as `pregen resume`).
- Persistence: the checkpoint already resumes an interrupted import, so a
  restart costs nothing.
- Order: overworld first, regions nearest the spawn first
  (`RegionImporter.listRegions` takes a center); then the other dimensions.
- Commands: `import pause|resume` to add (the importer has them), `import
  status` per dimension.
- Cost on the server: about 8 minutes of half the processors for the
  user's 1.3 M chunks, then the coarse levels. Run while nobody plays,
  it competes with nothing; the preview's pregeneration should wait for
  it (both are heavy on the processor) or take the other half.

## Risks

- Disk: a full import reads every region file once (the user's: a few
  GB) and rewrites the store's sections that change.
- A modded world with huge regions or broken chunks: failures are logged
  per region and per chunk and skipped (as today).
- Memory: the import holds a few regions at a time (one per worker); no
  growth seen in the 2026-10-25 run.

## Estimate

About half a day with tests: the setting, the start hook per dimension,
the player rule on the importer's pause, the two commands, the log lines,
a dev server run on a world without a store (a copy of `farfield-test`
with its `farfield/` folder removed), and option C's stop mark if picked.

## The user's picks (2026-11-09)

- Option C: a dimension never imported is imported at start; later, only
  the regions changed while Farfield was not there (the server marks its
  stop time; after a crash, the regions changed since the last import).
- Every dimension with region files: the overworld first, regions nearest
  the spawn first.
- On by default (`import_on_start`), with the pregeneration's player rule
  (paused while players are online; an operator's resume wins).
- With the pregeneration, the user's order: both run until the preview's
  1,024 blocks around each task's center are built (what a player joining
  early sees first), then the pregeneration waits for the import to end,
  then builds out to its radius. 1,024 blocks fixed, not a setting (the
  preview's finest area).
- Next: the implementation, about half a day plus an hour for the order.
