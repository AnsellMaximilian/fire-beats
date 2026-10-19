const api = window.firelabDesktop;

const elements = Object.fromEntries(
  [
    "openButton", "exportButton", "analyzeButton", "fileLabel", "channelMode", "minBpm",
    "maxBpm", "delta", "deltaValue", "currentBpm", "currentCount", "adaptiveCount",
    "onsetCount", "audioDetails", "showCurrent", "showAdaptive", "showOnsets", "showManual",
    "addMarkerButton", "clearMarkersButton", "markerCount", "statusBanner", "statusText",
    "playButton", "timeLabel", "scrubber", "timelineCanvas", "zoom", "zoomValue", "pan",
    "followPlayhead", "chartSource", "resetGameButton", "gameCanvas", "hitCount", "missCount",
    "lastOffset", "meanOffset", "audio",
  ].map((id) => [id, document.getElementById(id)]),
);

const laneKeys = ["Q", "P", "A", "L"];
const laneColors = ["#ef5b66", "#4b91ff", "#ef5b66", "#4b91ff"];
const noteTravelSeconds = 1.6;
const hitWindowSeconds = 0.18;
const missWindowSeconds = 0.32;

let selectedFile = null;
let analysis = null;
let audioObjectUrl = null;
let manualMarkers = [];
let gameState = createGameState();
let lastFrameTime = performance.now();
let lastSongTime = 0;

function createGameState() {
  return {
    hits: new Set(),
    misses: new Set(),
    offsets: [],
    missCursor: 0,
    flashes: [],
  };
}

function setStatus(message, mode = "ready") {
  elements.statusText.textContent = message;
  elements.statusBanner.classList.toggle("busy", mode === "busy");
  elements.statusBanner.classList.toggle("error", mode === "error");
}

function formatTime(seconds) {
  const safe = Number.isFinite(seconds) ? Math.max(0, seconds) : 0;
  const minutes = Math.floor(safe / 60);
  const remaining = safe - minutes * 60;
  return `${minutes}:${remaining.toFixed(3).padStart(6, "0")}`;
}

function arrayFrom(value) {
  return value ? Array.from(value) : [];
}

function normalizeAnalysis(result) {
  return {
    sampleRate: result.sampleRate,
    channelCount: result.channelCount,
    channelMode: result.channelMode,
    duration: result.duration,
    waveform: arrayFrom(result.waveform),
    current: {
      bpm: result.current.bpm,
      confidence: result.current.confidence,
      beats: arrayFrom(result.current.beats),
      onsets: arrayFrom(result.current.onsets),
    },
    adaptive: {
      bpm: result.adaptive?.bpm || 0,
      confidence: result.adaptive?.confidence || 0,
      beats: arrayFrom(result.adaptive?.beats),
    },
  };
}

function analysisOptions() {
  return {
    minBpm: Number(elements.minBpm.value),
    maxBpm: Number(elements.maxBpm.value),
    delta: Number(elements.delta.value),
    channelMode: elements.channelMode.value,
  };
}

function mimeFor(name) {
  const extension = name.toLowerCase().split(".").pop();
  return {
    mp3: "audio/mpeg",
    wav: "audio/wav",
    m4a: "audio/mp4",
    aac: "audio/aac",
    flac: "audio/flac",
    ogg: "audio/ogg",
    webm: "audio/webm",
  }[extension] || "application/octet-stream";
}

function setControlsEnabled(enabled) {
  for (const element of [
    elements.exportButton,
    elements.analyzeButton,
    elements.playButton,
    elements.scrubber,
    elements.zoom,
    elements.pan,
    elements.chartSource,
    elements.resetGameButton,
    elements.addMarkerButton,
  ]) {
    element.disabled = !enabled;
  }
  elements.clearMarkersButton.disabled = !enabled || manualMarkers.length === 0;
}

