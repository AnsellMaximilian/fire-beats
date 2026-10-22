# Releasing Fire Beats

Pushing a semantic version tag such as `v0.1.0` runs
`.github/workflows/release.yml`. The workflow builds a release-signed APK, verifies
its signature, creates a SHA-256 checksum, retains both files as a workflow
artifact, and creates the matching GitHub Release.

## One-time signing setup

Android requires every update for an installed app to use the same signing key.
Generate this key once, keep an offline backup, and never commit it to Git:

```powershell
keytool -genkeypair -v `
  -keystore fire-beats-release.jks `
  -alias fire-beats `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000
```

Convert the keystore to a one-line Base64 value:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("fire-beats-release.jks")
) | Set-Clipboard
```

Create these repository secrets under **Settings > Secrets and variables >
Actions**:

| Secret | Value |
| --- | --- |
| `FIREBEATS_KEYSTORE_BASE64` | The Base64 value copied above |
| `FIREBEATS_KEYSTORE_PASSWORD` | The keystore password |
| `FIREBEATS_KEY_ALIAS` | `fire-beats`, unless a different alias was chosen |
| `FIREBEATS_KEY_PASSWORD` | The private-key password |

The keystore and password values are provided to Gradle only as runner
environment variables. They are not stored in the APK or repository.

## Publish a release

Make sure the release commit is pushed before its tag:

```powershell
git push origin main
git tag v0.1.0
git push origin v0.1.0
```

Tags must use `vMAJOR.MINOR.PATCH`. The workflow uses the tag without `v` as the
APK `versionName` and maps it to an increasing Android `versionCode`. A successful
run creates these release assets:

- `fire-beats.apk`
- `fire-beats.apk.sha256`

The stable APK filename gives Downloader and other installers a URL that does not
change between releases:

```text
https://github.com/AnsellMaximilian/fire-beats/releases/latest/download/fire-beats.apk
```

This anonymous URL works only while the repository and release are public. A
private repository requires each downloader to have GitHub access, so use a
public repository or another public APK host before sharing a Downloader code.

## Install on a Fire TV

After enabling ADB debugging and connecting to the Fire TV, a first install is:

```powershell
adb install fire-beats.apk
```

Future releases signed by the same key can update it without clearing local
scores:

```powershell
adb install -r fire-beats.apk
```

A release-signed build cannot replace a debug-signed development build. A device
that already has a development APK must uninstall it once before installing the
first release APK; uninstalling clears that app's local data.
