# Agent playtesting: letting an agent test Farfield like a player

Date: 2026-10-29. Status: analysis, nothing decided yet (no ADR).

## Why

On 2026-10-28, eight fixes (ADR-087 to ADR-094) each needed the user to
deploy a jar, fly over new terrain in the pack, spot a one-frame band or a
level flash, and send a log. The agent could not reproduce the case alone:

- the dev client needs the user's consent and an idle, unlocked PC
  (`farfield-dev-client-check`), because it drives the real window with OS
  keystrokes;
- that skill carries about 40 lines of keystroke hazards: chat key rebound,
  AZERTY dead keys (`~`, `^` turned a relative `/tp` into an absolute one
  underground), `SetForegroundWindow` failing silently, Escape opening the
  menu;
- the glitches are transient (one frame, a few frames at a level change),
  so a screenshot misses them and a pixel pass over a video missed them too
  (memory `validate-glitch-detectors`);
- the pack runs Sodium 0.8.13 + Iris on NeoForge; the dev clients run
  neither, so a clean dev run does not clear a pack bug.

The goal: the agent reproduces "fly over new terrain", detects the glitch
itself with ground truth from the engine, and only hands the user a jar
that already passed.

## What exists (state of the art, 2026-10)

### 1. In-game agent bridges (MCP mods)

A dev-only client mod that exposes the running game to an agent, usually
over MCP. All found are young (0 to 2 stars, 3 to 16 commits) and several
were written by AI agents.

