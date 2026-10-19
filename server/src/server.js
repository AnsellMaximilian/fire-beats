import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes } from "node:crypto";
import QRCode from "qrcode";
import { WebSocketServer, WebSocket } from "ws";
import { analyzeEncodedAudio } from "./audio-analysis.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDirectory = path.resolve(__dirname, "../public");
const tracksDirectory = path.resolve(__dirname, "../data/tracks");
const httpPort = Number(process.env.PORT || 3050);
const httpsPort = Number(process.env.HTTPS_PORT || 3443);
const maxUploadBytes = 50 * 1024 * 1024;

fs.mkdirSync(tracksDirectory, { recursive: true });

const tracks = new Map();
for (const entry of fs.readdirSync(tracksDirectory, { withFileTypes: true })) {
  if (!entry.isDirectory()) continue;
  try {
    const metadata = JSON.parse(
      fs.readFileSync(path.join(tracksDirectory, entry.name, "track.json"), "utf8"),
    );
    tracks.set(metadata.id, metadata);
  } catch (error) {
    console.warn(`Skipping unreadable track metadata for ${entry.name}`, error.message);
  }
}

const readPage = (name) =>
  fs.readFileSync(path.join(publicDirectory, name), "utf8");

function localAddress() {
  for (const interfaces of Object.values(os.networkInterfaces())) {
    for (const address of interfaces || []) {
      if (address.family === "IPv4" && !address.internal)
        return address.address;
    }
  }
  return "localhost";
}

function originForPhone(request) {
  if (process.env.PUBLIC_URL) return process.env.PUBLIC_URL.replace(/\/$/, "");
  const protocol = request.socket.encrypted ? "https" : "http";
  const port = request.socket.encrypted ? httpsPort : httpPort;
  return `${protocol}://${localAddress()}:${port}`;
}

function createSession(request) {
  const room = randomBytes(4).toString("hex");
  const phoneUrl = `${originForPhone(request)}/phone?room=${encodeURIComponent(room)}`;
  return { room, phoneUrl };
}

