# JoTube (Pompadoure/SmartTube) — rules for Claude

This is Johan's personal fork of SmartTube. Branch `media3` = the Media3 rewrite ("JoTube M3"); `master` = the
daily JoTube. Read `docs/media3/PLAN.md` and `docs/media3/PROGRESS.md` before working on `media3`.

- Push only to Pompadoure/SmartTube, never to the upstream project.
- On `media3`: never push to `master`, never merge into `master`, never touch the `latest-j` release.
- Nothing from `media3` is installed on any device (Streamer or Mi Box) until Johan says the rewrite is done.
- The signing key and keystore never go into the repo (CI uses GitHub secrets).
- Never commit an app icon or logo with Johan's face (the current logo without a face is fine).
- MediaServiceCore is a submodule at its upstream base; change it only with new files in
  `patches/MediaServiceCore/` (CI applies them in order). Never commit the submodule pointer.
- Never fetch video format info in parallel and never cancel such a fetch half-way (403 errors).
- Root causes from evidence (code, CI, device logs), no guessing. An independent review agent checks changes
  before a build is called done.
- API credits are scarce: claude-task runs need `.claude-task/BUDGET_USD` (max 1.50) and are logged in PROGRESS.md.
- Reports to Johan: short, in Swedish.
