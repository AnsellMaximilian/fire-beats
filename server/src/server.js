import "./environment.js";

import { createHash, randomBytes } from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import os from "node:os";
import path from "node:path";
import { Transform } from "node:stream";
import { fileURLToPath } from "node:url";

import QRCode from "qrcode";
import { WebSocket, WebSocketServer } from "ws";

import { analyzeEncodedAudio } from "./audio-analysis.js";
import {
  UnauthorizedError,
  optionalUserFromRequest,
  publicAuthConfiguration,
  verifyAccessToken,
} from "./auth.js";
import {
  closeDatabase,
  completeTrackAnalysis,
  createUploadedTrack,
  findTrack,
  findVisibleTrack,
  listVisibleTracks,
  markInterruptedUploads,
  setTrackProgress,
  tracksNeedingAnalysis,
} from "./database.js";
import {
  deleteObject,
  downloadObject,
  publicObjectUrl,
  signedObjectUrl,
  uploadObject,
  userTracksBucket,
} from "./storage.js";

const sourceDirectory = path.dirname(fileURLToPath(import.meta.url));
const publicDirectory = path.resolve(sourceDirectory, "../public");
const httpPort = Number(process.env.PORT || 3050);
const httpsPort = Number(process.env.HTTPS_PORT || 3443);
const maxUploadBytes = 50 * 1024 * 1024;
const roomPattern = /^[a-f0-9]{32}$/;
const trackPattern = /^[a-f0-9]{16}$/;
const clients = new Map();
const selectedTracksByRoom = new Map();
const avatarsByRoom = new Map();
const socketServers = new Set();

const readPage = (name) => fs.readFileSync(path.join(publicDirectory, name), "utf8");

