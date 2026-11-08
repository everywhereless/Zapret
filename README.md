# Zapret Mobile

[![Android CI](https://github.com/lolososka/zapret-control-center-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/lolososka/zapret-control-center-android/actions/workflows/android-ci.yml)
[![License: GPL v3](https://img.shields.io/badge/license-GPLv3-151515.svg)](LICENSE)

An Android app by **lolososka** in the style of Zapret Control Center. It starts a local Android VPN interface and routes traffic through the [ByeDPI fork](https://github.com/lolososka/byedpi) built into the APK. No separate app or remote VPN server is launched, and root access is not required.

> 0.4.0 — a monochrome interface in the style of the PC version, a fast Telegram proxy, and a dedicated selection for YouTube/Discord. Update notifications and built-in downloading become available after verifying the subscription to @Slag0dworld. How well the bypass works on a specific phone and with a specific provider needs to be verified.

The "Messengers" profile helps connections with unstable UDP, while "Game traffic" enables conservative UDP handling that is not tied to any particular service.

## What you need to know

### New in 0.4.0

- Black and light monochrome themes in the style of the PC version; the icon changes along with the theme.
- The Telegram proxy sits above the main button. The switch starts/stops
  it, and "Connect" immediately opens the setup in Telegram. Additional actions are
  available via long press. The state is updated from the service without reopening the screen.
- A separate "Telegram proxy" tile in the quick settings panel and actions in the notification of the running proxy.
- Quick check and selection for YouTube, Discord, or both services. HTTP
  after TLS and UDP DNS are checked through SOCKS5, not just whether TCP/TLS opens. HTTP redirects are not
  counted as success. This is not a check of loading a real video or a voice session.
- The "Split" and "TLS without spoofing" profiles do not add fake UDP packets.
  The selection evaluates the actual UDP result, not whether spoofing is enabled in the settings.
- Matched MTU for the Android VPN and the native tunnel ("VPN packet size (MTU)"): 1500 by default,
  with 1400/1280 options in the settings. A change is applied after reconnecting.
- A cancel button for the selection; on error or cancellation, the user's configuration is not reset.
- The home screen shows the native VPN counter, not the phone's total traffic.
  The background direct ping has been removed: it did not show whether the bypass was reachable.

To prepare a separate APK for preview that does not replace the working app:
`gradlew.bat testDebugUnitTest lintDebug assembleDebug -PsideBySidePreview=true`.
The package is `io.github.lolososka.zapretmobile.preview`, the name is "Zapret Preview", with a debug signature.
Preview is not a public update and does not replace the installed stable version.
On Windows, the native forwarding headers are expanded only into `app/build/generated/windowsJni`.

### Verification limitations

YouTube uses real media servers that differ from the HTTP test node.
Discord voice establishes a separate UDP connection; a successful UDP DNS check does not confirm
the availability of a specific voice server. Before a release, check video and
calls on the target phone over both Wi-Fi and mobile network.
References: [Android VPN](https://developer.android.com/reference/android/net/VpnService.Builder),
[Discord voice](https://docs.discord.com/developers/topics/voice-connections).

- Android 5.0 and newer is supported (`minSdk 21`);
- processing happens on the device; the project has no remote VPN server of its own;
- the app does not change your external IP address and does not add encryption on top of HTTPS;
- Android shows the system VPN icon because `VpnService` is used to redirect traffic;
- usually only one VPN app can run at a time, so conflicts with AdGuard, other VPNs, and network filters are possible;
- the suitable strategy depends on the network and the provider.

## Installation

Signed APKs are published in [GitHub Releases](https://github.com/lolososka/zapret-control-center-android/releases) and on the [Zapret Control Center website](https://lolososka.github.io/zapret-discord-youtube/#android). Download the APK to your phone, allow installation from the browser you chose, open the app, and confirm the system VPN prompt on the first connection.

A SHA-256 checksum and a source code archive with submodules are attached to every release. The debug APK from Actions is intended for development and is signed with a different key.

## Building from source

The current project deliberately keeps the proven stack of the original fork:

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 8.3.0 |
| Gradle Wrapper | 8.4 |
| Kotlin | 1.9.22 |
| compileSdk / targetSdk | 34 / 34 |
| Android NDK | 26.1.10909125 |
| CMake | 3.22.1 |
| Java for Gradle | 17 |

You will need Android SDK 34, Android NDK 26.1.10909125, and CMake 3.22.1. The repository must be cloned together with its submodules:

```powershell
git clone --recurse-submodules https://github.com/lolososka/zapret-control-center-android.git
cd zapret-control-center-android
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

The debug APK will appear in `app/build/outputs/apk/debug/`.

The procedure for preparing a public release is described in [RELEASING.md](RELEASING.md). It separates verification debug builds from signed releases.

## Project origin

This is an independent fork of [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid), which is developed by **dovecoteescapee**. The Git history is preserved, and the `upstream` remote is used to sync carefully with the original.

Main components:

- [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) — the original Android implementation;
- the [ByeDPI fork](https://github.com/lolososka/byedpi) based on [hufrea/byedpi](https://github.com/hufrea/byedpi) — the built-in local SOCKS5 proxy and DPI desynchronization;
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — the tunnel between the Android VPN and SOCKS5.

The name Zapret Mobile refers to this fork. The app is not an official client of the ByeDPI, ByeDPIAndroid, or `bol-van/zapret` projects and does not contain the Windows WinDivert components.

## Privacy

The app has no account, analytics, or project telemetry. Regular network traffic still goes to the sites and services the user opens. Before installing a third-party build, compare its source and SHA-256 with the official release.

## License and acknowledgments

The app's main code and the changes in this fork are distributed under the [GNU General Public License v3](LICENSE). The copyrights of the original developers are preserved. The list of bundled components and their licenses is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Bugs and suggestions are accepted in [Issues](https://github.com/lolososka/zapret-control-center-android/issues). Report security issues following the instructions in [SECURITY.md](SECURITY.md).

