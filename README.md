# Screen Companion · 同屏

**English** | [简体中文](README.zh-CN.md)

Two-person screen sharing, camera video calls, and text/voice chat for Android and desktop browsers. Create a room, share its eight-digit code, talk, and take turns sharing a screen. The Android client uses Kotlin and Jetpack Compose; the desktop client uses native JavaScript and WebRTC. Both use the same Go signaling service built with the standard library.

Version **0.4.0** is a development and testing release. It does not include accounts, remote control, or recording storage. Screen video, microphone audio, and supported shared media audio travel over WebRTC. HTTP long polling exchanges only SDP, ICE candidates, and sharing state; the signaling server does not forward media. Devices connect directly when possible, with TURN as a fallback. High resolution and low latency are not guaranteed across physical devices and networks.

## Self-hosting and supported platforms

This repository does not provide a public test service or a preconfigured server address. Deploy your own signaling service and website, build the Android APK, and enter your server address in the Android app's **Connection settings** (`连接设置`). Configuration templates are in [deploy](deploy/).

- **Desktop:** recent Chrome or Edge on Windows, macOS, or Linux. No native desktop installation is required. Microphone and screen capture require browser and operating-system permission.
- **Android:** Android 10 or later.
- **Room model:** two participants, bidirectional voice, and one shared screen at a time. iOS and multiparty calls are outside the current scope.

## Camera video and text chat

After joining a call, choose **开启摄像头** (Enable camera) or use the chat panel. Camera permission is requested only when enabling video. Either participant can turn their camera off independently; Android also supports switching front/back cameras. Both cameras and one shared screen can run together.

Camera video targets up to 720p / 30 FPS / 2 Mbps, subject to device and network capacity. The existing 4K and precise quality controls apply to **screen sharing**, independently of the camera. On Android, an enabled camera can remain active when switching apps, with a foreground notification; turn it off or hang up to stop capture.

Text uses an ordered, reliable WebRTC DataChannel, with direct connections preferred and TURN as fallback. Messages are not stored by the signaling server. Each client keeps the most recent 200 messages in memory for the current call, with at most 2,000 UTF-16 code units per message. There is no offline delivery or cross-session history; accepting a message into the sender's channel is not a read receipt. Hangup clears the displayed history.

Both participants must use version 0.4.0 or later for video/chat. Update both ends together: older clients do not distinguish the new camera track from screen video.

## Run locally

Requires Go 1.23 or later. From the project root:

```sh
cd server
LISTEN_ADDR=0.0.0.0:8080 go run .
```

For desktop browser development, run the Go service with `LISTEN_ADDR=127.0.0.1:18765`, then run `python3 scripts/serve-web.py` from the project root. Open `http://127.0.0.1:18861/`. Remote browser access requires HTTPS; a plain HTTP LAN IP address does not meet browser media APIs' secure-context requirements.

For Android LAN testing, connect both devices and the computer to the same trusted Wi-Fi network. In the debug app, enter `http://YOUR_COMPUTER_LAN_IP:8080`. Do not enter the phone's own `localhost`. Allow TCP 8080 through the computer's firewall; guest Wi-Fi client isolation may prevent direct connections.

1. Install the Android APK or open the desktop website.
2. Make sure the website's API and Android connection address use the same signaling service.
3. Create a room, allow microphone access, and send the eight-digit room code to the other participant.
4. The other participant enters the code and joins.
5. Once voice is connected, either participant can start sharing and approve the system capture prompt.
6. Stop sharing to let the other participant share. Ending the call, dismissing the Android app task, or process termination ends the session.

### Audio and permissions

On Android, supported media/game audio capture starts with screen sharing and stops with it. **Mute** affects only the microphone. The remote participant's call audio is not captured again. The source app must allow audio capture; protected content, calls, notifications, and other non-media sounds are not guaranteed to be shared.

On desktop, prefer Chrome or Edge, select a **browser tab**, and enable **Share tab audio**. Whole-screen/window audio on macOS and Linux, and Safari/Firefox capabilities, depend on the browser and operating system. Windows whole-screen audio also depends on the options offered by the browser. The interface reports whether an audio track was captured. A computer without an available microphone cannot currently start a call.

Microphone permission enables voice; Nearby devices permission supports Bluetooth audio selection. Android 13+ notification permission can be declined, but notification controls may then be unavailable, so return to the app to stop sharing. On Android 10/11, Bluetooth routing is managed by the system; the app offers speaker and earpiece/headset switching.

## Resolution, frame rate, and bitrate

During a call, expand **Precise quality settings** (`精确调整画质`) on desktop or tap **Quality settings** (`画质设置`) on Android. Settings affect your outgoing screen. Apply changes without creating a new room. Settings last for the current call only.

| Setting | Supported values |
| --- | --- |
| Resolution | 720p, 1080p, 1440p, and 4K presets; custom even-pixel long edge 320–3840 and short edge 180–2160, with long edge ≥ short edge |
| Frame rate | Any integer from 1 to 60 FPS; common choices include 15, 24, 30, and 60 |
| Bitrate ceiling | 0.5–80 Mbps; desktop input uses 0.1 Mbps steps |
| Adaptation preference | Balanced, preserve resolution while allowing FPS to drop, or preserve FPS while allowing resolution to drop |

Resolution, FPS, and bitrate are independent: for example, **4K / 25 FPS / 17.5 Mbps**. Portrait orientation and source aspect ratio are respected. Lower-resolution sources are not enlarged to claim 4K. WebRTC congestion control remains active.

Presets:

