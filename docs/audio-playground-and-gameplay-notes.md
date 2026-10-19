# Audio playground and gameplay follow-ups

## Desktop playground

The rapid-prototyping app lives in `desktop-playground/`. Its current-grid result comes from the same module as the server. It also exposes adaptive beat tracking, raw onset markers, manual semantic markers, timeline zoom/scrubbing, JSON export, and a keyboard bubble preview.

The playground should remain an analysis and chart-authoring tool. The Fire TV should continue receiving a compact, precomputed chart rather than running expensive music or lyric models itself.

## Gameplay weaknesses to address later

- Scoring currently uses wrist occupancy rather than detecting a punch, target-entry edge, or sufficient hand velocity. A held hand can collect repeated notes.
- Pose frames arrive at roughly 20 FPS and are then interpolated for 60 ms. Capture, inference, network, rendering, and audio-output latency are not measured.
- The phone's pose timestamp is not mapped to the TV clock, so scoring compares delayed pose state with the current playback position.
- `MediaPlayer.currentPosition` prevents long-term game-clock drift, but it does not represent calibrated audible-output latency.
- The ±180 ms hit window masks latency but limits meaningful accuracy grades; the +320 ms miss window leaves an already-unhittable note visible.
- Spatial collision is deliberately generous and does not use per-user motion speed, target-entry hysteresis, or per-lane debounce.
- Lanes are assigned by `beatIndex % 4`, without musical semantics or movement/playability constraints.

Future gameplay work should use timestamped pose samples in a short ring buffer, phone/TV clock synchronization, device and player offset calibration, target-entry plus velocity detection, and accuracy grades based on compensated event offsets.

## Lyric-aware analysis direction

Treat rhythm events and semantic events as separate analysis products:

- rhythm: beat, downbeat, onset band, strength, and confidence;
- lyrics: normalized word, start/end timestamps, confidence, and optional phoneme timing;
- chart: target time, lane, hand, event kind, source confidence, and difficulty metadata.

For a target such as `low`, isolate or enhance the vocal stem, transcribe or forced-align the lyrics, select every matching word token, refine its perceptual attack, then pass those candidates through chart playability rules. Manual markers in the desktop playground provide an immediate way to prototype the desired timing before that automated pipeline exists.
