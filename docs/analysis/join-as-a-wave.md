# The join as a wave: one order for tiles and meshes

Date: 2026-11-14. Status: study, nothing built. Branch `feat/preview-pyramid`.
Follows ADR-150 to ADR-154 (the orders tried one after the other) and
`docs/analysis/preview-to-the-border.md`.

## What the user asks

After ADR-154 (2026-11-14, Fabric pack, a new world): fewer holes, but
"on se retrouve avec un moment où le terrain proche est sans arbres, puis
on a des arbres mais le reste n'en a pas, ça dure un petit moment et d'un
coup on a tout qui pope d'un coup"; "j'ai comme une impression qu'on
tourne encore en rond".

What is wanted, in his words: "quand on arrive dans le monde la première
fois ou à un tp en zone jamais générée [...] on ait le monde qui se
déroule devant nous comme une vague qui part de nous et qui s'éloigne
vers la distance max. On a pas besoin d'avoir instantanément le niveau de
détail le plus fin mais il faudrait que ce qui est proche puisse quand
même être assez rapidement fin [...] et le reste peut s'affiner de plus
en plus loin."

He asked to look at what the field does to make a world appear smoothly.

## What happens today (measured)

One run: Fabric pack in production, a new world, the largest draw
distance (33,554,432 blocks), 12 chunks of game view (the user's), from
200 blocks up in spectator, 4K window. A temporary probe in the renderer
counted every mesh job by what asked for it (removed after the run; the
figures below are computed by a script over the log).

Mesh jobs per 10 s window after the join:

| Window | Jobs | Real, first | Real, again | Preview, first | Preview, again |
|---|---|---|---|---|---|
| 0 to 10 s | 8,595 | 1,878 (22 %) | 3,722 (43 %) | 2,891 (34 %) | 104 (1 %) |
| 10 to 20 s | 11,720 | 1,370 (12 %) | 5,852 (50 %) | 4,496 (38 %) | 2 (0 %) |
| 20 to 30 s | 14,106 | 0 | 671 (5 %) | 13,435 (95 %) | 0 |
| 30 to 40 s | 23,300 | 0 | 190 (1 %) | 2,414 (10 %) | 20,696 (89 %) |
| 40 to 50 s | 20,922 | 0 | 10 (0 %) | 6,199 (30 %) | 14,713 (70 %) |

Over the first 50 s, 78,643 jobs for 32,683 sections:

| What asked for the mesh | Jobs | Share |
|---|---|---|
| A preview tile changed under a meshed section | 35,510 | 45 % |
| First mesh of a section | 32,683 | 42 % |
| A real section written again | 5,390 | 7 % |
| A neighbor's border changed | 3,857 | 5 % |
| Neighbor of a real write | 1,133 | 1 % |
| Others (written while meshing, looks) | 70 | 0 % |

- Real sections: 3,248 sections, 13,693 jobs, 4.2 a section; 895 of them
  meshed 5 times or more. By level: 7,861 jobs at level 0, 3,845 at
  levels 1 to 2, 1,452 at levels 3 to 5, 535 above.
- Preview sections: 29,435 sections, 64,950 jobs, 2.2 a section; 15,646
  meshed once, 9,334 twice, 763 three times, 1,978 four times, 1,714 five
  times or more.
- 17,689 meshes (22 % of the jobs) were replaced by a newer one without
  ever having been drawn; 2,276 were already outdated when they arrived.
- The mesher never idles: 64 jobs in flight on every stat line from 10
  to 60 s.

The server's side of the same run: 960 quarters to build; 378 in the
first minute, 294 of them coarse, 84 of level 0 (447 ms each on average);
the coarsest level whole at 50.6 s, everything within 1,024 blocks at
56.1 s, all built at 95.6 s.

### What the figures say

1. **The burst is a rule, not a cost.** Preview sections meshed again for
   a finer tile: 106 jobs in the first 30 s, 35,515 by 50 s. They wait
   behind every hole to the world border (ADR-153's lag during the disc),
   and the holes end near 30 s. This is "tout pope d'un coup": the far
   trees are ready long before they show.
2. **The first 20 s go to the spawn's real chunks.** 12,822 of the 20,315
   jobs (63 %) are real sections, three quarters of them meshed again as
   the integrated server writes the spawn's chunks one by one: each chunk
   written meshes its sections again at every level, and their neighbors.
   This is where the near ring waits.
3. **Half of the work is repeated work.** A preview section is meshed
   again for each quarter of a tile built under it, and the far sections
   (level 6 and up: 21,608 first meshes, 15,478 again) lie over many
   quarters. One mesh in five is never seen.
4. **The near detail waits for the tiles, not for the meshes.** The 84
   level-0 quarters of the first minute share the preview threads with
   294 coarse ones; everything within 1,024 blocks is built at 56 s.
5. **A saturated queue makes every order a choice of who waits.**
   ADR-151 to ADR-154 each moved the wait: the near ring, then the middle
   distances, then the holes, now the trees. Each was one more rule; none
   was a single order.

## What the field does

| Idea | Where | Effect reported |
|---|---|---|
| A coarse base always there; fine detail is what is dropped under load | Nanite's root pages (always resident), geometry clipmaps (levels updated coarse to fine within a budget) | No hole; fast motion loses detail, not coverage |
| One priority for every request, computed again as the view changes | Cesium 3D Tiles (depth in the tree, distance to the camera, distance to the screen's center), Funkhouser and Séquin's benefit over cost, Paper's chunk priority (near and in front) | "Something rendering in all parts of the scene in about half the time" (Cesium) |
| Skip the levels between what is shown and what is wanted | Cesium 3D Tiles | 30 to 50 % less load; 16 s down to 11 s on their example |
| Cancel what is no longer wanted | Cesium 3D Tiles | Fewer requests spent on tiles that left the view |
| The center of the screen first | Cesium 3D Tiles (the edges wait until the camera rests) | The view is finished where the player looks |
| Blend between two levels | Alpha or dithered cross-fade, geomorphing, hysteresis | The change of level does not pop |

Sources:
[Faster 3D Tiles](https://cesium.com/blog/2019/05/07/faster-3d-tiles/),
[Skipping levels of detail](https://cesium.com/blog/2017/05/05/skipping-levels-of-detail/),
[Funkhouser and Séquin 1993](https://collaborate.princeton.edu/en/publications/adaptive-display-algorithm-for-interactive-frame-rates-during-vis/),
[Geometry clipmaps](https://www.hhoppe.com/geomclipmap.pdf),
[GPU Gems 2, chapter 2](https://developer.nvidia.com/gpugems/GPUGems2/gpugems2_chapter02.html),
[Nanite, SIGGRAPH 2021](https://advances.realtimerendering.com/s2021/Karis_Nanite_SIGGRAPH_Advances_2021_final.pdf),
[Paper's chunk priority](https://ayakael.net/mirrors/papermc/commit/357b52fd98fe62e67e735f5cf2e3577e21a18ba2).
Their figures are for downloaded tiles, not for terrain generated on the
spot as here. Nothing reliable was found on the order other Minecraft LOD
mods use.

Against that list, Farfield has the coarse base (but drawn to the world
border before anything refines), no single priority (four steps for the
tiles, two lags for the meshes, the disc on top), no skipping, and
cancelling only in part.

## Proposal: the wave

### W1. One score for the meshes

Every mesh job gets one number, lowest first:

    score = distance × weight

- a hole (nothing drawn there), or a real section's new mesh: weight 1;
- a drawn preview section meshed again (a refinement): weight `k`.

`k` replaces ADR-152's fixed lag (16,384 blocks) and ADR-153's lag during
the disc (everything else first). With a factor instead of a lag, the
refinement at distance `d` is ordered like a hole at `k × d`: the front of
refinement follows the front of coverage at a fixed ratio, at every
distance. Each level of the pyramid holds about as many sections as the
next, so a ratio of 4 is two rings behind, the same two levels as
`ACCEPTABLE_LEVELS`. That is the wave: coverage runs ahead, refinement
follows it outward, nothing waits for the world border.

### W2. The same score for the tiles

The server's four steps (`PreviewPyramid.step`: ring acceptable, ring
wanted, acceptable elsewhere, wanted elsewhere) become the same formula: a
quarter that brings coverage (the acceptable level) scores its distance,
a quarter that refines (the wanted level) scores `k` times its distance.
The near ring's margin and the root's special case go away: near, the
wanted level of a place 300 blocks away (score 1,200 at `k` = 4) is built
before the coverage of a place 2,000 blocks away. Levels nobody needs stay
skipped. A client asking a server for tiles uses the same order
(`StreamClient.askTiles`).

One constant then decides what the user described: how fine the near
terrain gets before the horizon is whole.

### W3. Fewer meshes of the preview

A preview section is meshed again for each quarter built under it. Mesh
it again once per tile instead: a drawn preview section whose tile still
has quarters being built or about to be waits for them, within a bound in
time; a hole never waits. If every section meshed more than once (13,789)
were meshed again once, the 35,515 jobs would be 21,726 fewer. The exact
rule needs the list of quarters in flight on the client (in single player
from the store, through a server from its messages): to design.

### W4. Fewer meshes of the spawn's real chunks

Real sections take 63 % of the first 20 s for 3,248 sections. Two leads,
both to check in the code before choosing:

- a real section written chunk by chunk is meshed again once its writes
  rest for a short time, not at each chunk, while staying inside the 2 s
  the preview holds a chunk for the real terrain (ADR-154's holes must not
  come back);
- the coarser real sections over the spawn (5,832 jobs at level 1 and up)
  are mostly not drawn, only relied on by the selection: their new meshes
  can score like refinements.

At two meshes a section, the real sections would cost 6,496 jobs instead
of 13,693: 7,197 fewer, 35 % of the first 20 s.

### W5. The disc stays, as a safety

The disc (ADR-153) and its holes (ADR-154) keep the guarantee: nothing
far shows before what is nearer, no gap between. With W1 and W2 the
orders themselves produce that look, and the disc should rarely hold
anything back; its log line stays the measure.

### W6. Later: blending the change of level

A cross-fade when a finer mesh replaces a coarser one. Only once the
order is right: it hides a pop, not a wait.

### W0. First: the measure, kept

The probe of this study becomes a permanent line of the performance logs
(mesh jobs by cause and by class, meshes never drawn), so each step above
is judged on the same figures.

## How it is judged

On the same run (new world, largest distance, 12 then 32 chunks of game
view), before and after each step:

- mesh jobs in the first 60 s, by class, and the share never drawn;
- preview sections meshed again per 10 s window: a slope, not a step;
- the time at which everything within 1,024 blocks is at its wanted
  level (56.1 s today);
- the disc's radius at 10, 20, 30 s and the holes open;
- captures at 5, 10, 15, 25, 40 s, and the user's own view.

## Order of work

| Step | What | Estimate |
|---|---|---|
| W0 | The mesh counters in the performance logs | 1 h |
| W1 | One score for the meshes, bench at `k` = 2, 4, 8 | 2 h |
| W2 | The same score for the tiles (server and client asks) | 3 h |
| W4 | The spawn's real sections | 2 to 3 h |
| W3 | One new mesh per tile for the preview | half a day |
| W6 | Blending | later |

W1 alone removes the burst and is small; W2 is what brings the near
detail earlier; W3 and W4 are what shorten the whole.

## Answers (2026-11-14)

1. The ratio between coverage and refinement, and a later horizon for an
   earlier near detail: "Oui on peut priorisé le proche et retarder un
   peu le loin. On commence avec 4 et on ajustera." `k` = 4 to start
   (ADR-155), to be adjusted on the user's own view.

## Open questions

None.

## Built so far

- W0 and W1 (ADR-155, 2026-11-14). W1 alone made things worse: with the
  lags gone, far sections were meshed again under every coarse tile
  written over them (77,970 new meshes of preview sections in a minute,
  117,573 meshes in all), and coverage came later. The lag had hidden
  that waste by letting the marks pile up. A far section is now meshed
  again only when it reads the quarter written: 71,464 meshes in the
  minute against 93,283 before the study, the near and middle trees there
  at 25 s with the horizon whole. This was W3's lead for the far
  sections; what is left of W3 is the near sections, meshed once per
  quarter.

## Limits of this measure

One run, one world, one position, 12 chunks of game view, single player.
Through a dedicated server the real sections come already built and W4's
share should be much smaller; not measured. The probe counted jobs
requested; a job's cost varies from one window to the next (3.7 ms on
average in the first 10 s, 2.1 to 3.2 ms in the next four).
