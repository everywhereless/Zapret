# Third-Party Components and Copyright

Zapret Mobile is a fork of the open-source project [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid). This file does not replace the license texts. It helps connect components, authors, and the original distribution terms.

## Main project

- **ByeDPIAndroid** — the author and maintainer of the original project: [dovecoteescapee](https://github.com/dovecoteescapee).
- The original history and commit authorship are preserved in Git.
- The app code and the fork's changes are distributed under the GNU General Public License version 3; the full text is in [`LICENSE`](LICENSE).
- When distributing a modified APK, you must provide the corresponding source code of that version under the terms of GPL v3 and preserve the copyright notices.

## Bundled native components

| Component | Pinned revision | License | Where the text is located |
| --- | --- | --- | --- |
| [ByeDPI](https://github.com/lolososka/byedpi) (based on [hufrea/byedpi](https://github.com/hufrea/byedpi)) | `v0.13 + UDP patch` / `86348a4e1965039cb5df8aa5332154f50302679e` | MIT | [`app/src/main/cpp/byedpi/LICENSE`](app/src/main/cpp/byedpi/LICENSE) |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | `2.17.1` / `9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a` | MIT | [`app/src/main/jni/hev-socks5-tunnel/LICENSE`](app/src/main/jni/hev-socks5-tunnel/LICENSE) |
| hev-socks5-tunnel core | `1.6.4` / `162dd996299fc2d2bff2dd63728f8a2cd71ed31a` | MIT | [`app/src/main/jni/hev-socks5-tunnel/src/core/LICENSE`](app/src/main/jni/hev-socks5-tunnel/src/core/LICENSE) |
| [hev-task-system](https://github.com/heiher/hev-task-system) | `5.10.3` / `328f35d903221b51811b3d02b277d665dfbdc75f` | MIT | [`app/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/LICENSE`](app/src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/LICENSE) |
| [lwIP](https://savannah.nongnu.org/projects/lwip/) | `2.2.1.7` / `2a11c14c7a32887af25a034e82ef18b0b12076ac` | BSD 3-Clause | [`app/src/main/jni/hev-socks5-tunnel/third-part/lwip/LICENSE`](app/src/main/jni/hev-socks5-tunnel/third-part/lwip/LICENSE) |
| libyaml (part of the tunnel) | `0.2.5.2` / `efa36117a8646d26d12b58e05bac472d7854a70d` | MIT | [`app/src/main/jni/hev-socks5-tunnel/third-part/yaml/LICENSE`](app/src/main/jni/hev-socks5-tunnel/third-part/yaml/LICENSE) |
| KAVL header by Attractive Chaos | included in ByeDPI | MIT | [`app/src/main/cpp/byedpi/kavl.h`](app/src/main/cpp/byedpi/kavl.h) |
| [tg-ws-proxy](https://github.com/amurcanov/tg-ws-proxy-android) / Flowseal core | snapshot used by Zapret Mobile 0.2.0 | GPL-3.0 (core derived from MIT Flowseal code) | [`third_party/tg-ws-proxy/LICENSE`](third_party/tg-ws-proxy/LICENSE), [`third_party/tg-ws-proxy/src`](third_party/tg-ws-proxy/src) |

The exact revisions of the top-level submodules are pinned in the repository's Git tree. Nested revisions are pinned by the `hev-socks5-tunnel` tree.

## Android libraries

The build also uses the following libraries from Maven repositories:

- AndroidX Core, Fragment, AppCompat, Preference, and Lifecycle — Apache License 2.0;
- Material Components for Android — Apache License 2.0;
- PreferenceX — Apache License 2.0 according to the published Maven metadata;
- JUnit 4 — Eclipse Public License 1.0;
- AndroidX Test and Espresso — Apache License 2.0.

Their exact versions are declared in [`app/build.gradle.kts`](app/build.gradle.kts). The dependencies remain the property of their respective authors and are distributed under the terms of their own licenses.

## Updating this file

When updating a submodule or adding a dependency, before releasing the APK you must:

1. check the license file and copyright notices of the new revision;
2. update the table and the pinned version identifier;
3. make sure the required license texts are available together with the source code and the release;
4. not remove the original authors' notices.