function refreshMetrics() {
  if (!analysis) return;
  elements.currentBpm.textContent = `${analysis.current.bpm.toFixed(1)} BPM`;
  elements.currentCount.textContent = analysis.current.beats.length.toLocaleString();
  elements.adaptiveCount.textContent = `${analysis.adaptive.beats.length.toLocaleString()} · ${analysis.adaptive.bpm.toFixed(1)} BPM`;
  elements.onsetCount.textContent = analysis.current.onsets.length.toLocaleString();
  const channelDescription = analysis.channelCount === 1 ? "mono" : `${analysis.channelCount} ch`;
  elements.audioDetails.textContent = `${(analysis.sampleRate / 1000).toFixed(1)} kHz · ${channelDescription}`;
  elements.fileLabel.textContent = `${selectedFile.name} · ${(selectedFile.size / 1024 / 1024).toFixed(1)} MB`;
  elements.markerCount.textContent = `${manualMarkers.length} marker${manualMarkers.length === 1 ? "" : "s"}`;
  elements.clearMarkersButton.disabled = manualMarkers.length === 0;
}

async function openAudio() {
  try {
    const file = await api.openAudio();
    if (!file) return;
    selectedFile = file;
    manualMarkers = [];
    setControlsEnabled(false);
    elements.openButton.disabled = true;
    elements.fileLabel.textContent = file.name;
    setStatus(`Reading and analyzing ${file.name}…`, "busy");
    const [bytes, result] = await Promise.all([
      api.readAudio(file.id),
      api.analyzeAudio(file.id, analysisOptions()),
    ]);
    if (audioObjectUrl) URL.revokeObjectURL(audioObjectUrl);
    audioObjectUrl = URL.createObjectURL(new Blob([bytes], { type: mimeFor(file.name) }));
    elements.audio.src = audioObjectUrl;
    elements.audio.load();
    analysis = normalizeAnalysis(result);
    resetViewport();
    resetGame();
    refreshMetrics();
    setControlsEnabled(true);
    setStatus(
      `Ready: ${analysis.current.beats.length} uniform beats, ${analysis.adaptive.beats.length} adaptive beats, and ${analysis.current.onsets.length} onsets.`,
    );
  } catch (error) {
    setStatus(error.message || "Could not open that audio file", "error");
  } finally {
    elements.openButton.disabled = false;
  }
}

async function reanalyze() {
  if (!selectedFile) return;
  try {
    setControlsEnabled(false);
    setStatus(`Reanalyzing ${selectedFile.name}…`, "busy");
    analysis = normalizeAnalysis(await api.analyzeAudio(selectedFile.id, analysisOptions()));
    resetViewport();
    resetGame();
    refreshMetrics();
    setControlsEnabled(true);
    setStatus(
      `Updated: ${analysis.current.beats.length} uniform beats, ${analysis.adaptive.beats.length} adaptive beats, and ${analysis.current.onsets.length} onsets.`,
    );
  } catch (error) {
    setControlsEnabled(Boolean(analysis));
    setStatus(error.message || "Reanalysis failed", "error");
  }
}

async function exportAnalysis() {
  if (!analysis || !selectedFile) return;
  try {
    const payload = {
      format: "firelab-audio-analysis-v1",
      createdAt: new Date().toISOString(),
      file: { name: selectedFile.name, size: selectedFile.size },
      settings: analysisOptions(),
      analysis: {
        sampleRate: analysis.sampleRate,
        channelCount: analysis.channelCount,
        channelMode: analysis.channelMode,
        duration: analysis.duration,
        current: analysis.current,
        adaptive: analysis.adaptive,
      },
      manualMarkers,
    };
    const outputPath = await api.exportAnalysis(selectedFile.name, payload);
    if (outputPath) setStatus(`Exported analysis to ${outputPath}`);
  } catch (error) {
    setStatus(error.message || "Could not export analysis", "error");
  }
}

function resetViewport() {
  elements.zoom.value = "1";
  elements.pan.value = "0";
  elements.zoomValue.textContent = "1×";
}

