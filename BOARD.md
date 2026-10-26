# Agent board (Pompadoure/SmartTube)

Shared between Claude and Google Jules (and Johan). Lives only on branch `agent-board`.
Read `AGENTS.md` (on `media3`) for how the repo works.

## Who decides
**Claude leads, Jules executes** (Johan's decision 2026-10-26). Claude assigns work to Jules in "Task queue for Jules" below.
Jules works ONLY on tasks from that queue (top task first), claims it under "Active claims", and reports the result in
the Log and in the PR description. Jules does not pick its own tasks and does not change the queue; if the queue is empty
or a task is unclear/blocked, Jules writes that under "Requests" and stops. Claude reviews Jules' PRs before merge.
Johan's instructions override both.

## Task queue for Jules (Claude writes here; top = next)
| # | Task | Branch to create | Allowed files / areas | Done when |
|---|---|---|---|---|
| 0 | TEST TASK: create `docs/agents/jules-hello.md` containing (a) the branch you based your work on, (b) the 5 most important hard rules from AGENTS.md section 4 in your own words, (c) one question for Claude about the repo. No other file changes. | m3/jules-hello | `docs/agents/jules-hello.md` only | PR into `media3`; Log line on the board |
| 1 | Unit tests for `media3-sabr` (no behaviour change; test only what exists) | m3/jules-sabr-tests | `media3-sabr/src/test/**` only | `:media3-sabr:testReleaseUnitTest` green in CI; PR into `media3` |

## How Jules asks for input (Johan's decision 2026-10-26)
Jules: if you need input, a decision, or you are blocked, do not stop silently. Write a comment on your issue/PR that starts
with `NEEDS INPUT:` and asks one concrete question (also add it under "Requests" here). Claude checks all comments hourly
(scheduled task), answers in the same thread and decides. Johan only gets asked when Claude cannot decide.
Dispatch: Claude creates a GitHub issue with the label `jules` for each queue task (Jules picks it up at once).

## Reply protocol (Jules <-> Claude), binding for every task
Claude reads **GitHub comments** (the issue that assigned your task, and your PR). Your chat in the Jules app is NOT read.
Always answer in the issue/PR thread of the task, starting the comment with one of these keywords:
- `PROGRESS:` (optional, long tasks) one or two lines: what is done, what is next.
- `NEEDS INPUT:` one concrete question, the options you see, and which one you recommend. Keep working on the parts that do
  not depend on the answer. If no `DECISION:` arrives within 1 hour, continue with your recommended option and say so.
- `DONE:` final report, always with: (1) branch and PR link (base `media3`), (2) changed files, (3) CI result,
  (4) what you tested and what you could NOT test (no device access), (5) open questions or follow-up ideas.
Claude answers in the same thread with `DECISION:` (the answer, binding) or `REVIEW:` (required changes / approved).
Also: one Log line here when you finish; put the same `DONE:` text in the PR description. Do not merge your own PR.
Do not create extra files for communication (no notes in docs/); this board and the comments are the channel.

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
- (Old note) Jules had no task; now see the queue. Suggested start: tasks that do not touch the Shorts player (docs, unit tests for `media3-sabr`, cleanup of stale branches listed under Log).

## Log (newest first)
- 2026-10-26 Claude: set the rule "Claude leads, Jules executes" and added the task queue.
- 2026-10-25 Claude: created AGENTS.md (branch m3/agents-md) and this board.
