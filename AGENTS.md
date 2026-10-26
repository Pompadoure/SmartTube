# AGENTS.md: how this repository works (for Jules, Claude and any other agent)

Repository: **Pompadoure/SmartTube**, Johan's personal fork of the SmartTube Android TV YouTube client
("JoTube"). It is a fork of `yuliskov/SmartTube`. **Push only to Pompadoure/SmartTube, never to the upstream project.**
The repo has ~60 branches; almost all of them are short-lived work branches. Only the five long-lived ones below matter.

## 1. Read this first, every time (Jules: read section 8, Standing orders, before anything else)
1. This file.
2. The **agent board**: branch `agent-board`, file `BOARD.md` (`git fetch origin agent-board && git show origin/agent-board:BOARD.md`).
   It lists who is working on what. **Claim your task there before you start** and release it when you are done.
   Rules for the board are at the top of that file.
3. `CLAUDE.md` (the hard rules, repeated in section 4).
4. For work on `media3`: `docs/media3/PLAN.md` and `docs/media3/PROGRESS.md`.

## 2. Branches
| Branch | What it is | Who may push |
|---|---|---|
| `master` | The daily app "JoTube" (package `app.smarttube`, release tag `latest-j`). **Frozen**: no feature work. | Nobody, unless Johan asks in plain words. |
| `media3` | The Media3 rewrite of the TV app ("JoTube M3", package `org.smarttube.johan.media3`, pre-release `latest-media3`). Active development. | Via short-lived `m3/<topic>` branches merged into it. |
| `media3-mobile` | The phone/tablet app on top of `media3` (module `smarttubemobile`, release `latest-mobile`). | Via `m3m/<topic>` branches. |
| `agent-board` | Only `BOARD.md`: the shared task board between agents. | Both agents push directly (small commits, rebase on conflict). |
| `m/*`, `m3/*`, `m3m/*`, `claude-task/*` | Short-lived work branches. `m3/*` builds in CI but never publishes. | The agent that owns the branch (see the board). |

- Start new work from the up-to-date branch it targets (`git fetch origin` first). Name branches `m3/<topic>` (TV),
  `m3m/<topic>` (mobile). Jules: use `m3/jules-<topic>` so branch owners are obvious.
- Merge into `media3` with a pull request (or `--no-ff` merge) only when CI on your branch is green. A push to `media3`
  triggers a signed release build, so do not push to it for trivial reasons.
- **Never push to `master`, never merge anything into `master`, never touch the `latest-j` release.**
- Never delete branches you did not create; list them on the board instead.

