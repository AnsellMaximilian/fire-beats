import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes } from "node:crypto";
import QRCode from "qrcode";
import { WebSocketServer, WebSocket } from "ws";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDirectory = path.resolve(__dirname, "../public");
const httpPort = Number(process.env.PORT || 3000);
const httpsPort = Number(process.env.HTTPS_PORT || 3443);

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

function send(response, status, type, body) {
  response.writeHead(status, {
    "content-type": `${type}; charset=utf-8`,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(body);
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

  if (url.pathname === "/tv") {
    const room = randomBytes(4).toString("hex");
    const phoneUrl = `${originForPhone(request)}/phone?room=${encodeURIComponent(room)}`;
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
          socket.send(JSON.stringify({ type: "error", message: "Invalid room" }));
          return;
        }
        clients.set(socket, { room, role });
        const peers = [...clients.values()]
          .filter((client) => client.room === room && client !== clients.get(socket))
          .map((client) => client.role);
        socket.send(JSON.stringify({ type: "joined", room, peers }));
        broadcast(socket, { type: "peer-joined", role });
        return;
      }

      if (message.type === "signal" && clients.has(socket)) {
        broadcast(socket, { type: "signal", data: message.data });
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
    if (socket !== sender && client.room === senderClient.room && socket.readyState === WebSocket.OPEN) {
      socket.send(payload);
    }
  }
}

function startServer(server, port, label) {
  attachSignaling(server);
  server.listen(port, "0.0.0.0", () => {
    console.log(`${label} listening on ${label.toLowerCase()}://${localAddress()}:${port}`);
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
