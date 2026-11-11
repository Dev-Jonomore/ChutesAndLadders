# Chutes and Ladders — design spec

Paper plugin, Minecraft/Paper **26.3**, Java **25**, Gradle 9.9.1 (Kotlin DSL), `paper-plugin.yml`.
Base package `mc.jonomore.chutes`. No NMS, no PacketEvents, no other plugin dependencies.

## 1. Game rules

- A **board** is `N` rooms, indices `0..N-1`. Index `N` is the finish. Reaching `>= N` wins.
- A **pawn** is a team of 1..`max-team-size` players. The whole pawn is always in the same
  room and moves together. Solo play is a pawn of one.
- Play is **real-time**: all pawns act simultaneously. Every pawn tracks **plies** (exits
  taken, including ladder/chute exits) and **elapsed ticks** from game start to finish.
- Every room is a "find the button" room. The button exists **per pawn** (see §4), at a spot
  chosen at random from the template's candidate spots, so pawns cannot copy each other.
- Room kinds:
  - `NORMAL` — pressing the exit rolls `1..max-roll` and moves the pawn forward that far.
  - `BREAK` — every `break-interval`-th room. Exit is not hidden (glowing, at the first
    candidate spot). Heals and feeds the pawn. Exit behaves like `NORMAL`. If
    `break-checkpoint` is on, a chute can never drop the pawn below the last break room it
    has visited.
  - `LADDER` / `CHUTE` — forced, as in the board game. The only exit starts a **transit**.
    The room kind is announced on arrival (title + sound).
- Room 0 is always `NORMAL`. The first `safe-start` rooms are never ladders or chutes.
- Players are in adventure mode, invulnerable and hunger-free everywhere except inside a
  wave challenge.

### Transit scoring

A transit has a signed `delta` (ladder > 0, chute < 0) and a challenge. The challenge ends
with `ChallengeResult(completed, progress 0..1, elapsedTicks, parTicks)`.

| Transit | Result | Rooms moved |
|---|---|---|
| Ladder `+L` | completed | `+L` |
| Ladder `+L` | failed | `+max(ladder-min-gain, floor(L * progress))`, capped at `L` |
| Chute `-C` | completed | `-clamp(ceil(C * elapsed / par), chute-min-regress, C)` |
| Chute `-C` | failed | `-C` |

Destination is clamped to `[0, N]`, then to the checkpoint floor for chutes.
Landing in another ladder/chute room after a partial result is allowed; that room is then
forced as usual.

### Challenges

Both types work for ladders and chutes. Type is fixed per transit at board generation.

- **MAZE** — stacked floors, each a generated perfect 2D maze (`cells` x `cells`) with one
  way to the next floor. Floors `F = clamp(ceil(|delta| / rooms-per-floor), min-floors,
  max-floors)`. Time limit and par are `F * seconds-per-floor`.
  - Ladder: start at the bottom, climb. `progress = highest floor reached / F`. Reaching the
    top = completed. Timer expiry = failed.
  - Chute: start at the top, descend. Reaching the bottom = completed. Timer expiry = failed.
  - Maze seed is derived from board seed + room index, so every pawn taking the same transit
    gets the same maze. Built on demand in the pawn's arena region, removed afterwards.
- **WAVES** — a wave arena (template of type `ARENA`, or the built-in fallback). Total mobs
  `= |delta| * mobs-per-room`, spawned in waves of `wave-size`; the next wave spawns when the
  current one is dead. Par/time limit `= total mobs * seconds-per-mob`.
  - `progress = kills / total`. All mobs dead = completed.
  - Failed when the timer expires or every participant is dead/offline. A participant who
    dies is put in spectator mode at the arena until the challenge ends (no death screen
    loop, no item drops).
  - Participants get a fixed kit for the duration; inventory is cleared before and after.

## 2. Board generation

`BoardGenerator.generate(BoardSettings, seed)` — deterministic for a given seed.

- Break rooms at every index `i > 0` with `i % break-interval == 0`.
- Candidate rooms for transits: not break, index `>= safe-start`.
- Roughly `ladder-density * N` ladders and `chute-density * N` chutes, each with length in
  `[min-length, max-length]`.
- A full ladder ends at `<= N-1`; a full chute ends at `>= 0`. The full destination of any
  transit must be a `NORMAL` or `BREAK` room (no guaranteed chains).
- No more than `max-roll - 1` consecutive chute rooms, so a chute can always be skipped by
  some roll.