function viewRange() {
  const duration = analysis?.duration || 1;
  const zoom = Number(elements.zoom.value) || 1;
  const length = duration / zoom;
  const maximumStart = Math.max(0, duration - length);
  const start = maximumStart * (Number(elements.pan.value) / 1000);
  return { start, end: start + length, length, maximumStart };
}

function setViewStart(start) {
  const range = viewRange();
  const fraction = range.maximumStart > 0 ? Math.max(0, Math.min(1, start / range.maximumStart)) : 0;
  elements.pan.value = String(Math.round(fraction * 1000));
}

function resetGame() {
  gameState = createGameState();
  const events = chartEvents();
  const threshold = elements.audio.currentTime - missWindowSeconds;
  const firstUpcomingEvent = events.findIndex((event) => event.time >= threshold);
  gameState.missCursor = firstUpcomingEvent === -1 ? events.length : firstUpcomingEvent;
  lastSongTime = elements.audio.currentTime;
  refreshScore();
}

function chartEvents() {
  if (!analysis) return [];
  let times;
  switch (elements.chartSource.value) {
    case "adaptive": times = analysis.adaptive.beats; break;
    case "onsets": times = analysis.current.onsets; break;
    case "manual": times = manualMarkers.map((marker) => marker.time); break;
    default: times = analysis.current.beats;
  }
  return times.map((time, index) => ({ time, lane: index % 4, index }));
}

function addManualMarker() {
  if (!analysis) return;
  const time = Math.max(0, Math.min(analysis.duration, elements.audio.currentTime));
  manualMarkers.push({ time: Math.round(time * 1000) / 1000, label: "manual" });
  manualMarkers.sort((left, right) => left.time - right.time);
  refreshMetrics();
  if (elements.chartSource.value === "manual") resetGame();
  setStatus(`Added manual marker at ${formatTime(time)}. Replay with “Manual markers” to test it.`);
}

function clearMarkers() {
  manualMarkers = [];
  refreshMetrics();
  if (elements.chartSource.value === "manual") resetGame();
  setStatus("Manual markers cleared.");
}

function refreshScore() {
  elements.hitCount.textContent = String(gameState.hits.size);
  elements.missCount.textContent = String(gameState.misses.size);
  const last = gameState.offsets.at(-1);
  elements.lastOffset.textContent = Number.isFinite(last) ? `${last > 0 ? "+" : ""}${Math.round(last)} ms` : "—";
  const mean = gameState.offsets.length
    ? gameState.offsets.reduce((sum, value) => sum + Math.abs(value), 0) / gameState.offsets.length
    : null;
  elements.meanOffset.textContent = mean === null ? "—" : `${Math.round(mean)} ms`;
}

function hitLane(lane) {
  if (!analysis) return;
  const time = elements.audio.currentTime;
  let candidate = null;
  for (const event of chartEvents()) {
    if (event.lane !== lane || gameState.hits.has(event.index) || gameState.misses.has(event.index)) continue;
    const distance = Math.abs(time - event.time);
    if (distance <= hitWindowSeconds && (!candidate || distance < candidate.distance)) {
      candidate = { event, distance };
    }
  }
  if (!candidate) return;
  gameState.hits.add(candidate.event.index);
  gameState.offsets.push((time - candidate.event.time) * 1000);
  gameState.flashes.push({ lane, until: performance.now() + 260 });
  refreshScore();
}

function updateMisses(songTime) {
  const events = chartEvents();
  if (songTime + 0.08 < lastSongTime) {
    resetGame();
    return;
  }
  while (
    gameState.missCursor < events.length &&
    events[gameState.missCursor].time < songTime - missWindowSeconds
  ) {
    const event = events[gameState.missCursor];
    if (!gameState.hits.has(event.index)) gameState.misses.add(event.index);
    gameState.missCursor += 1;
  }
  lastSongTime = songTime;
  refreshScore();
}

