# 0001 — One-page repo tour beats a long doc

- Date: 2026-09-22
- Context: user asked "explain everything how everything is working in this repo"
  as a minimalist HTML page hosted at `a.yash0.in/whisperlow`.
- Insight: the repo's load-bearing structure is shortcut → managers (audio /
  model / transcription / history) → command-event bridge → paste. Teaching that
  single journey first makes every other detail (VAD, engines, overlay, CLI)
  land somewhere, instead of floating as isolated facts.
- Decision: lesson 001 traces one dictation end-to-end with file:line citations,
  plus a 3-question quiz for retrieval practice. Deeper lessons (audio, models,
  frontend) can branch later.
- Next: verify `https://a.yash0.in/whisperlow` serves the lesson and
  `/openstream/` is untouched.
