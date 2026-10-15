# FireLab phone camera → Fire TV prototype

This project pairs a phone browser with the Fire TV app by QR code and sends the phone's rear-camera video directly to the TV with WebRTC. A small Node server serves both browser pages and relays WebRTC signaling over WebSocket; it never handles the video itself.

## 1. Start the signaling server

```powershell
cd server
npm install
npm start
```

The server listens on every network interface at port `3000`. Check it with `http://localhost:3000/health`.

## 2. Give the phone an HTTPS URL

Phone browsers only allow camera access from a secure context. The quickest development setup is an HTTPS tunnel pointed at port 3000. Set its public URL before starting the server so the TV's QR code uses it:

```powershell
$env:PUBLIC_URL = "https://your-tunnel.example"
npm start
```

Alternatively, provide a certificate trusted by the phone. This keeps HTTP on port 3000 for the TV and also starts HTTPS on port 3443:

```powershell
$env:TLS_KEY_PATH = "C:\path\to\trusted-key.pem"
$env:TLS_CERT_PATH = "C:\path\to\trusted-cert.pem"
$env:PUBLIC_URL = "https://192.168.1.20:3443"
npm start
```

The phone and development machine must be able to reach each other. A tunnel is usually simpler than installing a local CA on a phone.

## 3. Point the Android app at the server

For an Android emulator, the default `http://10.0.2.2:3000` already points back to the development machine:

```powershell
.\gradlew.bat installDebug
```

For a physical Fire TV, use the development machine's LAN address:

```powershell
.\gradlew.bat installDebug -PFIRELAB_SERVER_URL=http://192.168.1.20:3000
```

Replace `192.168.1.20` with the address printed by the Node server. The Fire TV and development machine must be on the same network, and the firewall must allow inbound TCP port 3000.

## 4. Pair

1. Launch FireLab on the TV.
2. Scan the QR code with the phone's normal camera app.
3. Tap **Start camera** and grant camera permission.
4. The pairing screen disappears when the WebRTC stream arrives.

## Prototype limitations

- The Google public STUN server is enough for many local and ordinary NAT setups, but production use needs a TURN server for reliable connectivity.
- Rooms are short-lived only because clients disconnect; there is no authentication or persistence.
- Use this only on a trusted development network. Add authentication and tighter origin controls before exposing it as a real service.
- Video is one-way and audio is disabled.