## 3. Layout (module map)
- `smarttubetv/`: the TV app (leanback UI, `PlaybackFragment`, Shorts UI: `ShortsPreview`, `ShortsLiveSwitch`).
- `common/`: shared logic: presenters, `ExoPlayerController`, track selection, renderers, `FastStartLoadControl`.
- `media3-sabr/`: SABR streaming (YouTube's adaptive protocol) for AndroidX Media3, with unit tests.
- `smarttubemobile/` (only on `media3-mobile`): the phone app.
- `MediaServiceCore/`, `SharedModules/`: **git submodules at the upstream base**. See section 5.
- `exoplayer-amzn-2.10.6/`: the old ExoPlayer fork, only used by `master`; out of the build on `media3`.
- `patches/MediaServiceCore/NNNN-*.patch`: our changes to MediaServiceCore (applied in order by CI; latest is 0016).
- `docs/media3/`: plan, progress log, inventory, test list, design notes (`ROLE_SWAP.md` = Shorts player design).
- `.github/workflows/`: `build-media3.yml` (TV media3), `build-mobile.yml`, `build-johan.yml` (master),
  `claude-task.yml` (paid Claude runs), `cleanup.yml`, `stale.yml`.

Flavors (TV app, `smarttubetv/build.gradle`): `stjohan` is the one Johan uses on `media3`
(`applicationId org.smarttube.johan.media3`, installs next to the daily app). Target devices: Nvidia-like Android TV
"Streamer" (armeabi-v7a, MediaTek, 1080p) and a Mi Box.

## 4. Hard rules (from CLAUDE.md; they apply to every agent)
- Push only to Pompadoure/SmartTube, never upstream (`yuliskov/SmartTube`).
- On `media3`: never push to `master`, never merge into `master`, never touch the `latest-j` release.
- **Nothing from `media3` is installed on any device until Johan says so.** Agents do not install APKs on his devices
  on their own. Do not start videos, launch apps or send input to the TV without Johan's permission.
- The signing key and keystore never go into the repo (CI uses GitHub secrets `SIGNING_KEY`, `KEY_STORE_PASSWORD`,
  `ALIAS`, `KEY_PASSWORD`). Never print or log them.
- Never commit an app icon or logo showing Johan's face (the current faceless logo is fine).
- MediaServiceCore: change it only with a **new** patch file in `patches/MediaServiceCore/` (next free number).
  Never commit a changed submodule pointer.
- **Never fetch video format info in parallel and never cancel such a fetch half-way** (YouTube answers 403).
  `FormatFetchLock` serializes this; do not bypass it.
- Root causes from evidence (code, CI logs, device logs). No guessing, no "try and see" fixes.
- An independent review (a second agent, not the author) checks a change before a build is called done.
- API credits are scarce: `claude-task` runs need `.claude-task/BUDGET_USD` (max 1.50) and are logged in PROGRESS.md.
- Reports to Johan are **short and in Swedish**. Code, commits and docs are in English.

## 5. Patching MediaServiceCore (how to do it)
```
git submodule update --init
cd MediaServiceCore
for p in ../patches/MediaServiceCore/*.patch; do git apply "$p"; done   # all existing patches, in order
git add -A                       # stage them so the next diff shows only your change
# ...edit files...
git diff > ../patches/MediaServiceCore/00NN-short-name.patch   # NN = next number
git reset --hard && git clean -fdq   # leave the submodule at its upstream base
cd .. && git status               # the submodule must NOT show as modified
```
Some upstream files use CRLF line endings: keep them (edit with a tool that preserves them).

## 6. Build and test
- JDK 17, Android SDK as in the workflow. CI does: init submodules, apply patches, then
  `./gradlew assembleStjohanRelease :media3-sabr:assembleRelease :media3-sabr:testReleaseUnitTest`.
  Locally you need the signing secrets for a release build; without them run `./gradlew :media3-sabr:testReleaseUnitTest`
  and compile checks (`:common:compileStjohanReleaseJavaWithJavac` or similar).
- Real verification is CI plus Johan's device. A green build is necessary, not sufficient, for playback changes: say in
  the PR what was and was not tested on a device.
- Releases: a push to `media3` publishes `latest-media3` (signed APKs per ABI + `SHA256SUMS.txt`). The Streamer needs the
  armeabi-v7a APK. `m3/**` branches build but never publish.

## 7. How agents work together (summary; details on the board)
- The board is the single place to say "I am working on X". Check it before you start, claim before you edit, release
  when you merge or give up. Claims expire after 24 h without an update.
- One owner per area at a time. Areas: `Shorts player` (ShortsPreview, ShortsLiveSwitch, ExoPlayerController Shorts
  paths), `SABR / media3-sabr`, `MediaServiceCore patches`, `TV UI`, `Mobile app`, `CI / release`, `Docs`.
- Do not touch another agent's claimed files. If you need a change there, leave a request on the board.
- Small, focused branches; rebase on the target branch before opening a PR; keep PRs reviewable.
- Update `docs/media3/PROGRESS.md` when you finish a step on `media3`, but only the lines of your own step
  (append, do not rewrite) to avoid conflicts.

## 8. Standing orders for Jules (binding, Johan's instruction 2026-10-26)
Jules, you have broken things before (wrong base branch, a PR with 345 files, line endings changed in ~70 files, a task that
ended in "unexpected error" without a word). That must not happen again. Follow these every single time:
1. **Base branch**: use exactly the base branch named in the issue. Run `git fetch origin` and create your branch from
   `origin/<base>`. Open the PR with that base. Never `master`, unless the issue says so (it never does).
2. **Scope**: change only the files the issue allows. No clean-ups, no reformatting, no renames, no `.gitignore` edits, no
   submodule changes, no deleting files you did not create. If something looks wrong in the repo (a huge diff, "unusually
   large diff" warnings, dirty tree): STOP and post `NEEDS INPUT:`. Do not try to fix the repo yourself.
3. **Line endings**: many files are CRLF. Keep each file's line endings exactly. Never save a file with converted endings. Check
   with `git diff --stat origin/<base>...HEAD`: it must list only your task's files, with small numbers. If it lists more, fix
   it before you push or open the PR.
4. **Logging, in GitHub comments (Claude cannot see the Jules app)**. Post on the issue:
   - `PROGRESS:` right at the start (your plan: which files you will touch, how you will verify) and after each larger step.
   - Paste real command output when it matters: build/test errors verbatim (first 30 lines), `git diff --stat`, what you ran.
   - `FAILED:` immediately if you hit an error you cannot solve or the task is about to end abnormally: the last step done,
     the exact error text, what you tried. Never end a task silently or with a generic "unexpected error".
   - `DONE:` at the end: branch, PR link, changed files, what you verified, what you could not verify (state clearly if the code
     was never compiled).
5. **Verification**: if you can compile or run tests, do it and paste the result. If you cannot, say so; never write "tested" when
   you did not. CI (GitHub Actions) is the judge; wait for it and report its result.
6. **Questions**: ask early with `NEEDS INPUT:` (one concrete question, options, your recommendation). If no `DECISION:` arrives
   within 1 hour, continue with your recommendation and say so in a comment.
7. **Do not merge** your own PR and do not touch other agents' branches.
Claude reads your comments and reacts with 👀 when read. Claude answers with `DECISION:` or `REVIEW:`.