function localAddress() {
  for (const interfaces of Object.values(os.networkInterfaces())) {
    for (const address of interfaces || []) {
      if (address.family === "IPv4" && !address.internal) return address.address;
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
  const room = randomBytes(16).toString("hex");
  const phoneUrl = `${originForPhone(request)}/phone?room=${encodeURIComponent(room)}`;
  return { room, phoneUrl };
}

function send(response, status, type, body, headers = {}) {
  if (response.headersSent) return;
  response.writeHead(status, {
    "content-type": `${type}; charset=utf-8`,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
    ...headers,
  });
  response.end(body);
}

function sendJson(response, status, value) {
  send(response, status, "application/json", JSON.stringify(value));
}

function trackSummary(track, includeBeats = false, audioUrl = undefined) {
  const summary = {
    id: track.id,
    name: track.name,
    size: track.sizeBytes,
    status: track.status,
    progress: track.progress,
    bpm: track.bpm,
    duration: track.duration,
    beatCount: track.beats?.length || 0,
    error: track.error,
    createdAt: track.createdAt,
    source: track.source,
    visibility: track.visibility,
  };
  if (includeBeats) summary.beats = track.beats || [];
  if (audioUrl) summary.audioUrl = audioUrl;
  return summary;
}

async function trackWithPlayback(track) {
  const audioUrl = track.visibility === "public"
    ? publicObjectUrl(track.bucket, track.objectKey)
    : await signedObjectUrl(track.bucket, track.objectKey);
  return trackSummary(track, true, audioUrl);
}

function publishTrackProgress(room, track, message) {
  if (!room) return;
  broadcastToRoom(room, { type: "track-progress", message, track: trackSummary(track) });
}

async function analyzeTrack(id, room = null) {
  const original = await findTrack(id);
  if (!original) return;
  try {
    let track = await setTrackProgress(id, "analyzing", 60);
    publishTrackProgress(room, track, "Decoding audio…");
    const encodedAudio = await downloadObject(track.bucket, track.objectKey);
    track = await setTrackProgress(id, "analyzing", 80);
    publishTrackProgress(room, track, "Detecting beats…");

    const decoded = await analyzeEncodedAudio(encodedAudio, { minBpm: 60, maxBpm: 200 });
    const analysis = {
      bpm: Math.round(decoded.current.bpm * 10) / 10,
      beats: Array.from(decoded.current.beats, (beat) => Math.round(beat * 1000) / 1000),
      duration: Math.round(decoded.duration * 100) / 100,
    };
    track = await completeTrackAnalysis(id, analysis);
    publishTrackProgress(room, track, `Ready — ${track.bpm} BPM, ${track.beats.length} beats`);
  } catch (error) {
    console.error(`Track analysis failed for ${id}`, error);
    const track = await setTrackProgress(
      id,
      "error",
      100,
      error?.message || "Audio analysis failed",
    );
    publishTrackProgress(room, track, track.error);
  }
}

async function receiveTrackUpload(request, response, url) {
  const authenticated = await optionalUserFromRequest(request);
  if (!authenticated) throw new UnauthorizedError();

  const roomValue = String(url.searchParams.get("room") || "").toLowerCase();
  const room = roomPattern.test(roomValue) ? roomValue : null;
  const name = String(url.searchParams.get("name") || "Uploaded track").trim().slice(0, 160)
    || "Uploaded track";
  const contentLength = Number(request.headers["content-length"] || 0);
  const mimeType = String(request.headers["content-type"] || "audio/mpeg").split(";")[0];
  if (!Number.isSafeInteger(contentLength) || contentLength <= 0) {
    sendJson(response, 411, { error: "A Content-Length header is required" });
    return;
  }
  if (contentLength > maxUploadBytes) {
    sendJson(response, 413, { error: "Audio must be 50 MB or smaller" });
    return;
  }
  if (!mimeType.startsWith("audio/") && mimeType !== "application/octet-stream") {
    sendJson(response, 415, { error: "Upload must be an audio file" });
    return;
  }

  const id = randomBytes(8).toString("hex");
  const ownerPath = createHash("sha256").update(authenticated.userId).digest("hex").slice(0, 32);
  const objectKey = `users/${ownerPath}/${id}/audio`;
  let track = await createUploadedTrack({
    id,
    ownerUserId: authenticated.userId,
    name,
    bucket: userTracksBucket,
    objectKey,
    mimeType,
    sizeBytes: contentLength,
  });

  let receivedBytes = 0;
  let lastPublishedProgress = -1;
  const uploadStream = new Transform({
    transform(chunk, _encoding, callback) {
      receivedBytes += chunk.length;
      const progress = Math.min(55, Math.round((receivedBytes / contentLength) * 55));
      if (progress >= lastPublishedProgress + 2) {
        lastPublishedProgress = progress;
        publishTrackProgress(room, { ...track, progress }, `Uploading ${progress}%`);
      }
      callback(null, chunk);
    },
  });
  request.pipe(uploadStream);

  try {
    await uploadObject({
      bucket: track.bucket,
      key: track.objectKey,
      body: uploadStream,
      contentLength,
      contentType: mimeType,
    });
    if (receivedBytes !== contentLength) throw new Error("Upload was interrupted");
    track = await setTrackProgress(id, "analyzing", 60);
    console.log(`[FireTV] Track upload received id=${id} bytes=${receivedBytes}`);
    sendJson(response, 202, trackSummary(track));
    void analyzeTrack(id, room);
  } catch (error) {
    console.error(`[FireTV] Track upload failed id=${id}`, error);
    request.unpipe(uploadStream);
    uploadStream.destroy();
    await setTrackProgress(id, "error", 100, error?.message || "Upload failed");
    await deleteObject(track.bucket, track.objectKey).catch(() => {});
    if (!response.headersSent) sendJson(response, 400, { error: error?.message || "Upload failed" });
  }
}

async function requestHandler(request, response) {
  const protocol = request.socket.encrypted ? "https" : "http";
  const base = `${protocol}://${request.headers.host || "localhost"}`;
  const url = new URL(request.url || "/", base);

  if (url.pathname === "/health") {
    sendJson(response, 200, { ok: true });
    return;
  }
  if (url.pathname === "/") {
    response.writeHead(302, { location: "/tv" });
    response.end();
    return;
  }
  if (url.pathname === "/api/config" && request.method === "GET") {
    sendJson(response, 200, publicAuthConfiguration());
    return;
  }
  if (url.pathname === "/api/session" && request.method === "GET") {
    sendJson(response, 200, createSession(request));
    return;
  }
  if (url.pathname === "/api/tracks" && request.method === "GET") {
    const authenticated = await optionalUserFromRequest(request);
    const tracks = await listVisibleTracks(authenticated?.userId);
    sendJson(response, 200, tracks.map((track) => trackSummary(track)));
    return;
  }
  if (url.pathname === "/api/tracks" && request.method === "POST") {
    await receiveTrackUpload(request, response, url);
    return;
  }

  const trackMatch = url.pathname.match(/^\/api\/tracks\/([a-f0-9]{16})$/);
  if (trackMatch && request.method === "GET") {
    const authenticated = await optionalUserFromRequest(request);
    const track = await findVisibleTrack(trackMatch[1], authenticated?.userId);
    if (!track) return sendJson(response, 404, { error: "Track not found" });
    sendJson(response, 200, trackSummary(track, true));
    return;
  }

  const audioMatch = url.pathname.match(/^\/api\/tracks\/([a-f0-9]{16})\/audio$/);
  if (audioMatch && request.method === "GET") {
    const authenticated = await optionalUserFromRequest(request);
    const track = await findVisibleTrack(audioMatch[1], authenticated?.userId);
    if (!track || track.status !== "ready") {
      return sendJson(response, 404, { error: "Track audio not found" });
    }
    const location = track.visibility === "public"
      ? publicObjectUrl(track.bucket, track.objectKey)
      : await signedObjectUrl(track.bucket, track.objectKey);
    response.writeHead(302, { location, "cache-control": "no-store" });
    response.end();
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
  if (url.pathname === "/phone") return send(response, 200, "text/html", readPage("phone.html"));
  if (url.pathname === "/shared.css") return send(response, 200, "text/css", readPage("shared.css"));
  if (url.pathname === "/auth-client.js") {
    return send(response, 200, "text/javascript", readPage("auth-client.js"));
  }
  send(response, 404, "text/plain", "Not found");
}

async function handleSocketMessage(socket, raw) {
  let message;
  try {
    message = JSON.parse(raw.toString());
  } catch {
    socket.send(JSON.stringify({ type: "error", message: "Invalid JSON" }));
    return;
  }

  if (message.type === "join") {
    const room = String(message.room || "").toLowerCase();
    const role = message.role;
    if (!roomPattern.test(room) || !["phone", "tv"].includes(role)) {
      socket.send(JSON.stringify({ type: "error", message: "Invalid room or role" }));
      return;
    }
    let userId = null;
    if (message.token) userId = (await verifyAccessToken(message.token)).userId;

    for (const [otherSocket, client] of clients) {
      if (otherSocket !== socket && client.room === room && client.role === role) {
        clients.delete(otherSocket);
        otherSocket.close(1000, "Replaced by a newer connection");
      }
    }
    clients.set(socket, { room, role, userId });
    const peers = [...clients.entries()]
      .filter(([peer, client]) => peer !== socket && client.room === room)
      .map(([, client]) => client.role);
    socket.send(JSON.stringify({ type: "joined", room, peers }));

    const selectedTrackId = selectedTracksByRoom.get(room);
    const selectedTrack = selectedTrackId ? await findTrack(selectedTrackId) : null;
    if (selectedTrack?.status === "ready") {
      socket.send(JSON.stringify({ type: "track-selected", track: await trackWithPlayback(selectedTrack) }));
    }
    const avatarDataUrl = avatarsByRoom.get(room);
    if (avatarDataUrl) socket.send(JSON.stringify({ type: "avatar-selected", dataUrl: avatarDataUrl }));
    broadcast(socket, { type: "peer-joined", role });
    return;
  }

  const client = clients.get(socket);
  if (!client) return;
  if (message.type === "signal") {
    broadcast(socket, { type: "signal", data: message.data });
    return;
  }
  if (message.type === "select-track") {
    if (client.role !== "phone" || !trackPattern.test(String(message.trackId || ""))) return;
    const track = await findVisibleTrack(String(message.trackId), client.userId);
    if (!track || track.status !== "ready") {
      socket.send(JSON.stringify({ type: "error", message: "Track is unavailable or not ready" }));
      return;
    }
    selectedTracksByRoom.set(client.room, track.id);
    broadcastToRoom(client.room, { type: "track-selected", track: await trackWithPlayback(track) });
    return;
  }
  if (message.type === "set-avatar" && client.role === "phone") {
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
}

function attachSignaling(server) {
  const sockets = new WebSocketServer({ server, path: "/signal", maxPayload: 300_000 });
  socketServers.add(sockets);
  sockets.on("connection", (socket) => {
    socket.isAlive = true;
    socket.on("pong", () => { socket.isAlive = true; });
    socket.on("message", (raw) => {
      void handleSocketMessage(socket, raw).catch((error) => {
        const message = error instanceof UnauthorizedError ? error.message : "Request failed";
        if (!(error instanceof UnauthorizedError)) console.error("WebSocket message failed", error);
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: "error", message }));
      });
    });
    socket.on("close", () => {
      const client = clients.get(socket);
      clients.delete(socket);
      if (!client) return;
      broadcastToRoom(client.room, { type: "peer-left", role: client.role });
      if (![...clients.values()].some((peer) => peer.room === client.room)) {
        selectedTracksByRoom.delete(client.room);
        avatarsByRoom.delete(client.room);
      }
    });
  });
  const heartbeat = setInterval(() => {
    for (const socket of sockets.clients) {
      if (!socket.isAlive) {
        socket.terminate();
        continue;
      }
      socket.isAlive = false;
      socket.ping();
    }
  }, 30_000);
  heartbeat.unref();
  sockets.on("close", () => clearInterval(heartbeat));
}

function broadcast(sender, message) {
  const senderClient = clients.get(sender);
  if (!senderClient) return;
  const payload = JSON.stringify(message);
  for (const [socket, client] of clients) {
    if (socket !== sender && client.room === senderClient.room && socket.readyState === WebSocket.OPEN) {
      socket.send(payload);
    }
  }
}

function broadcastToRoom(room, message) {
  const payload = JSON.stringify(message);
  for (const [socket, client] of clients) {
    if (client.room === room && socket.readyState === WebSocket.OPEN) socket.send(payload);
  }
}

function createRequestListener() {
  return (request, response) => {
    requestHandler(request, response).catch((error) => {
      if (error instanceof UnauthorizedError) {
        sendJson(response, 401, { error: error.message });
        return;
      }
      console.error(error);
      sendJson(response, 500, { error: "Internal server error" });
    });
  };
}

function startServer(server, port, label) {
  attachSignaling(server);
  server.listen(port, "0.0.0.0", () => {
    console.log(`${label} listening on ${label.toLowerCase()}://${localAddress()}:${port}`);
  });
}

await markInterruptedUploads();
const pending = await tracksNeedingAnalysis();
for (const track of pending) void analyzeTrack(track.id);

const servers = [];
const plainServer = http.createServer(createRequestListener());
servers.push(plainServer);
startServer(plainServer, httpPort, "HTTP");

if (process.env.TLS_KEY_PATH && process.env.TLS_CERT_PATH) {
  const secureServer = https.createServer(
    { key: fs.readFileSync(process.env.TLS_KEY_PATH), cert: fs.readFileSync(process.env.TLS_CERT_PATH) },
    createRequestListener(),
  );
  servers.push(secureServer);
  startServer(secureServer, httpsPort, "HTTPS");
}

async function shutdown() {
  for (const sockets of socketServers) sockets.close();
  await Promise.all(servers.map((server) => new Promise((resolve) => server.close(resolve))));
  await closeDatabase();
}

process.once("SIGTERM", () => void shutdown().finally(() => process.exit(0)));
process.once("SIGINT", () => void shutdown().finally(() => process.exit(0)));
