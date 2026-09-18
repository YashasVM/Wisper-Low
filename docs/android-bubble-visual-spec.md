# Android bubble visual specification

This is the compact visual contract for the floating dictation bubble. It keeps
the overlay legible over another app while making each state identifiable from
shape, text, and motion as well as color.

## Tokens

| Token | Value | Use |
| --- | --- | --- |
| Minimum action target | 48 dp | Every review action, including cancel and insert |
| Idle bubble | 56 × 56 dp | Branded microphone mark; tap starts dictation |
| Listening/processing pill | 180 × 64 dp | Animated waveform or progress dots plus a state label |
| Review panel | 320 × 64 dp | Cancel, editable text, insert |
| Bubble corner | 50% of the short side | Circle at idle; pill/panel after expansion |
| Action glyph | 24 dp | Resource-backed check and close symbols |
| Brand mark | 32 dp | Resource-backed mark shared by launcher and idle bubble |
| Bubble surface | `#211F29` | Idle and review |
| Listening surface | `#4936A0` | Active capture |
| Processing surface | `#353039` | Recognition/preparation |
| Bubble alpha | 0.95 | Preserve separation from the app below |

The overlay uses the existing Material 3 type scale. State labels use
`labelLarge`, and review text uses `bodyLarge` so Android font scaling remains
usable. Labels can ellipsize to two lines; review text can use three lines.

## Icon inventory

| Resource | Role | Rendering | Accessible label |
| --- | --- | --- | --- |
| `ic_brand_mark` | Launcher and idle bubble identity | Material `Icon`, 32 dp, no tint | Parent bubble: “Start dictation” |
| `ic_check` | Confirm review and insert | Material `Icon`, white, 24 dp | Parent action: “Insert dictation” |
| `ic_close` | Cancel review | Material `Icon`, white, 24 dp | Parent action: “Cancel dictation” |
| Animated waveform | Listening activity | Canvas bars, 56 × 48 dp | Parent bubble: “Dictation listening” |
| Animated dots | Processing activity | Canvas dots | Parent bubble: “Preparing dictation” |

Action icons are vector resources rather than hand-drawn canvas strokes. The
waveform and dots remain canvas primitives because they animate continuously;
their state labels and distinct geometry keep them understandable when motion
is disabled.

## Interaction and motion contract

- Preserve the existing tap, long-press cancel, drag, focusable review field,
  review edit callback, confirm callback, and target insertion behavior.
- Keep the compact bubble during capture. Expand only when the mode changes so
  the review editor has room without covering more of the target app than needed.
- Android's animator-duration setting controls expansion and looping activity
  motion. With animators disabled, expansion snaps and waveform/dots remain in
  a stable state while labels continue to communicate progress.
- Color is supplemental. Every non-idle state has a visible text label and a
  different icon treatment; review actions have both an icon and a spoken
  content description.

## Device capture checklist

Capture the same four states at default, large, and extra-large font scales in
light and dark system themes. Check that the idle mark is centered, waveform
bars do not clip, review text remains editable, both actions retain 48 dp hit
targets, and the panel does not cover the focused field unexpectedly. Repeat
with Android “Remove animations” enabled and verify the labels still make the
state clear.
