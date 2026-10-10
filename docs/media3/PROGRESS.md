# JoTube M3 progress

Status: ACTIVE
Next step: 1b (fork changes in exoplayer-amzn-2.10.6 and their Media3 counterparts, see PLAN.md)

Status values: ACTIVE (the scheduled runs keep going), WAITING_FOR_JOHAN (a question to Johan, runs only check
for his answer), BLOCKED (a stage can't be made stable; reason below), DONE (stage 6 finished).

## Usage guard (no unexpected stops)
The Claude plan's usage can't be read by a run, so the work is cut into pieces that survive a cut-off:
- Every run first writes `Run: STARTED <time>` below, works on ONE small step, commits and pushes after each
  meaningful change (never more than ~30 minutes of work uncommitted), then writes `Run: FINISHED <time>`.
- A run that finds `STARTED` without `FINISHED` knows the previous one was cut off (usually the usage limit):
  it checks what was pushed, notes "cut off" in the log and halves the step size (`Step size` below).
- Two cut-offs in a row: the run only resumes the unfinished step, nothing new, and tells Johan.
- Runs are scheduled every 5 hours (the usage window), so each one starts with a fresh window.
- API credits: hard cap per claude-task run and a total budget (below). At 12.00 USD spent the runs stop using
  the API and tell Johan; the last 2.00 USD stay untouched.

Run: FINISHED 2026-10-10T13:38Z
Step size: normal (one sub-step, e.g. "1a")
Cut-offs in a row: 0

## API credits (Claude Console, used only by the claude-task workflow)
Johan had 14.00 USD left on 2026-10-10. Budget for this project: 12.00 USD in total (2.00 USD stays as a reserve).
Each claude-task run has a hard cap in `.claude-task/BUDGET_USD` (at most 1.50 USD); its real cost is in
`.claude-task/COST_USD` on the task branch. Add every run here.

| Date | Task branch | Budget | Cost (USD) | Result |
|------|-------------|--------|------------|--------|

Spent so far: 0.00 USD

## Run log
- 2026-10-10: branch `media3` created from master (j66, 997b69a). Package org.smarttube.johan.media3, app
  "JoTube M3", CI build-media3.yml (pre-release latest-media3), claude-task.yml with a budget cap and cost logging,
  PLAN.md, this file, CLAUDE.md. Scheduled runs set up (every 5 hours, at the usage resets).
- 2026-10-10: 1a done: `docs/media3/INVENTORY.md` (36 app files + doubletapplayerview, grouped by feature with
  Media3 counterparts; unsure mappings marked (?) for 1d). Found: doubletapplayerview also depends on the fork; no app
  use of the ffmpeg/vp9/opus/flac extensions; MediaSessionConnector and ControlDispatcher have no direct Media3
  counterpart. Docs only, no CI build needed.
