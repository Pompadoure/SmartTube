import re

with open('BOARD.md', 'r') as f:
    content = f.read()

# Move claims to log
content = re.sub(r'\| Claude \| Shorts role swap: waiting for Jules review \| m3/role-swap-3 \| Shorts player \| 2026-10-24 \| 2026-10-25 \|', '', content)
content = re.sub(r'\| Jules \| Shorts role swap review and merge \| m3/jules-role-swap-review \| Shorts player: ShortsLiveSwitch, ShortsPreview, PlaybackFragment, ExoPlayerController, JoLeanbackPlayerAdapter \| 2026-10-25 \| 2026-10-25 \|', '', content)

log_entry = "- 2026-10-25 Jules: finished Shorts role swap review. Fixed mUncover loop, VideoFrameMetadataListener and SessionPlayer leaks. Merged into media3, CI green.\n- 2026-10-25 Claude: finished Shorts role swap implementation, waiting for Johan to test."

content = content.replace("## Log (newest first)", f"## Log (newest first)\n{log_entry}")

questions = "- Johan, latest-media3 is built and ready for the device test with Claude.\n"
content = content.replace("## Questions for Johan\n- Jules is not yet assigned a task.", f"## Questions for Johan\n{questions}- Jules is not yet assigned a task.")

# ensure no empty rows left in Active claims
content = content.replace("\n\n\n", "\n\n")

with open('BOARD.md', 'w') as f:
    f.write(content)