function send(response, status, type, body) {
  response.writeHead(status, {
    "content-type": `${type}; charset=utf-8`,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(body);
}

function trackSummary(track, includeBeats = false) {
  const summary = {
    id: track.id,
    name: track.name,
    size: track.size,
    status: track.status,
    progress: track.progress,
    bpm: track.bpm ?? null,
    duration: track.duration ?? null,
    beatCount: track.beats?.length || 0,
    error: track.error || null,
    createdAt: track.createdAt,
  };
  if (includeBeats) summary.beats = track.beats || [];
  return summary;
}

async function persistTrack(track) {
  const directory = path.join(tracksDirectory, track.id);
  await fs.promises.mkdir(directory, { recursive: true });
  await fs.promises.writeFile(
    path.join(directory, "track.json"),
    JSON.stringify(track, null, 2),
  );
}

function publishTrackProgress(room, track, message) {
  if (!room) return;
  broadcastToRoom(room, {
    type: "track-progress",
    message,
    track: trackSummary(track),
  });
}

async function analyzeTrack(track, room) {
  try {
    track.status = "analyzing";
    track.progress = 60;
    await persistTrack(track);
    publishTrackProgress(room, track, "Decoding MP3…");

    const encodedAudio = await fs.promises.readFile(
      path.join(tracksDirectory, track.id, "audio.mp3"),
    );
    track.progress = 80;
    publishTrackProgress(room, track, "Detecting beats…");
    const decodedAnalysis = await analyzeEncodedAudio(encodedAudio, {
      minBpm: 60,
      maxBpm: 200,
    });
    const analysis = decodedAnalysis.current;

    track.bpm = Math.round(analysis.bpm * 10) / 10;
    track.beats = Array.from(analysis.beats, (beat) => Math.round(beat * 1000) / 1000);
    track.duration = Math.round(decodedAnalysis.duration * 100) / 100;
    track.status = "ready";
    track.progress = 100;
    track.error = null;
    await persistTrack(track);
    publishTrackProgress(room, track, `Ready — ${track.bpm} BPM, ${track.beats.length} beats`);
  } catch (error) {
    console.error(`Track analysis failed for ${track.id}`, error);
    track.status = "error";
    track.error = error.message || "Audio analysis failed";
    track.progress = 100;
    await persistTrack(track);
    publishTrackProgress(room, track, track.error);
  }
}

async function receiveTrackUpload(request, response, url) {
  const room = String(url.searchParams.get("room") || "").toLowerCase();
  const name = String(url.searchParams.get("name") || "Uploaded track").trim().slice(0, 160);
  const contentLength = Number(request.headers["content-length"] || 0);
  console.log(
    `[FireTV] Track upload request name=${JSON.stringify(name)} length=${contentLength || "unknown"}`,
  );
  if (contentLength > maxUploadBytes) {
    console.warn(`[FireTV] Track upload rejected: ${contentLength} bytes exceeds 50 MB`);
    send(response, 413, "application/json", JSON.stringify({ error: "MP3 must be 50 MB or smaller" }));
    return;
  }

  const id = randomBytes(8).toString("hex");
  const directory = path.join(tracksDirectory, id);
  const audioPath = path.join(directory, "audio.mp3");
  await fs.promises.mkdir(directory, { recursive: true });
  const track = {
    id,
    name: name || "Uploaded track",
    size: 0,
    status: "uploading",
    progress: 0,
    bpm: null,
    duration: null,
    beats: [],
    error: null,
    createdAt: new Date().toISOString(),
  };
  tracks.set(id, track);

  let receivedBytes = 0;
  let lastPublishedProgress = -1;
  const output = fs.createWriteStream(audioPath, { flags: "wx" });
  request.on("data", (chunk) => {
    receivedBytes += chunk.length;
    if (receivedBytes > maxUploadBytes) request.destroy(new Error("Upload exceeds 50 MB"));
    const progress = contentLength > 0
      ? Math.min(55, Math.round((receivedBytes / contentLength) * 55))
      : 25;
    if (progress >= lastPublishedProgress + 2) {
      lastPublishedProgress = progress;
      track.progress = progress;
      track.size = receivedBytes;
      publishTrackProgress(room, track, `Uploading ${progress}%`);
    }
  });

  try {
    await new Promise((resolve, reject) => {
      request.on("error", reject);
      request.on("aborted", () => reject(new Error("Upload was interrupted")));
      output.on("error", reject);
      output.on("finish", resolve);
      request.pipe(output);
    });
    track.size = receivedBytes;
    track.progress = 55;
    await persistTrack(track);
    console.log(`[FireTV] Track upload received id=${track.id} bytes=${receivedBytes}`);
    send(response, 202, "application/json", JSON.stringify(trackSummary(track)));
    void analyzeTrack(track, room);
  } catch (error) {
    console.error(`[FireTV] Track upload failed name=${JSON.stringify(name)}`, error);
    output.destroy();
    tracks.delete(id);
    await fs.promises.rm(directory, { recursive: true, force: true });
    if (!response.headersSent) {
      send(response, 400, "application/json", JSON.stringify({ error: error.message }));
    }
  }
}

async function requestHandler(request, response) {
  const base = `${request.socket.encrypted ? "https" : "http"}://${request.headers.host || "localhost"}`;
  const url = new URL(request.url || "/", base);

  if (url.pathname === "/health") {
    send(response, 200, "application/json", JSON.stringify({ ok: true }));
    return;
  }

  if (url.pathname === "/") {
    response.writeHead(302, { location: "/tv" });
    response.end();
    return;
  }

  if (url.pathname === "/api/session") {
    send(response, 200, "application/json", JSON.stringify(createSession(request)));
    return;
  }

  if (url.pathname === "/api/tracks" && request.method === "GET") {
    const result = [...tracks.values()]
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
      .map((track) => trackSummary(track));
    send(response, 200, "application/json", JSON.stringify(result));
    return;
  }

  if (url.pathname === "/api/tracks" && request.method === "POST") {
    await receiveTrackUpload(request, response, url);
    return;
  }

  const trackMatch = url.pathname.match(/^\/api\/tracks\/([a-f0-9]{16})$/);
  if (trackMatch && request.method === "GET") {
    const track = tracks.get(trackMatch[1]);
    if (!track) {
      send(response, 404, "application/json", JSON.stringify({ error: "Track not found" }));
      return;
    }
    send(response, 200, "application/json", JSON.stringify(trackSummary(track, true)));
    return;
  }

  const audioMatch = url.pathname.match(/^\/api\/tracks\/([a-f0-9]{16})\/audio$/);
  if (audioMatch && request.method === "GET") {
    const track = tracks.get(audioMatch[1]);
    const audioPath = path.join(tracksDirectory, audioMatch[1], "audio.mp3");
    if (!track || !fs.existsSync(audioPath)) {
      send(response, 404, "application/json", JSON.stringify({ error: "Track audio not found" }));
      return;
    }

    const fileSize = fs.statSync(audioPath).size;
    const range = request.headers.range;
    if (range) {
      const match = range.match(/^bytes=(\d*)-(\d*)$/);
      const start = match?.[1] ? Number(match[1]) : 0;
      const end = match?.[2] ? Math.min(Number(match[2]), fileSize - 1) : fileSize - 1;
      if (!match || start > end || start >= fileSize) {
        response.writeHead(416, { "content-range": `bytes */${fileSize}` });
        response.end();
        return;
      }
      response.writeHead(206, {
        "content-type": "audio/mpeg",
        "content-length": end - start + 1,
        "content-range": `bytes ${start}-${end}/${fileSize}`,
        "accept-ranges": "bytes",
        "cache-control": "no-store",
      });
      fs.createReadStream(audioPath, { start, end }).pipe(response);
      return;
    }

    response.writeHead(200, {
      "content-type": "audio/mpeg",
      "content-length": fileSize,
      "accept-ranges": "bytes",
      "cache-control": "no-store",
    });
    fs.createReadStream(audioPath).pipe(response);
    return;
  }

  if (url.pathname === "/tv") {
    const { room, phoneUrl } = createSession(request);
    const qrSvg = await QRCode.toString(phoneUrl, {
      type: "svg",
      margin: 1,
      color: { dark: "#111827", light: "#ffffff" },
    });
    const page = readPage("tv.html")
      .replace("__ROOM_JSON__", JSON.stringify(room))
      .replace("__PHONE_URL_JSON__", JSON.stringify(phoneUrl))
      .replace("__QR_SVG__", qrSvg);
    send(response, 200, "text/html", page);
    return;
  }

  if (url.pathname === "/phone") {
    send(response, 200, "text/html", readPage("phone.html"));
    return;
  }

  if (url.pathname === "/shared.css") {
    send(response, 200, "text/css", readPage("shared.css"));
    return;
  }

  send(response, 404, "text/plain", "Not found");
}

const clients = new Map();
const selectedTracksByRoom = new Map();
const avatarsByRoom = new Map();

function attachSignaling(server) {
  const sockets = new WebSocketServer({ server, path: "/signal" });

  sockets.on("connection", (socket) => {
    socket.on("message", (raw) => {
      let message;
      try {
        message = JSON.parse(raw.toString());
      } catch {
        socket.send(JSON.stringify({ type: "error", message: "Invalid JSON" }));
        return;
      }

      if (message.type === "join") {
        const room = String(message.room || "").toLowerCase();
        const role = message.role === "phone" ? "phone" : "tv";
        if (!/^[a-f0-9]{8}$/.test(room)) {
          socket.send(
            JSON.stringify({ type: "error", message: "Invalid room" }),
          );
          return;
        }
        clients.set(socket, { room, role });
        const peers = [...clients.values()]
          .filter(
            (client) => client.room === room && client !== clients.get(socket),
          )
          .map((client) => client.role);
        socket.send(JSON.stringify({ type: "joined", room, peers }));
        const selectedTrackId = selectedTracksByRoom.get(room);
        const selectedTrack = selectedTrackId && tracks.get(selectedTrackId);
        if (selectedTrack?.status === "ready") {
          socket.send(JSON.stringify({
            type: "track-selected",
            track: trackSummary(selectedTrack, true),
          }));
        }
        const avatarDataUrl = avatarsByRoom.get(room);
        if (avatarDataUrl) {
          socket.send(JSON.stringify({ type: "avatar-selected", dataUrl: avatarDataUrl }));
        }
        broadcast(socket, { type: "peer-joined", role });
        return;
      }

      if (message.type === "signal" && clients.has(socket)) {
        broadcast(socket, { type: "signal", data: message.data });
        return;
      }

      if (message.type === "select-track" && clients.has(socket)) {
        const track = tracks.get(String(message.trackId || ""));
        if (!track || track.status !== "ready") {
          socket.send(JSON.stringify({ type: "error", message: "Track is not ready" }));
          return;
        }
        const client = clients.get(socket);
        selectedTracksByRoom.set(client.room, track.id);
        broadcastToRoom(client.room, {
          type: "track-selected",
          track: trackSummary(track, true),
        });
        return;
      }

      if (message.type === "set-avatar" && clients.has(socket)) {
        const client = clients.get(socket);
        const dataUrl = typeof message.dataUrl === "string" ? message.dataUrl : "";
        const validImage = /^data:image\/(?:jpeg|png|webp);base64,[a-z0-9+/=]+$/i.test(dataUrl);
        if (!validImage || dataUrl.length > 250_000) {
          socket.send(JSON.stringify({
            type: "error",
            message: "Avatar must be a JPEG, PNG, or WebP image under 250 KB",
          }));
          return;
        }
        avatarsByRoom.set(client.room, dataUrl);
        broadcastToRoom(client.room, { type: "avatar-selected", dataUrl });
      }
    });

    socket.on("close", () => {
      const client = clients.get(socket);
      if (client) broadcast(socket, { type: "peer-left", role: client.role });
      clients.delete(socket);
    });
  });
}

function broadcast(sender, message) {
  const senderClient = clients.get(sender);
  if (!senderClient) return;
  const payload = JSON.stringify(message);
  for (const [socket, client] of clients) {
    if (
      socket !== sender &&
      client.room === senderClient.room &&
      socket.readyState === WebSocket.OPEN
    ) {
      socket.send(payload);
    }
  }
}

function broadcastToRoom(room, message) {
  const payload = JSON.stringify(message);
  for (const [socket, client] of clients) {
    if (client.room === room && socket.readyState === WebSocket.OPEN) {
      socket.send(payload);
    }
  }
}

function startServer(server, port, label) {
  attachSignaling(server);
  server.listen(port, "0.0.0.0", () => {
    console.log(
      `${label} listening on ${label.toLowerCase()}://${localAddress()}:${port}`,
    );
  });
}

const plainServer = http.createServer((request, response) => {
  requestHandler(request, response).catch((error) => {
    console.error(error);
    send(response, 500, "text/plain", "Internal server error");
  });
});
startServer(plainServer, httpPort, "HTTP");

if (process.env.TLS_KEY_PATH && process.env.TLS_CERT_PATH) {
  const secureServer = https.createServer(
    {
      key: fs.readFileSync(process.env.TLS_KEY_PATH),
      cert: fs.readFileSync(process.env.TLS_CERT_PATH),
    },
    (request, response) => {
      requestHandler(request, response).catch((error) => {
        console.error(error);
        send(response, 500, "text/plain", "Internal server error");
      });
    },
  );
  startServer(secureServer, httpsPort, "HTTPS");
}