| Preset | Resolution | FPS | Bitrate ceiling |
| --- | --- | --- | --- |
| Balanced | 1080p | 30 | 8 Mbps |
| Clear text | 1440p | 24 | 12 Mbps |
| Smooth motion | 1080p | 60 | 10 Mbps |
| 4K | 2160p | 30 | 24 Mbps |

The call screen separates target settings from actual sent/received resolution, FPS, and Mbps, and reports known CPU or bandwidth limitations. These are targets, not guarantees: 4K requires a suitable source and encoder/decoder, while 60 FPS needs changing content and sufficient performance. Both may not be achievable together. Static screens can legitimately use fewer frames and less bitrate. Invalid input is rejected. If a device rejects a combination, the client attempts to restore the previous settings; if restoration fails, screen sharing stops while voice can continue.

## Build the Android APK

Requires Java 17, Android SDK Platform 35, and Build Tools 35.0.0. The Gradle Wrapper version is pinned.

```sh
# Set ANDROID_HOME, or put sdk.dir=/path/to/sdk in an untracked local.properties.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Debug builds permit LAN HTTP and are not intended to send room credentials over untrusted networks. Release builds require HTTPS by default. Configure a persistent signing key and a valid server certificate before distribution. The debug key is for testing, not app-store publishing.

```sh
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Cross-network deployment

Devices on the same Wi-Fi may work without ICE services. Different carriers, mobile networks, and restrictive NATs need working STUN/TURN services. Use [server.env.example](deploy/server.env.example) and [turnserver.conf.example](deploy/turnserver.conf.example) as templates.

- `STUN_URLS`: comma-separated URLs, such as `stun:share.example.com:3478`.
- `TURN_URLS`: for example, `turn:share.example.com:3478?transport=udp,turn:share.example.com:3478?transport=tcp`.
- `TURN_SECRET`: must match coturn's `static-auth-secret`. Supply it through environment variables or restricted configuration files, never in the APK.
- Open coturn's configured UDP/TCP listening port and UDP relay port range. Configure the public/private address mapping when behind NAT.
- Public signaling requires HTTPS. Set `TLS_CERT_FILE` and `TLS_KEY_FILE` for direct TLS, or use an existing reverse proxy. The proxy read timeout must exceed 30 seconds.
- Rate limits use the TCP peer address, not client-provided `X-Forwarded-For`. Clients behind a shared proxy share creation/join limits. Evaluate trusted proxy handling and expected traffic before public operation.

The service issues temporary TURN credentials valid for three hours; rooms last at most two hours. WebRTC encrypts screen and audio media. Initial signaling authenticity depends on HTTPS and the configured service address.

Build a Linux signaling binary:

```sh
cd server
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -o ../dist/signaling-linux-amd64 .
```

[deploy/screenshare.service](deploy/screenshare.service) is an optional systemd template. Create a dedicated service user and configure files, certificates, and access permissions before enabling it. Adapt all templates to your own domain, ports, and environment. [nginx-web.locations.conf](deploy/nginx-web.locations.conf) serves the website and same-origin API under `/screenshare/`, leaving the site's root route separate.

## Verification

The public repository contains tests and written verification conclusions. Screenshots containing personal browser tabs and raw records containing network details remain local; those evidence paths in the verification documents are not distributed with the public source.

```sh
cd server
go test -race ./...
go vet ./...
```

Server tests cover the two-person limit, authorization, negotiation roles, replay and deduplication, sharing exclusivity, disconnects, room expiry, event windows, basic rate limiting, and TURN credentials.

From the project root, run `node --test web/tests/call.test.mjs` for browser negotiation, lifecycle, quality-setting, and statistics regressions. The development-only `/tests/browser.html` uses Canvas/sine-wave media inputs with real RTCPeerConnection instances. Do not deploy test pages. See the [0.3.0 verification record](docs/0.3.0验证记录.md) and [acceptance checklist](docs/测试与验收.md) (Chinese).

Android unit tests cover server addresses, HTTPS restrictions, playback-audio mixing, quality validation, and proportional capture sizing. Network round-trip time (RTT) is not a measurement of end-to-end screen latency.

## Project structure and limits

- `app/`: Android client. `CallService` owns the foreground service, `CallSession` coordinates the room/audio/lifecycle, and `RtcSession` manages WebRTC and screen capture.
- `web/`: desktop client without npm runtime dependencies. Test pages are not deployed.
- `server/`: signaling service without third-party runtime dependencies. Rooms are in memory and end when the service restarts.
- `deploy/`: signaling, web proxy, and TURN configuration templates.
- `docs/`: implementation research and verification records.

The prototype supports two participants and one shared screen. It attempts recovery after network loss; long outages or expired state require a new room. Protected windows may appear black. Resolution and FPS ceilings are not guaranteed on every device/network. WebRTC congestion control and degradation preferences adapt quality; there is no separate thermal-management policy.

## Dependencies and references

The Android client uses `io.github.webrtc-sdk:android:144.7559.15`, AndroidX, and Kotlin. Public architecture references include ScreenStream, LiveKit, and scrcpy. The implementation does not copy RustDesk AGPL source. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). First-time builds require access to Google Maven, Maven Central, and Gradle distributions.

## Android emulators

Use Android Studio's Device Manager to install the official emulator and Android system images and create two independent AVDs. Emulators can reach the host's local service at `http://10.0.2.2:8080`; configure this address in the app yourself.

`scripts/start-emulator.sh` is a development helper that expects an SDK at `.tools/android-sdk`, AVD data at `.tools/avds`, and pre-created `codex-screenshare-host` and `codex-screenshare-viewer` devices. These tools and device files are not distributed with the repository. Emulator tests do not replace physical-device performance, echo cancellation, Bluetooth routing, or mobile-network validation.