function prepareCanvas(canvas) {
  const rect = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  const pixelWidth = Math.max(1, Math.round(rect.width * ratio));
  const pixelHeight = Math.max(1, Math.round(rect.height * ratio));
  if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
    canvas.width = pixelWidth;
    canvas.height = pixelHeight;
  }
  const context = canvas.getContext("2d");
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  return { context, width: rect.width, height: rect.height };
}

function niceTickStep(length) {
  const choices = [0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120];
  return choices.find((choice) => length / choice <= 10) || 300;
}

function drawTimeline() {
  const { context, width, height } = prepareCanvas(elements.timelineCanvas);
  context.clearRect(0, 0, width, height);
  context.fillStyle = "#080d16";
  context.fillRect(0, 0, width, height);
  if (!analysis) {
    context.fillStyle = "#6e7c91";
    context.font = "13px system-ui";
    context.textAlign = "center";
    context.fillText("Waveform and event overlays appear here", width / 2, height / 2);
    return;
  }

  let range = viewRange();
  const songTime = elements.audio.currentTime;
  if (
    elements.followPlayhead.checked &&
    !elements.audio.paused &&
    (songTime < range.start || songTime > range.end - range.length * 0.08)
  ) {
    setViewStart(songTime - range.length * 0.12);
    range = viewRange();
  }
  const left = 56;
  const right = width - 12;
  const plotWidth = Math.max(1, right - left);
  const xForTime = (time) => left + ((time - range.start) / range.length) * plotWidth;

  context.strokeStyle = "#1c293c";
  context.fillStyle = "#738198";
  context.font = "10px ui-monospace, monospace";
  context.textAlign = "center";
  const tick = niceTickStep(range.length);
  const firstTick = Math.ceil(range.start / tick) * tick;
  for (let time = firstTick; time <= range.end; time += tick) {
    const x = xForTime(time);
    context.beginPath();
    context.moveTo(x, 10);
    context.lineTo(x, height - 15);
    context.stroke();
    context.fillText(formatTime(time).replace(/\.\d{3}$/, ""), x, height - 4);
  }

  const waveformTop = 17;
  const waveformBottom = Math.max(100, height - 104);
  const waveformCenter = (waveformTop + waveformBottom) / 2;
  const waveformAmplitude = (waveformBottom - waveformTop) * 0.48;
  context.strokeStyle = "#47637f";
  context.globalAlpha = 0.72;
  context.beginPath();
  const buckets = analysis.waveform.length / 2;
  for (let bucket = 0; bucket < buckets; bucket += 1) {
    const time = (bucket / Math.max(1, buckets - 1)) * analysis.duration;
    if (time < range.start || time > range.end) continue;
    const x = xForTime(time);
    const minimum = analysis.waveform[bucket * 2];
    const maximum = analysis.waveform[bucket * 2 + 1];
    context.moveTo(x, waveformCenter - maximum * waveformAmplitude);
    context.lineTo(x, waveformCenter - minimum * waveformAmplitude);
  }
  context.stroke();
  context.globalAlpha = 1;

  const rows = [height - 80, height - 53, height - 27];
  context.textAlign = "right";
  context.font = "10px system-ui";
  context.fillStyle = "#77869c";
  context.fillText("GRID", left - 8, rows[0] + 3);
  context.fillText("ADAPT", left - 8, rows[1] + 3);
  context.fillText("ONSET", left - 8, rows[2] + 3);

  const drawEvents = (times, row, color, heightPixels, opacity = 1) => {
    context.strokeStyle = color;
    context.globalAlpha = opacity;
    context.lineWidth = 1.4;
    context.beginPath();
    for (const time of times) {
      if (time < range.start || time > range.end) continue;
      const x = xForTime(time);
      context.moveTo(x, row - heightPixels / 2);
      context.lineTo(x, row + heightPixels / 2);
    }
    context.stroke();
    context.globalAlpha = 1;
  };
  if (elements.showCurrent.checked) drawEvents(analysis.current.beats, rows[0], "#48d9ff", 18, 0.95);
  if (elements.showAdaptive.checked) drawEvents(analysis.adaptive.beats, rows[1], "#ff6eb6", 18, 0.95);
  if (elements.showOnsets.checked) drawEvents(analysis.current.onsets, rows[2], "#ffb84d", 13, 0.56);
  if (elements.showManual.checked) drawEvents(manualMarkers.map((marker) => marker.time), waveformCenter, "#a78bfa", waveformBottom - waveformTop, 0.85);

  if (songTime >= range.start && songTime <= range.end) {
    const x = xForTime(songTime);
    context.strokeStyle = "#ffffff";
    context.lineWidth = 1.5;
    context.beginPath();
    context.moveTo(x, 7);
    context.lineTo(x, height - 15);
    context.stroke();
  }
}

