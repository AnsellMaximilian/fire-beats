# Fire Beats phone pose → Fire TV rhythm game

Fire Beats pairs a phone browser with a native Fire TV app by QR code. The phone runs MediaPipe pose detection locally and sends normalized landmarks to the TV over WebRTC; camera video never leaves the phone.

The Android application ID is `io.github.ansellmaximilian.firebeats`. Launcher icons, the TV banner, splash screen, native pairing screen, and phone controller share the same Fire Beats identity.

## Install on Fire TV with Downloader

The `v0.1.0` release can be sideloaded directly on a compatible Fire TV without
a computer:

1. Install **Downloader** from the Amazon Appstore.
2. Allow Downloader to install unknown apps under **Settings > My Fire TV >
   Developer Options > Install unknown apps**. If Developer Options is hidden,
   open **Settings > My Fire TV > About**, highlight the device name, and press
   the remote's center button seven times.
3. Open Downloader and enter code **4389006**. The equivalent short URL is
   [`aftv.news/4389006`](https://aftv.news/4389006).
4. Download and install `fire-beats.apk`, then open Fire Beats from the Apps
   screen.

The Downloader code points specifically to the GitHub asset for `v0.1.0`:

```text
https://github.com/AnsellMaximilian/fire-beats/releases/download/v0.1.0/fire-beats.apk
```

The release must exist and be publicly accessible before the code can download
it. A device with a development/debug build already installed must uninstall
that build once before installing the signed release; this clears that app's
local data. Later releases signed with the same release key can update in place.

## Hardware compatibility

Fire Beats is developed and tested on the **Amazon Fire TV Stick HD with Alexa
Voice Remote (2024, Wi-Fi 5)**, also sold as “Fire TV Stick HD 2024 Wi-Fi 5 with
Alexa.” Amazon identifies this model as build model `AFTSS`, running Fire OS 7.

The current APK requires Android API level 23 or newer. It is therefore expected
to work on Android-based Fire TV sticks, Fire TV Cubes, and televisions with Fire
TV built in that run **Fire OS 6 or newer**, although those devices have not all
been tested. Fire OS 5 devices use Android API level 22 and cannot install this
build. Check a device against Amazon's [Fire OS device
table](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html) when in
doubt.

