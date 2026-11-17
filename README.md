# RelayView

RelayView is a local Android viewer for CP Plus Orange-series DVRs. Open the app on the recorder’s network, select the discovered device, and sign in with a DVR account. The app opens a grid of live substreams. Tap a camera for its main stream in landscape, then tap the picture again or use Android Back to return to the grid.

## Where this started

This project began with an eight-channel CP Plus recorder. Its web viewer displayed four channels at a time. Five inputs showed camera pictures and the other three returned black video with advancing timestamps. The goal was to see every configured channel from a phone, use less bandwidth in the overview, and open one camera at full quality without entering the DVR login again.

Earlier experiments used JPEG snapshots and YOLOX Nano to detect people and vehicles in Python. RelayView 0.2 runs the same model directly on Android and can alert while the live viewer is closed.

## Pair a recorder

1. Connect the phone to the same LAN as the recorder and open RelayView. Select a recorder from the scan results. If multicast is disabled on your network, use **Enter address manually**.
2. Enter your DVR username and password exactly as configured, including characters such as `@`. **Connection settings** exposes the HTTP and RTSP ports. The defaults are 80 and 554.
3. Tap **Pair and view cameras**. RelayView reads channel names from the protected channel directory. If the directory is unavailable, it verifies viewing access to the first substream and asks for the recorder's channel count.
4. Scroll the camera grid to see every input. Tap one tile to switch to the main stream. **Recorder → Change** pairs a different recorder. **Recorder → Forget** removes the saved pairing.

## Background detection

Tap **Start monitoring** on the camera grid and allow notifications. Keep the phone on a network that can reach the DVR. A foreground service continues sampling when you return to the home screen or turn off the display. **Stop monitoring** on the grid or **Stop** on the ongoing notification ends sampling. Pairing a different recorder or forgetting the current one also stops it.

Each configured channel gets a separate check targeting **one JPEG per second**, including channels outside the visible grid. Requests are staggered and fetched directly from the DVR's `/cgi-bin/snapshot.cgi?channel=N` endpoint using its saved Digest login. Up to eight fetches can run concurrently. A single local inference session analyzes frames in memory. Slow channels skip missed intervals. Throughput depends on the phone, Wi-Fi, channel count and DVR response time. Continuous monitoring uses battery.

The alert title is the **camera name** and its text reports **Person detected**, **Vehicle detected**, or both. The detector uses a 0.40 confidence threshold. Vehicle covers bicycle, car, motorcycle, airplane, bus, train, truck and boat. Each camera re-arms each category independently after three analyzed frames without that category. Connection failures do not count as clear frames. The ongoing notification shows how many cameras are responding and flags unavailable or access-denied snapshots. Rejected channels retry after one minute.

The DVR account needs permission to fetch JPEG snapshots. Android notification settings control alert sound and lock-screen visibility. The model and inference runtime ship in the APK. Frames stay in phone memory and are discarded after analysis.

## Compatibility and privacy

- Android 8.0 (API 26) or newer. The app targets API 36. For devices enforcing local-network permission on a future target, review Android's local-network access guidance before updating the target.
- Discovery sends ONVIF WS-Discovery probes over the local Wi-Fi or Ethernet interface and recognizes CP Plus scope or hardware names. It uses the source of the reply when a recorder advertises an empty ONVIF hostname. Manual pairing accepts an IPv4 address.
- The CP Plus adapter uses the `ChannelTitle` CGI directory and `/cam/realmonitor` RTSP endpoint. The grid requests `subtype=1` and the focused view requests `subtype=0`. Enable both streams on the recorder. Firmware and product lines vary.
- The grid opens visible substreams over RTSP/TCP and releases off-screen players. The main view requests hardware decoding where available. Stream capacity depends on the recorder, phone, codec and resolution. A DVR can deliver a valid black video stream for an empty analog input.
- Host, ports, username, password, and channel names are encrypted together using AES-256-GCM. The encryption key is held in Android Keystore. Backups are disabled. Recorder credentials are passed to the player separately from the stream URL. Streams run directly between phone and recorder and stop when the viewer leaves the foreground.
- HTTP Digest and ordinary RTSP provide authentication on a trusted LAN. The video transport itself is unencrypted.

## Build and test

Open the project in Android Studio or configure a JDK 17+ and Android SDK Platform 36. The Gradle wrapper pins Gradle 9.3.1. The first build fetches the official YOLOX Nano ONNX release, verifies its SHA-256, and packages it in the APK. From the repository root, run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use `gradlew.bat`. Debug APKs appear under `app/build/outputs/apk/debug/` by CPU architecture. Most current Android phones use `arm64-v8a`. Select the APK matching the device and install through Android Studio or `adb install -r <apk>`.

The tests use synthetic recorder replies and loopback HTTP/RTSP servers. They cover discovery with a malformed advertised host, sparse channel names, Digest authentication, credential rejection, and stream quality selection. To check a physical installation, pair on the recorder's LAN, cold-launch the app to check the saved pairing, then switch between the grid and a main stream.

Monitoring tests cover category filtering, re-arming, Digest JPEG requests, recovery after missed snapshots, slow-channel isolation and shutdown. In a screen-off Android 15 emulator test with eight simulated DVR channels, Android posted a detection notification for each camera name. Median per-channel snapshot intervals ranged from 1.001 to 1.003 seconds during the test. Stopping monitoring stopped requests. Physical DVR and phone performance will vary.

## Source layout

| Component | Responsibility |
| --- | --- |
| `Discovery` | ONVIF multicast probes and CP Plus identification |
| `CpPlus`, `Digest` | Recorder login, channel enumeration, and stream addressing |
| `Vault`, `Recorder` | Encrypted saved pairing and channel metadata |
| `MainActivity` | Scan, pair, manage the recorder, and display the grid |
| `CameraTile`, `ViewerActivity` | LibVLC playback, retry, lifecycle, and landscape viewing |
| `MonitorService`, `CameraChecks`, `Snapshots` | User-started background sampling, DVR authentication, and local notifications |
| `SnapshotDetector`, `YoloModel`, `Presence` | JPEG preprocessing, CPU inference, and per-camera detection events |

A future recorder adapter can supply its own discovery identifiers, channel list, and main/substream addresses while reusing the vault and viewing screens.

## References

[CP Plus Orange-series downloads](https://cpplusworld.com/orange-series/index.html) · [ONVIF specifications](https://www.onvif.org/profiles/specifications/) · [VideoLAN Android libraries](https://code.videolan.org/videolan/vlc-android) · [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

Model and runtime: [YOLOX Nano](https://github.com/Megvii-BaseDetection/YOLOX/tree/main/demo/ONNXRuntime) · [ONNX Runtime Mobile](https://onnxruntime.ai/docs/tutorials/mobile/).
