# RelayView

RelayView is a local Android viewer for CP Plus Orange-series DVRs. Open the app on the recorder’s network, select the discovered device, and sign in with a DVR account. The app opens a grid of live substreams. Tap a camera for its main stream in landscape, then tap the picture again or use Android Back to return to the grid.

## Where this started

This project began with an eight-channel CP Plus recorder. Its web viewer displayed four channels at a time. Five inputs showed camera pictures and the other three returned black video with advancing timestamps. The goal was to see every configured channel from a phone, use less bandwidth in the overview, and open one camera at full quality without entering the DVR login again.

Earlier experiments used JPEG snapshots and a small Python model to detect people and vehicles and measure response time. This Android release covers discovery, pairing, and live viewing. On-device detection and background notifications are possible follow-up projects.

## Pair a recorder

1. Connect the phone to the same LAN as the recorder and open RelayView. Select a recorder from the scan results. If multicast is disabled on your network, use **Enter address manually**.
2. Enter your DVR username and password exactly as configured, including characters such as `@`. **Connection settings** exposes the HTTP and RTSP ports. The defaults are 80 and 554.
3. Tap **Pair and view cameras**. RelayView reads channel names from the protected channel directory. If the directory is unavailable, it verifies viewing access to the first substream and asks for the recorder's channel count.
4. Scroll the camera grid to see every input. Tap one tile to switch to the main stream. **Recorder → Change** pairs a different recorder. **Recorder → Forget** removes the saved pairing.

## Compatibility and privacy

- Android 8.0 (API 26) or newer. The app targets API 36. For devices enforcing local-network permission on a future target, review Android's local-network access guidance before updating the target.
- Discovery sends ONVIF WS-Discovery probes over the local Wi-Fi or Ethernet interface and recognizes CP Plus scope or hardware names. It uses the source of the reply when a recorder advertises an empty ONVIF hostname. Manual pairing accepts an IPv4 address.
- The CP Plus adapter uses the `ChannelTitle` CGI directory and `/cam/realmonitor` RTSP endpoint. The grid requests `subtype=1` and the focused view requests `subtype=0`. Enable both streams on the recorder. Firmware and product lines vary.
- The grid opens visible substreams over RTSP/TCP and releases off-screen players. The main view requests hardware decoding where available. Stream capacity depends on the recorder, phone, codec and resolution. A DVR can deliver a valid black video stream for an empty analog input.
- Host, ports, username, password, and channel names are encrypted together using AES-256-GCM. The encryption key is held in Android Keystore. Backups are disabled. Recorder credentials are passed to the player separately from the stream URL. Streams run directly between phone and recorder and stop when the viewer leaves the foreground.
- HTTP Digest and ordinary RTSP provide authentication on a trusted LAN. The video transport itself is unencrypted.

## Build and test

Open the project in Android Studio or configure a JDK 17+ and Android SDK Platform 36. The Gradle wrapper pins Gradle 9.3.1. From the repository root, run:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows, use `gradlew.bat`. Debug APKs appear under `app/build/outputs/apk/debug/` by CPU architecture. Most current Android phones use `arm64-v8a`. Select the APK matching the device and install through Android Studio or `adb install -r <apk>`.

The tests use synthetic recorder replies and loopback HTTP/RTSP servers. They cover discovery with a malformed advertised host, sparse channel names, Digest authentication, credential rejection, and stream quality selection. To check a physical installation, pair on the recorder's LAN, cold-launch the app to check the saved pairing, then switch between the grid and a main stream.

## Source layout

| Component | Responsibility |
| --- | --- |
| `Discovery` | ONVIF multicast probes and CP Plus identification |
| `CpPlus`, `Digest` | Recorder login, channel enumeration, and stream addressing |
| `Vault`, `Recorder` | Encrypted saved pairing and channel metadata |
| `MainActivity` | Scan, pair, manage the recorder, and display the grid |
| `CameraTile`, `ViewerActivity` | LibVLC playback, retry, lifecycle, and landscape viewing |

A future recorder adapter can supply its own discovery identifiers, channel list, and main/substream addresses while reusing the vault and viewing screens.

## References

[CP Plus Orange-series downloads](https://cpplusworld.com/orange-series/index.html) · [ONVIF specifications](https://www.onvif.org/profiles/specifications/) · [VideoLAN Android libraries](https://code.videolan.org/videolan/vlc-android) · [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