- Challenge type per transit: `MAZE` with probability `maze-weight`, else `WAVES`.

## 3. World layout and templates

- A dedicated void world (`world` in config), created on enable if missing: no mobs, no
  weather, fixed time, no natural generation.
- Each running game gets a **session slot** `s`. `BoardWorld` hands out non-overlapping
  regions:
  - `roomOrigin(s, roomIndex)` — min corner of a `room-spacing`^2 footprint.
  - `arenaOrigin(s, pawnSlot)` — min corner of a 128 x 128 footprint, free from `base-y` to
    the world top, for one pawn's transit challenge.
- **Templates** have a type (`ROOM`, `BREAK`, `ARENA`), a size, an entrance (offset + yaw /
  pitch), candidate **button spots** (block offset + the face the button sits on) and mob
  spawn offsets (arenas). No rotation in v1.
- Stored with Paper's structure API as `templates/<type>/<id>.nbt` plus a sidecar
  `<id>.yml` holding the markers.
- Authored in-game:
  `/chutes template pos1|pos2` (corners), `entrance` (your position and facing),
  `button` (the block face you are looking at), `spawn` (your position),
  `save <type> <id>`, `list`, `delete <type> <id>`, `paste <type> <id>`.
- **Built-in fallback templates** are generated in code when a type has no saved templates,
  so the game is playable out of the box: a few randomly sized box rooms with clutter and
  several button spots, a plain break room, and a flat walled arena with spawn points.
- Rooms for a game are pasted spread over several ticks before the game starts. Which
  template a room index gets is chosen with a `Random` seeded from board seed + index.
  Everything a session placed is cleared when the session ends.

## 4. Per-pawn exit buttons

Each pawn in a room gets a `BlockDisplay` showing a button (oriented to the spot's face) and
an `Interaction` entity over it. Both are `setVisibleByDefault(false)` and shown only to the
pawn's members with `Player#showEntity`. Clicking the interaction (either mouse button)
triggers the exit. They are removed when the pawn leaves the room.

## 5. Sessions, modes and commands

- `/chutes solo [rooms] [seed]` — create and immediately start a one-pawn game.
- `/chutes create [rooms] [seed]` — open a lobby; creator is host.
- `/chutes join <host>` / `/chutes leave`
- `/chutes team <name>` — join/create a team inside the lobby (default: own team).
- `/chutes start` / `/chutes stop` — host or `chutes.admin`.
- `/chutes top <plies|time> [rooms]` — leaderboard.
- Race: first pawn to finish wins; the game continues until every pawn has finished or the
  host stops it. All finishers are recorded.
- Disconnect: the pawn keeps playing. A returning player is put back with their pawn. A pawn
  with nobody online for `rejoin-grace-seconds` is removed.
- On leaving a game, a player is restored to their previous location, game mode and
  inventory.
- Action bar HUD for every player in a game: room `i / N`, plies, elapsed time.

Permissions: `chutes.play` (default true), `chutes.admin` (default op; template commands,
stopping other people's games).

## 6. Stats

`StatsStore` persists finished runs to `stats.yml`: members, rooms, seed, plies, ticks, time.
Leaderboards are per `(rooms, team size)` by fewest plies or lowest ticks.

## 7. Code layout and ownership

Contracts (interfaces/records) are already in the repo and must not be changed without
saying so. Each workstream owns its packages and touches nothing else.

| Workstream | Packages | Entry points (exact names) |
|---|---|---|
| Logic | `board` (impl), `stats` (impl), tests | `board.RandomBoardGenerator()`, `board.TransitScoring`, `stats.YamlStatsStore(Path)` |
| World | `world` | `world.VoidBoardWorld(Plugin, ChutesConfig)`, `world.FileTemplateStore(Plugin)`, `world.TemplateCommands(Plugin, TemplateStore)` |
| Maze | `challenge.maze` | `challenge.maze.MazeChallengeFactory()` |
| Waves | `challenge.waves` | `challenge.waves.WaveChallengeFactory()` |
| Game | `game` | `game.GameManager(Plugin, Services)`, `game.GameCommands(GameManager)` |

`TransitScoring` signature:
`static int roomsMoved(Transit transit, ChallengeResult result, ChutesConfig.Scoring scoring)`
(signed; clamping to the board and checkpoint is the game's job).

`ChutesPlugin` wires everything and is edited only by the integrator.