function drawCircle(context, x, y, radius, fill, stroke = null, width = 1) {
  context.beginPath();
  context.arc(x, y, radius, 0, Math.PI * 2);
  context.fillStyle = fill;
  context.fill();
  if (stroke) {
    context.strokeStyle = stroke;
    context.lineWidth = width;
    context.stroke();
  }
}

function drawGame(now) {
  const { context, width, height } = prepareCanvas(elements.gameCanvas);
  context.clearRect(0, 0, width, height);
  const gradient = context.createRadialGradient(width / 2, height / 2, 5, width / 2, height / 2, width * 0.7);
  gradient.addColorStop(0, "#122039");
  gradient.addColorStop(1, "#070a10");
  context.fillStyle = gradient;
  context.fillRect(0, 0, width, height);

  const center = { x: width / 2, y: height / 2 };
  const targets = [
    { x: width * 0.15, y: height * 0.2 },
    { x: width * 0.85, y: height * 0.2 },
    { x: width * 0.15, y: height * 0.8 },
    { x: width * 0.85, y: height * 0.8 },
  ];
  const targetRadius = Math.min(width, height) * 0.095;
  const songTime = elements.audio.currentTime;
  const events = chartEvents();
  const active = events.filter((event) =>
    !gameState.hits.has(event.index) &&
    event.time - noteTravelSeconds <= songTime &&
    event.time + missWindowSeconds >= songTime,
  );
  gameState.flashes = gameState.flashes.filter((flash) => flash.until > now);

  for (let lane = 0; lane < targets.length; lane += 1) {
    const target = targets[lane];
    const highlighted = active.some((event) => event.lane === lane && Math.abs(songTime - event.time) <= hitWindowSeconds);
    const flashing = gameState.flashes.some((flash) => flash.lane === lane);
    context.strokeStyle = `${laneColors[lane]}33`;
    context.lineWidth = 3;
    context.beginPath();
    context.moveTo(center.x, center.y);
    context.lineTo(target.x, target.y);
    context.stroke();
    drawCircle(
      context,
      target.x,
      target.y,
      targetRadius * (flashing ? 1.18 : 1),
      `${laneColors[lane]}${highlighted ? "75" : "2d"}`,
      flashing || highlighted ? "#ffffff" : laneColors[lane],
      flashing ? 7 : 4,
    );
    context.fillStyle = "#ffffff";
    context.font = "800 15px system-ui";
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.fillText(laneKeys[lane], target.x, target.y);
  }

  drawCircle(context, center.x, center.y, 7, "#dbeafe");
  for (const event of active) {
    const target = targets[event.lane];
    const progress = (songTime - (event.time - noteTravelSeconds)) / noteTravelSeconds;
    const x = center.x + (target.x - center.x) * progress;
    const y = center.y + (target.y - center.y) * progress;
    const radius = Math.min(width, height) * 0.035;
    drawCircle(context, x, y, radius, laneColors[event.lane], "#ffffffdd", 3);
    context.fillStyle = "white";
    context.font = "800 11px system-ui";
    context.fillText(laneKeys[event.lane], x, y);
  }

  if (!analysis) {
    context.fillStyle = "#738198";
    context.font = "13px system-ui";
    context.fillText("Load a track to preview its bubbles", center.x, center.y + 34);
  } else if (events.length === 0) {
    context.fillStyle = "#738198";
    context.font = "13px system-ui";
    context.fillText("This event source is empty", center.x, center.y + 34);
  }
}