| Project | MC / loader | Notes |
|---|---|---|
| [CoolerProYT/MC-Test](https://github.com/CoolerProYT/MC-Test) | 26.1.2 to 26.3, Fabric + NeoForge, MIT | Most complete. "Playwright for Minecraft": ~60 tools (screenshot, GUI snapshot/click, keys, look/teleport, A* goto, block/entity inspection, `mc_wait_for` on screen/block/log/chat, time freeze/step/**lockstep**, GameTest runs, launch/close, dedicated-server twin API). Input goes through Minecraft's own handlers, not the OS, so the window can stay unfocused. Node MCP server + WebSocket JSON-RPC on loopback. |
| [GreedyCraftNirvanaMC/mcptest-1.21.1](https://github.com/GreedyCraftNirvanaMC/mcptest-1.21.1) | **1.21.1, NeoForge 21.1** | The only one on our version. 37 tools, HTTP MCP on `127.0.0.1:8765` with a bearer token, PNG screenshot, cursor-based event log, `set_pause_on_lost_focus`. 5 commits, license not stated, README says real-client verification is still pending. Mining/attack holds need mouse capture. |
| [dgurney/minecraft-mcp](https://github.com/dgurney/minecraft-mcp) | 26.3 Fabric, MIT | Small and clean: status, blocks/surface, entities, screenshot (HUD hidden), batched commands, input, `watch_blocks` tick by tick. Input injected into the game's handlers; window must not be minimized. No auth. |
| [Tuan6b/agent_mcp-template-1.21.11](https://github.com/Tuan6b/agent_mcp-template-1.21.11) | 1.21.11 / 26.3 Fabric | Aimed at shader and mod testing: `look` at a target, `wait` N ticks or N **rendered frames**, screenshot with shaders, Iris reload/set pack, `read_log` with a regex. License unclear (Apache-2.0 vs CC0). |
| [cuspymd/mcp-server-mod](https://github.com/cuspymd/mcp-server-mod), [InventivetalentDev/minecraft-mcp](https://glama.ai/mcp/servers/InventivetalentDev/minecraft-mcp), [ClaudeCraft MCP](https://www.curseforge.com/minecraft/mc-mods/claudecraft-mcp) | various | Command-oriented; less useful for rendering. |

**For us:** the design is right (in-process input, no OS focus,
framebuffer screenshots, wait conditions, time lockstep), but none fits
as is: the only 1.21.1 one is NeoForge-only, unproven and unlicensed;
the good ones are 26.x. MC-Test (MIT) is the best model to copy the tool
surface from, and a direct candidate once we port to 26.x.

### 2. Loader test frameworks

- **Fabric client game tests** ([docs](https://docs.fabricmc.net/develop/automatic-testing)):
  experimental Client Game Test API added in Fabric API 0.112.1+1.21.4,
  screenshot comparison API in 0.115.0+1.21.4
  ([0.112.1 notes](https://newreleases.io/project/github/FabricMC/fabric-api/release/0.112.1%2B1.21.4)).
  A test opens a singleplayer world, waits for chunks to render, presses
  keys, takes and compares screenshots; `runClientGameTest` locally,
  `runProductionClientGameTest` with Xvfb in CI. **Not on 1.21.1**: it
  starts at 1.21.4. Worth adopting on the 26.x port.
- **NeoForge GameTest + TestFramework** (`net.neoforged.testframework`,
  [gist](https://gist.github.com/Matyrobbrt/cac2427748a51ff2bfc82fcd03606b55)):
  server-side structure tests, plus a client configuration; JEI runs
  NeoForge client game tests in CI with JUnit XML
  ([JEI changelog](https://www.curseforge.com/minecraft/mc-mods/jei/files/8406339)).
  Server GameTests suit the LOD pipeline (write a chunk, assert the
  section and the push), not the rendering.

### 3. Headless clients and CI

- **HeadlessMC** ([repo](https://github.com/headlesshq/headlessmc)): CLI
  launcher that runs the real client without a screen, either by patching
  LWJGL (no rendering at all) or under Xvfb; Docker image
  `3arthqu4ke/headlessmc`; handles loaders and modpacks.
- **mc-runtime-test** ([action](https://github.com/marketplace/actions/mc-runtime-test)):
  GitHub Action on top of it; boots the client in CI, joins a world, runs
  `/test runall`. Catches boot crashes and mixin failures on both loaders.
- **Software rendering**: Mesa llvmpipe exposes an OpenGL 4.6 core
  context when asked for one
  ([Phoronix](https://www.phoronix.com/news/Lavapipe-ARB_gl_spirv),
  [Mesa 24.1.6 notes](https://docs.mesa3d.org/relnotes/24.1.6.html)), so
  our GL 4.6 renderer could draw under Xvfb on a CPU, slowly. Not checked
  with our shaders.

**For us:** a cheap CI smoke test (both loaders boot, LOD renderer
starts, no `Mixin apply failed`), and possibly a correctness rig off the
user's PC. Not for performance.

### 4. Protocol bots and LLM players

- **Mineflayer** (PrismarineJS, supports 1.21.1 via minecraft-protocol):
  a headless Node bot, no rendering. **Mindcraft**, Voyager and similar
  LLM agents build on it; they play from text observations.
- **For us:** irrelevant for rendering. Useful only to load the server:
  N bots flying over new terrain to measure `StreamServer` push delay and
  bandwidth without a client. Our protocol is custom, so the bots would
  only drive the server, not read our sections, unless we write a decoder.

### 5. Camera paths and image comparison

- **Replay Mod / Flashback / Lumiera**: keyframed camera paths
  (position + rotation, linear or cubic). Replay worlds do not run our
  server stream, so they cannot reproduce streaming bugs. The idea worth
  taking: a camera path as data, played back identically.
- **Image diff**: [odiff](https://github.com/kamieen/odiff) (fast pixel
  diff, exit codes, ignore regions), pixelmatch, blazediff (pixel + SSIM
  with a `--min-score` threshold). Fine for golden-image regression at a
  fixed spot and time, too noisy for transient glitches.
- **RenderDoc** (known tool, not researched here): command-line frame
  capture plus a Python API; good for a deep look at one bad frame once a
  detector has found it.

## Pros and cons for Farfield

| Option | Pros | Cons | Value |
|---|---|---|---|
| In-process agent bridge (ours, modeled on MC-Test) | No OS focus or keystrokes: the 40 lines of hazards go away; framebuffer screenshots work behind other windows; we add Farfield tools | Work to write and keep on two loaders; still needs a GPU and an unlocked session on this PC | **High** |
| Adopt mcptest-1.21.1 as is | Exists, NeoForge 1.21.1 | NeoForge only, unproven, no license, generic tools | Low: read it for ideas |
| Adopt MC-Test as is | Richest, MIT, both loaders | 26.x only | High after the 26.x port |
| Fabric client game tests | Official, screenshot assertions, CI | 1.21.4+ only, Fabric only | Later (26.x) |
| HeadlessMC / mc-runtime-test in CI | Free boot and mixin checks on every push | Says nothing about the far view | Medium, cheap |
| Xvfb + llvmpipe rig (lakio-vault or CI) | Off the user's PC, any time | Slow; GL 4.6 path unchecked; not the user's GPU or Sodium | Spike first |
| Mineflayer bots | Server load tests with no client | No rendering, no Farfield protocol | Low |
| Golden images + SSIM | Catches regressions of a still view | Misses one-frame glitches; noisy far band | Medium |
| Camera path replay (our own) | Same flight every run, before and after a fix | Must run against the live stream, so through our harness | **High** |

## What we should build

The core idea: **the engine says when a frame is wrong, the agent replays
the flight that shows it**. Pixels come last, as evidence for the user.

### A. Agent inbox in the dev client (start here)

A dev-only client hook (system property `farfield.agent=true`, set by the
Gradle run tasks, never in the shipped config) that polls
`<run dir>/farfield-agent/inbox/` each client tick and writes replies to
`outbox.jsonl`:

- `command <text>`: runs a chat command through the client's command
  path, no keystrokes;
- `screenshot <name> [hud=false]`: reads the main render target back
  (works while the window is covered);
- `look <yaw> <pitch>`, `tp <x> <y> <z>` (server side in single player);
- `state`: position, LOD stats (wanted, in flight, meshing, drawn), FPS;
- `wait stable [timeout]`: replies once the renderer shows 0 wanted,
  0 in flight, 0 meshing for two stat periods.

Files instead of MCP: Claude Code drives it with Bash and reads PNGs with
`Read`; no Node server, no port, nothing to secure. An MCP front can come
later on top of the same handlers.

### B. Flight scenarios and the user's flights as data

- A scenario file: start point, waypoints, speed in blocks/s, look
  direction, time of day, weather, settle rules, capture points.
- The client plays it tick by tick (server-side teleports in single
  player, or creative flight inputs injected in-process for a joined
  server), with the time frozen where possible (MC-Test's lockstep idea).
- **Recorder**: a client keybind or `/farfieldclient record` that writes
  the user's own flight (position and rotation per tick) next to the log.
  When the user sees a glitch, they send that file once; the agent
  replays the exact flight before and after each fix instead of asking
  for a new test.

### C. A frame oracle in the engine

Detect glitches from renderer state, per frame, with the frame number:

- extend the ADR-077 hole detector into events: a section that stops
  drawing with nothing covering its octant in the same frame (the
  "flash"), a preview mask change applied before its replacement is
  meshed, a level change that leaves a gap;
- optional GPU coverage pass: render a tiny level-ID buffer (which LOD
  level or vanilla drew each pixel) and count "sky below the horizon" per
  frame; this sees what the user sees without judging colors;
- a ring buffer of the last ~60 frames, downscaled, dumped to PNG when an
  event fires, so the agent reads the 5 frames around the glitch, not a
  video.

Per memory `validate-glitch-detectors`: before trusting the oracle, run
it on the flights the user annotated on 2026-10-28 and check that it
fires on their frames and not elsewhere.

### D. A pack-like dev instance

Our run dir with the pack's renderer mods (Sodium 0.8.13, Iris, the
pack's NeoForge version) plus the agent hook, or a copy of the CurseForge
instance launched from the command line (HeadlessMC can launch modpacks)
with the dev jar added. Then "seen only in the pack" can be checked
without the user.

### E. CI smoke tests

mc-runtime-test on both loaders: boot, join a world, `LOD renderer: N
sections drawn` with N > 0, no `Mixin apply failed`. Later, on 26.x,
Fabric client game tests with a few golden views compared by SSIM.

### Open point: running off the user's PC

The dev client draws nothing while the Windows session is locked, so even
with A to C the agent needs the PC idle and unlocked. Two spikes to try,
neither checked:

- Xvfb + Mesa llvmpipe on Linux (lakio-vault, in Docker with HeadlessMC):
  does our GL 4.6 renderer start, and is it fast enough for a 30 s
  flight at 854x480?
- A Hyper-V VM with GPU partitioning on this PC: does it keep rendering
  with no client connected?

If one works, the agent tests at night and the consent question
(`dev-client-launch-consent`) mostly goes away for that rig.

## Proposed order and estimates

| Step | Content | Estimate |
|---|---|---|
| 1 | A: inbox, command, screenshot readback, state, wait stable | half a day |
| 2 | B: recorder + replay of a flight | half a day to a day |
| 3 | C: oracle events + frame ring buffer, validated on the 2026-10-28 frames | 1 to 2 days |
| 4 | D: pack-like instance with Sodium + Iris | half a day |
| 5 | E: mc-runtime-test in CI | 2 hours |
| 6 | Off-PC spikes (llvmpipe, Hyper-V) | half a day each, outcome unknown |

Steps 1 and 2 alone remove the keystroke hazards and let the agent replay
the user's exact flight; step 3 is what lets the agent say "fixed"
without asking the user to fly again.

## Review (2026-10-29, second pass)

A second read against the progress log of the day found five gaps; they
change the order above.

1. **A replay needs fresh terrain.** The open bug shows "only where the
   preview gives way to the LOD, once per chunk": a second flight over
   the same path shows nothing. Each run starts from a pristine copy of
   the world with the LOD stores emptied (server side and the client
   mirror), or it reproduces nothing.
2. **The open bug is pack-only.** The NeoForge dev client shows no bands
   (`docs/progress/2026-10-29.md`); steps 1 to 3 in the plain dev client
   would not reproduce it. The pack-like instance moves up, and the
   agent hook must run in the user's CurseForge instance as well (the dev
   jar plus `-Dfarfield.agent=true`), not only in `runClient`.
3. **An engine-state oracle can be blind to it.** With ADR-094 the holes
   line reported nothing while the user saw bands. If Sodium or Iris
   damages the image after our pass, the renderer's state says "fine".
   The discriminating detector compares what Farfield believes it drew
   (its level-ID buffer) with the final image: a pixel we drew that ends
   up sky-coloured points at the other mod.
4. **Validation as written is not possible.** An in-engine detector
   cannot run on a past video, and the 2026-10-28 flights were not
   recorded. Two separate validations instead: a pixel "flash" detector
   checked against the 18 annotated frames; the in-engine detector
   checked by reproducing the case (pack, fresh terrain) once while the
   user watches.
5. **No determinism.** Freezing world time does not freeze our
   generation and meshing threads, so two replays never give the same
   frames. Judge a fix over N runs (about 5) by counting events, not on
   one before/after pair.

Smaller points: covered-window capture already works with `PrintWindow`
(the gain of A is the keystrokes, not the capture). Before the off-PC
spikes, two 10 minute checks: does a client started *before* the lock
keep drawing (only a start while locked is known to hang), and does
`tscon` to the console keep an RDP session unlocked after a disconnect.

### Revised order

| Step | Content | Estimate |
|---|---|---|
| 1 | A: inbox, command, screenshot readback, state, wait stable; usable in the pack instance from the start | half a day |
| 2 | D: pack instance driven by the agent; pristine world and LOD stores reset on demand | half a day |
| 3 | B: recorder + replay, judged over N runs | half a day to a day |
| 4 | C: "drawn by Farfield but sky on screen" detector + ring buffer of the last 60 frames | 1 to 2 days |
| 5 | E: CI smoke test, then the off-PC spikes | 2 hours, then half a day each |

Steps 1 and 2 already let the agent run the Sodium/Iris A/B of the
2026-10-29 log alone, before any detector.
