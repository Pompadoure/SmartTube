# Agent board (Pompadoure/SmartTube)

Shared between Claude and Google Jules (and Johan). Lives only on branch `agent-board`.
Read `AGENTS.md` (on `media3`) for how the repo works.

## Rules
1. **Read this file before you start any work** (`git fetch origin agent-board && git show origin/agent-board:BOARD.md`),
   and again before you merge or push to `media3` / `media3-mobile`.
2. **Claim before you edit**: add a row under "Active claims" (agent, task, branch, files/areas, started UTC) and push
   to `agent-board` right away. If your area overlaps a claim by another agent, do not start: add a request under
   "Requests" instead, or pick another task.
3. **Release when done** (merged, abandoned or blocked): move your row to "Log" with the outcome and the commit/PR.
4. Claims with no update for 24 h count as stale: any agent may ask on the board, then take over.
5. Edit only your own rows (and add requests to others). Commit small, `git pull --rebase` before pushing; this file
   is the only thing on this branch, so conflicts are trivial. Never rewrite history of this branch.
6. Anything Johan must decide goes under "Questions for Johan" (he reads Swedish and English).
7. Hard rules are in `CLAUDE.md`/`AGENTS.md` section 4 (no push to master, nothing installed on devices, etc.).

Areas: `Shorts player`, `SABR / media3-sabr`, `MediaServiceCore patches`, `TV UI`, `Mobile app`, `CI / release`, `Docs`.

## Active claims
| Agent | Task | Branch | Areas / files | Started (UTC) | Updated (UTC) |
|---|---|---|---|---|---|
| Claude | Shorts role swap: remove the still image at the hand-over (review + merge) | m3/role-swap-3 | Shorts player: ShortsLiveSwitch, ShortsPreview, PlaybackFragment, ExoPlayerController, JoLeanbackPlayerAdapter | 2026-10-24 | 2026-10-25 |

## Requests (agent to agent)
_none_

## Questions for Johan
- Jules is not yet assigned a task. Suggested start: tasks that do not touch the Shorts player (docs, unit tests for `media3-sabr`, cleanup of stale branches listed under Log).

## Log (newest first)
- 2026-10-25 Claude: created AGENTS.md (branch m3/agents-md) and this board.
