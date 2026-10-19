# FireLab desktop playground

This local Electron tool makes the FireLab audio pipeline inspectable without pairing a phone or Fire TV. It imports the same `analyzeEncodedAudio` module used by the signaling server, so the **Current grid** overlay is the result the TV would receive.

## Run it

Install both sets of dependencies once:

```powershell
cd server
npm install
cd ..\desktop-playground
npm install
```

Then launch the playground:

```powershell
cd desktop-playground
npm start
```

## What you can compare

- **Current grid** — FireLab's uniform, phase-aligned BPM grid.
- **Adaptive beats** — the dependency's dynamic-programming beat tracker.
- **Raw onsets** — every spectral-flux attack found before the current grid discards them.
- **Manual markers** — press `M` during playback to mark a lyric or sound, rewind, and select Manual markers as the bubble source.

Click the timeline to seek and use its zoom, position, and volume controls for close inspection. In the bubble preview, `Q`, `P`, `A`, and `L` trigger the four lanes; Space toggles playback. JSON export preserves the analyzer settings, playback volume, event arrays, and manual markers.

The renderer has no Node.js access. File selection, reading, analysis, and export go through a context-isolated preload API.