async function togglePlayback() {
  if (!analysis) return;
  if (elements.audio.paused) {
    try {
      await elements.audio.play();
    } catch (error) {
      setStatus(error.message || "Playback could not start", "error");
    }
  } else {
    elements.audio.pause();
  }
}

function frame(now) {
  const elapsed = Math.min(0.05, (now - lastFrameTime) / 1000);
  lastFrameTime = now;
  if (analysis) {
    const duration = analysis.duration || elements.audio.duration || 1;
    const time = elements.audio.currentTime;
    elements.scrubber.value = String(Math.round((time / duration) * 1000));
    elements.timeLabel.textContent = `${formatTime(time)} / ${formatTime(duration)}`;
    elements.playButton.textContent = elements.audio.paused ? "▶" : "Ⅱ";
    if (!elements.audio.paused && elapsed >= 0) updateMisses(time);
  }
  drawTimeline();
  drawGame(now);
  requestAnimationFrame(frame);
}

elements.openButton.addEventListener("click", openAudio);
elements.analyzeButton.addEventListener("click", reanalyze);
elements.exportButton.addEventListener("click", exportAnalysis);
elements.playButton.addEventListener("click", togglePlayback);
elements.addMarkerButton.addEventListener("click", addManualMarker);
elements.clearMarkersButton.addEventListener("click", clearMarkers);
elements.resetGameButton.addEventListener("click", resetGame);
elements.chartSource.addEventListener("change", resetGame);
elements.delta.addEventListener("input", () => {
  elements.deltaValue.textContent = Number(elements.delta.value).toFixed(2);
});
elements.zoom.addEventListener("input", () => {
  elements.zoomValue.textContent = `${elements.zoom.value}×`;
});
elements.scrubber.addEventListener("input", () => {
  if (!analysis) return;
  elements.audio.currentTime = (Number(elements.scrubber.value) / 1000) * analysis.duration;
  resetGame();
});
elements.timelineCanvas.addEventListener("pointerdown", (event) => {
  if (!analysis) return;
  const rect = elements.timelineCanvas.getBoundingClientRect();
  const range = viewRange();
  const left = 56;
  const right = rect.width - 12;
  const fraction = Math.max(0, Math.min(1, (event.clientX - rect.left - left) / (right - left)));
  elements.audio.currentTime = range.start + fraction * range.length;
  resetGame();
});
elements.audio.addEventListener("ended", () => {
  elements.playButton.textContent = "▶";
});
elements.audio.addEventListener("error", () => {
  setStatus("Chromium could not play this format. MP3 and WAV are the safest choices.", "error");
});

for (const id of ["showCurrent", "showAdaptive", "showOnsets", "showManual"]) {
  elements[id].addEventListener("change", drawTimeline);
}

document.addEventListener("keydown", (event) => {
  const tag = event.target?.tagName;
  if (tag === "INPUT" || tag === "SELECT" || tag === "BUTTON") return;
  if (event.code === "Space") {
    event.preventDefault();
    void togglePlayback();
    return;
  }
  if (event.code === "KeyM") {
    addManualMarker();
    return;
  }
  const lane = ["KeyQ", "KeyP", "KeyA", "KeyL"].indexOf(event.code);
  if (lane >= 0 && !event.repeat) hitLane(lane);
});

window.addEventListener("beforeunload", () => {
  if (audioObjectUrl) URL.revokeObjectURL(audioObjectUrl);
});

setControlsEnabled(false);
requestAnimationFrame(frame);