The APK does **not** support Vega OS devices, which require a separate VPKG app.
This currently includes models such as the Fire TV Stick 4K Select (2025), Fire
TV Stick HD (2026), and Fire TV Stick 4K 3rd Gen (2026). Amazon documents Fire OS
and Vega OS as separate [Fire TV development
platforms](https://developer.amazon.com/apps-and-games/sdks).

Testing also requires:

- A Fire TV remote or equivalent controller with a D-pad and select button.
- A modern phone with a camera and an HTTPS-capable browser for QR pairing and
  pose tracking.
- Internet access on both devices. Alexa play/pause is optional and requires an
  Alexa Voice Remote or a compatible paired Echo; D-pad controls work without
  Alexa.

## Fire TV artwork

- `app/src/main/res/drawable-xhdpi/firebeats_tv_banner.png` is the 320 x 180 Android TV banner referenced by the manifest.
- `store-assets/fire-tv-app-icon.png` is the opaque 1280 x 720 app icon to upload under **Fire TV assets** in the Amazon Appstore submission.

Fire OS uses the APK's square Android launcher icon for a sideloaded build on some devices. The Amazon Appstore supplies the landscape app tile after publication, so a sideloaded launcher test is not a reliable preview of the submitted Fire TV icon.

The long-running Node service hosts the phone UI, creates pairing rooms, relays WebRTC signaling over WebSockets, and analyzes uploaded audio. Neon supplies Managed Auth, Postgres metadata, and S3-compatible object storage:

- Guests can pair, play public default tracks, and use an avatar.
- Google sign-in is optional and is required only for uploading or listing personal tracks.
- Uploaded audio is private and every API/WebSocket selection is owner-checked.
- A private track reaches the paired TV through a short-lived signed object URL.
- The cropped avatar is stored only in the phone browser's `localStorage` and is resent to the TV when the phone joins a room.
- Pairing rooms are 128-bit random capabilities and live only in server memory.

## Neon development setup

This repository is configured for Neon project `purple-leaf-30592465`. The local `.neon` pointer and `.env.local` are ignored by Git. Keep local work on the `development` Neon branch:

```powershell
neon checkout development --no-env-pull
neon deploy
npm install
cd server
npm install
npm run migrate
npm run verify:neon
```

`neon deploy` provisions the services in `neon.ts` and refreshes the branch-specific secrets in the root `.env.local`. The migration command prefers the direct `DATABASE_URL_UNPOOLED`; the running app uses the pooled `DATABASE_URL`.

To add public music, put audio you are allowed to distribute in `server/default-tracks/`, then run:

```powershell
cd server
npm run seed:defaults
```

The source audio is ignored by Git. The seed command analyzes it, uploads it to the public `default-tracks` bucket, and upserts its Postgres row using a content-derived ID.

## Run locally

```powershell
cd server
npm run build
$env:PORT = "3050"
npm start
```

The server loads the root `.env.local`, listens on every network interface, and exposes a shallow health check at `http://localhost:3050/health`.

Android builds target `https://fire-beats.onrender.com` by default. Override that for an emulator connected to the local server with:

```powershell
.\gradlew.bat :app:assembleDebug -PFIREBEATS_SERVER_URL=http://10.0.2.2:3050
```

## Tagged Android releases

Pushing a tag such as `v0.1.0` builds and verifies a release-signed APK, publishes
it with a SHA-256 checksum as a GitHub Release, and retains the same files as a
workflow artifact. The one-time signing-key setup and release commands are in
[`docs/releasing.md`](docs/releasing.md).

Phone browsers require HTTPS for camera access. For local device testing, set `PUBLIC_URL` to an HTTPS tunnel or run the optional local TLS listener:

```powershell
$env:TLS_KEY_PATH = "C:\path\to\trusted-key.pem"
$env:TLS_CERT_PATH = "C:\path\to\trusted-cert.pem"
$env:PUBLIC_URL = "https://192.168.1.20:3443"
npm start
```

## Deploy the test backend on Render

`render.yaml` defines one Singapore web service, matching the Neon project region. Keep it at one instance because active rooms, avatars, and WebRTC signaling state are deliberately in memory.

For a quick demo, create a regular **Web Service** manually; a Blueprint is optional:

1. In Render choose **New > Web Service**, connect this repository, and select the Git branch containing these changes.
2. Set **Region** to Singapore, **Root Directory** to `server`, **Build Command** to `npm ci && npm run build`, **Start Command** to `npm run migrate && npm start`, and **Instance Type** to Free. Under Advanced, set **Health Check Path** to `/health`.
3. Add `NODE_VERSION=24` plus these values from the target Neon's environment: `DATABASE_URL`, `DATABASE_URL_UNPOOLED`, `NEON_AUTH_BASE_URL`, `NEON_AUTH_JWKS_URL`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_ENDPOINT_URL_S3`, and `AWS_REGION`. Do not set `PORT`; Render supplies it. `PUBLIC_URL` is also optional because the server reads Render's automatic `RENDER_EXTERNAL_URL`.
4. Deploy. Render applies migrations and starts the WebSocket/HTTP service.
5. Add the resulting `https://<service-name>.onrender.com` origin as a trusted Neon Auth domain. Before a real production launch, configure your own Google OAuth Client ID and secret in Neon Auth; use Neon’s displayed Google callback URL.
6. Build the APK. The deployed origin is now the build default:

```powershell
.\gradlew.bat :app:assembleDebug
```

For the first hosted test, it is reasonable to point Render at the Neon `development` branch. Once verified, apply `neon.ts` and `server/migrations/` to `production`, change only Render's Neon environment variables, and rebuild the APK only if the Render origin changes.

The checked-in Blueprint is still available and explicitly uses Render's free plan, but it is not required for the manual flow above.

## Pair and play

1. Launch Fire Beats on the TV.
2. Scan its QR code with the phone camera.
3. Choose a public default track, or sign in with Google to upload/select a private track.
4. Optionally take or choose a player photo. It remains in that phone browser.
5. Start pose tracking, grant camera permission, and follow the TV calibration prompts.

During an active round on Fire TV, say **“Alexa, pause”** or **“Alexa, play”**
through the Voice Remote or a paired Echo. The Play/Pause media button uses the
same Android MediaSession controls.

## Verification

```powershell
npm test
cd server
npm run verify:neon
cd ..
.\gradlew.bat :app:assembleDebug
```

`verify:neon` checks the database schema, Auth signing keys, a private object round trip and signed URL, and anonymous retrieval from the public bucket. It deletes its diagnostic objects afterward.

## Desktop audio playground

The Electron tool in `desktop-playground/` visualizes the same audio analysis used by the server:

```powershell
cd desktop-playground
npm install
npm start
```

## Remaining production work

- Configure owned Google OAuth credentials and the deployed trusted origin.
- Add licensed/default audio content; the public bucket and seeder are ready but intentionally empty.
- Add a TURN service for reliable WebRTC connectivity across difficult NATs.
- Move ephemeral room state to a shared coordinator only if the service needs more than one instance.
- Add retention/deletion controls and abuse limits before opening uploads beyond a small test group.
- The phone page still loads MediaPipe and its pose model from public CDNs.
