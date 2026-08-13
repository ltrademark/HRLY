# HRLY To-Do List

## Current
- [ ] Add Widget Visual

## Feature Backlog (from GitHub issues)
- [ ] #13 (2nd part) An untrimmed custom tone is cut off at the *default* tone's length (500 / 1500 ms) instead of playing in full. `playFor` falls back to the hardcoded duration in `ChimeService.playTone`; read the file's real duration instead (`WaveformDecoder` already gets it for the trim dialog). Trimming and saving is the current workaround.

## Completed
- [x] clean up comments, dead variables, and other old testing code (if i haven't done that already).
- [x] Optional Vibration feature (for silent modes, or whatever) — GH #11 (play on vibrate, keep respecting DND)
- [x] Set up base project structure
- [x] Implement initial foreground service
- [x] Design app icon and branding
- [x] Finalize core service and chime logic
- [x] Implement UI for enabling/disabling service
- [x] Add custom sound selection
- [x] Have it Respect when device is in DND
- [x] Add a "quiet hours" setting row (schedule a start and end period where the service is "disabled". re-enables itself).
  - [-] Try to check if device can see if user has pre-set a "bedtime" that it can follow.
- [x] Add 3rd control to notification to "temporarily suspend", and if enabled, the option then turns into "enable". Of course, in the "suspend" state, there will not be a "skip" button option. 
- [x] Remove A.O.D. visual
- [x] Cleanup and modernize "about" popup
- [x] Change "about" pop-up trigger icon to (❔).
- [x] Revisit Battery optimization permission flow
- [x] #6 User-defined delay between chimes (also covers tcely's ask in #2)
- [x] #3 Option to use only one type of tone (short *or* long)
- [x] #4 Optional half-hour chime alongside the hourly chime
- [x] #7 Option to disable the "next chime at" notification
- [x] #8 Chime once with a single sound (feature part; the "default still plays" part is the #2 bug, now fixed)
- [x] #13 Chime during Do Not Disturb, via an "Ignore Do Not Disturb" toggle. Android's own per-app override is honoured too, so anyone who already allowed HRLY through Do Not Disturb needs no in-app setting at all.

---

## Nice-To-Haves
- [ ] Create a companion watch face for Wear OS
- [ ] Localize app for other languages
