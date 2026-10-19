import { randomUUID } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath, pathToFileURL } from "node:url";
import { Worker } from "node:worker_threads";
import { app, BrowserWindow, dialog, ipcMain } from "electron";

const currentDirectory = path.dirname(fileURLToPath(import.meta.url));
const rendererPath = path.join(currentDirectory, "renderer", "index.html");
const rendererUrl = pathToFileURL(rendererPath).href;
const workerPath = path.join(currentDirectory, "analysis-worker.mjs");
const maxAudioBytes = 50 * 1024 * 1024;
const selectedFiles = new Map();

async function registerSelectedFile(filePath) {
  const stats = await fs.stat(filePath);
  if (!stats.isFile()) throw new Error("The selected item is not a file");
  if (stats.size > maxAudioBytes) throw new Error("Choose an audio file no larger than 50 MB");
  const id = randomUUID();
  selectedFiles.clear();
  selectedFiles.set(id, {
    path: filePath,
    name: path.basename(filePath),
    size: stats.size,
  });
  return { id, name: path.basename(filePath), size: stats.size };
}

function requireTrustedRenderer(event) {
  if (event.senderFrame?.url !== rendererUrl) {
    throw new Error("Rejected a request from an unexpected renderer");
  }
}

function selectedFile(id) {
  const file = selectedFiles.get(String(id || ""));
  if (!file) throw new Error("Choose an audio file again");
  return file;
}

function normalizedOptions(value = {}) {
  const minBpm = Math.round(Number(value.minBpm));
  const maxBpm = Math.round(Number(value.maxBpm));
  const delta = Number(value.delta);
  if (!Number.isFinite(minBpm) || minBpm < 30 || minBpm > 240) {
    throw new Error("Minimum BPM must be between 30 and 240");
  }
  if (!Number.isFinite(maxBpm) || maxBpm < 40 || maxBpm > 300 || maxBpm <= minBpm) {
    throw new Error("Maximum BPM must be greater than minimum BPM and at most 300");
  }
  if (!Number.isFinite(delta) || delta < 0.5 || delta > 4) {
    throw new Error("Onset threshold must be between 0.5 and 4");
  }
  return {
    minBpm,
    maxBpm,
    delta,
    channelMode: value.channelMode === "mix" ? "mix" : "first",
    includeAdaptive: true,
    includeWaveform: true,
    waveformBuckets: 1800,
  };
}

function runAnalysis(filePath, options) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(workerPath, {
      workerData: { filePath, options },
    });
    let settled = false;
    worker.once("message", (message) => {
      settled = true;
      if (message.ok) resolve(message.result);
      else reject(new Error(message.error || "Audio analysis failed"));
    });
    worker.once("error", (error) => {
      settled = true;
      reject(error);
    });
    worker.once("exit", (code) => {
      if (!settled && code !== 0) reject(new Error(`Audio analysis worker exited with code ${code}`));
    });
  });
}

function registerIpc() {
  ipcMain.handle("audio:open", async (event) => {
    requireTrustedRenderer(event);
    if (process.env.FIRELAB_SMOKE_TRACK) {
      return registerSelectedFile(path.resolve(process.env.FIRELAB_SMOKE_TRACK));
    }
    const owner = BrowserWindow.fromWebContents(event.sender);
    const result = await dialog.showOpenDialog(owner, {
      title: "Choose a track to analyze",
      properties: ["openFile"],
      filters: [
        { name: "Audio files", extensions: ["mp3", "wav", "m4a", "aac", "flac", "ogg", "webm"] },
        { name: "All files", extensions: ["*"] },
      ],
    });
    if (result.canceled || !result.filePaths[0]) return null;
    return registerSelectedFile(result.filePaths[0]);
  });

  ipcMain.handle("audio:read", async (event, id) => {
    requireTrustedRenderer(event);
    const data = await fs.readFile(selectedFile(id).path);
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  });

  ipcMain.handle("audio:analyze", async (event, id, options) => {
    requireTrustedRenderer(event);
    return runAnalysis(selectedFile(id).path, normalizedOptions(options));
  });

  ipcMain.handle("analysis:export", async (event, suggestedName, payload) => {
    requireTrustedRenderer(event);
    const owner = BrowserWindow.fromWebContents(event.sender);
    const safeName = String(suggestedName || "firelab-analysis")
      .replace(/\.[^.]+$/, "")
      .replace(/[^a-z0-9_-]+/gi, "-")
      .replace(/^-+|-+$/g, "") || "firelab-analysis";
    const serialized = JSON.stringify(payload, null, 2);
    if (serialized.length > 20_000_000) throw new Error("The analysis export is unexpectedly large");
    const result = await dialog.showSaveDialog(owner, {
      title: "Export FireLab analysis",
      defaultPath: `${safeName}-analysis.json`,
      filters: [{ name: "JSON", extensions: ["json"] }],
    });
    if (result.canceled || !result.filePath) return null;
    await fs.writeFile(result.filePath, serialized, "utf8");
    return result.filePath;
  });
}

function createWindow() {
  const window = new BrowserWindow({
    width: 1400,
    height: 920,
    minWidth: 1050,
    minHeight: 720,
    backgroundColor: "#070a12",
    title: "FireLab Audio Playground",
    webPreferences: {
      preload: path.join(currentDirectory, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  window.webContents.on("will-navigate", (event) => event.preventDefault());
  if (process.env.FIRELAB_CAPTURE_PATH && process.env.FIRELAB_SMOKE_TRACK) {
    window.webContents.once("did-finish-load", async () => {
      try {
        await window.webContents.executeJavaScript(
          "document.getElementById('openButton').click()",
        );
        let state;
        for (let attempt = 0; attempt < 150; attempt += 1) {
          await delay(100);
          state = await window.webContents.executeJavaScript(`({
            busy: document.getElementById('statusBanner').classList.contains('busy'),
            error: document.getElementById('statusBanner').classList.contains('error'),
            status: document.getElementById('statusText').textContent,
            count: document.getElementById('currentCount').textContent
          })`);
          if (!state.busy && (state.error || state.count !== "—")) break;
        }
        if (!state || state.busy || state.error || state.count === "—") {
          throw new Error(state?.status || "The smoke analysis did not finish");
        }
        for (let attempt = 0; attempt < 50; attempt += 1) {
          const audioReady = await window.webContents.executeJavaScript(
            "document.getElementById('audio').readyState >= 1",
          );
          if (audioReady) break;
          await delay(100);
        }
        const interaction = await window.webContents.executeJavaScript(`(() => {
          const audio = document.getElementById('audio');
          audio.currentTime = 0.023;
          document.dispatchEvent(new KeyboardEvent('keydown', { code: 'KeyQ', bubbles: true }));
          document.getElementById('addMarkerButton').click();
          return {
            hits: document.getElementById('hitCount').textContent,
            markers: document.getElementById('markerCount').textContent
          };
        })()`);
        if (interaction.hits !== "1" || interaction.markers !== "1 marker") {
          throw new Error(`Smoke interaction failed: ${JSON.stringify(interaction)}`);
        }
        const image = await window.webContents.capturePage();
        await fs.writeFile(path.resolve(process.env.FIRELAB_CAPTURE_PATH), image.toPNG());
        console.log(`[FireLab] Smoke capture complete: ${state.status} Hit and marker controls passed.`);
        app.exit(0);
      } catch (error) {
        console.error("[FireLab] Smoke capture failed", error);
        app.exit(1);
      }
    });
  }
  void window.loadFile(rendererPath);
}

app.whenReady().then(() => {
  registerIpc();
  createWindow();
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
