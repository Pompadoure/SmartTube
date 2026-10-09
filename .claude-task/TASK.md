# Task: preload card thumbnails in the browse screens (JoTube)

Repo: personal fork of SmartTube, an Android TV YouTube client (Java, AndroidX Leanback, Glide 4.11).
App code: `smarttubetv/` (UI: fragments, presenters, card views) and `common/` (presenters, models).

## Problem
On the home screen (rows of cards), in channels and in subscriptions (grids), card thumbnails are loaded by Glide only
when the card is bound / comes on screen. Scrolling down, the images visibly pop in. They should already be in Glide's
memory cache when the cards appear.

## What to do
1. Find where card images are loaded (card presenter / card view, e.g. `CardPresenter`, `VideoCardPresenter`,
   `ComplexImageCardView` or similar) and the exact Glide request (URL from Video.getCardImageUrl or similar, size
   override, transformations, placeholder, diskCacheStrategy). A preload only helps if it produces the SAME Glide
   memory-cache key: same model URL, same width/height, same transformations and options. Verify this carefully.
2. Implement preloading:
   - rows screen (home etc.): when the user focuses a row/card, preload the visible-soon cards: the next ~6 cards in the
     focused row and the first ~6 cards of the next 2 rows below; also preload when a new row/group arrives (first ~6).
   - grid screens (channel uploads, subscriptions): preload the next 2–3 grid rows below the focused item.
   - Use a central helper (e.g. `ThumbnailPreloader`) that dedups recent URLs, uses Glide's preload with the matching
     size/options, and never blocks the UI thread. Keep memory in mind (Android TV devices, moderate number of preloads;
     prefer the card's display size, not the original image size).
3. Don't change how cards look. Small, commented (`JoTube:`) changes; Java 8; no new dependencies. Don't touch
   `MediaServiceCore/`, `exoplayer-amzn-2.10.6/`, `.github/`.
You can't build or run anything here; a reviewer will check and build your diff. Make sure it compiles (imports,
types, Leanback APIs of the version used in the project).
In REPORT.md: where images were loaded, the exact cache-key match you ensured, what you changed, risks.
