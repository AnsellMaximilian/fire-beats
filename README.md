# FireLab phone pose → Fire TV beat-game prototype

This project pairs a phone browser with a native Fire TV app by QR code. The phone runs lightweight MediaPipe pose detection locally and sends only normalized body landmarks to the TV over a WebRTC data channel; camera video stays on the phone.

A small Node server serves the phone page, creates pairing sessions, relays WebRTC signaling, stores uploaded MP3 files, and performs prototype beat analysis. The Fire TV renders a native four-corner rhythm game with pose calibration, color-coded hand targets, scoring, and sound.

## 1. Start the signaling server

```powershell
cd server
npm install
$env:PORT = "3050"
$env:PUBLIC_URL = "https://your-tunnel.example"
npm start
```

The server listens on every network interface. It defaults to port `3050`; `PORT` can override it. Check it with `http://localhost:3050/health`.

## 2. Give the phone an HTTPS URL

Phone browsers only allow camera access from a secure context. The quickest development setup is an HTTPS tunnel pointed at port 3050. Set its public URL before starting the server so the TV's QR code uses it:

```powershell
$env:PUBLIC_URL = "https://your-tunnel.example"
npm start
```

For example, with a reserved ngrok development domain:

```powershell
ngrok http 3050 --url https://your-domain.ngrok-free.dev
```

Alternatively, provide a certificate trusted by the phone. This keeps HTTP on port 3050 for the TV and also starts HTTPS on port 3443:

```powershell
$env:TLS_KEY_PATH = "C:\path\to\trusted-key.pem"
$env:TLS_CERT_PATH = "C:\path\to\trusted-cert.pem"
$env:PUBLIC_URL = "https://192.168.1.20:3443"
npm start
```

The phone and development machine must be able to reach each other. A tunnel is usually simpler than installing a local CA on a phone.

## 3. Point the Android app at the server

For an Android emulator, pass `10.0.2.2`, which points back to the development machine:

```powershell
.\gradlew.bat installDebug -PFIRELAB_SERVER_URL=http://10.0.2.2:3050
```

For a physical Fire TV, use the development machine's LAN address:

```powershell
.\gradlew.bat installDebug -PFIRELAB_SERVER_URL=http://192.168.1.20:3050
```

Replace `192.168.1.20` with the address printed by the Node server. The Fire TV and development machine must be on the same network, and the firewall must allow the selected inbound TCP port. `FIRELAB_SERVER_URL` is compiled into the APK, so rebuild and reinstall after changing it.

## 4. Pair

1. Launch FireLab on the TV.
2. Scan the QR code with the phone's normal camera app.
3. Tap **Start pose tracking** and grant camera permission.
4. Follow the TV prompts to calibrate the upper and lower reach corners, then use the overhead ready circle.
5. Upload or select an MP3 on the phone. Notes travel from the center toward the four calibrated targets.

## Desktop audio playground

To inspect beat detection and bubble timing without pairing the phone and TV, use the Electron tool in `desktop-playground/`. It visualizes the exact current server grid beside adaptive beats and raw onsets, plays a keyboard-controlled four-lane preview, supports manual lyric/sound markers, and exports the analysis as JSON.

```powershell
cd desktop-playground
npm install
npm start
```

Install the server dependencies first because the playground deliberately imports the same audio-analysis module used by the server. See `desktop-playground/README.md` for controls and setup details.

For focused native receiver logs:

```powershell
adb logcat -v time FireTV:V AndroidRuntime:E "*:S"
```

## Prototype limitations

- The Google public STUN server is enough for many local and ordinary NAT setups, but production use needs a TURN server for reliable connectivity.
- Pairing rooms and selected tracks are held in memory; uploaded MP3 files and their analysis metadata are stored under `server/data/`.
- Use this only on a trusted development network. Add authentication and tighter origin controls before exposing it as a real service.
- The phone page currently loads MediaPipe and its pose model from public CDNs.
- Beat detection, calibration thresholds, hit windows, and gameplay are prototype-quality and need broader device/user testing.
