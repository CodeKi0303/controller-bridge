# Third party notices

Controller Bridge is distributed as a whole under GPL-3.0-or-later. Original project code and documentation: Copyright (c) 2026 CodeKi0303. Existing third-party rights, notices, and licenses remain in effect. This notice does not license proprietary game applications or controller firmware; neither is distributed with this project.

## Xbox report descriptor from xpadneo

`app/src/main/java/dev/codek/xboxprobe/core/HidProfile.java` contains the Xbox One S Windows-mode report descriptor recorded by the xpadneo project, from commit `7213c9a4a67c4f41be4179d1f28602c221a01adb`:

https://github.com/atar-axis/xpadneo/blob/7213c9a4a67c4f41be4179d1f28602c221a01adb/docs/descriptors/xb1s_windows.md

That source document explicitly declares `GPL-3.0-or-later`. The source document does not identify an individual copyright holder for the capture; attribution is retained to the xpadneo contributors. This project follows that stated license without making a separate claim about the copyright status of captured protocol facts.

The captured descriptor was represented as a Java hexadecimal constant, its final `00` byte was omitted because it is not a HID item, and a source annotation's logical-maximum error was noted. The descriptor's origin is not claimed as original project work. These adaptations are present in this project's 2026 release. See `LICENSE` and the APK asset `licenses/GPL-3.0.txt` for the license text. No xpadneo kernel driver code or binary is bundled.

## Shizuku API 13.1.5

Runtime artifacts: `dev.rikka.shizuku:api`, `provider`, `aidl`, and `shared`, each version `13.1.5`.

Copyright (c) 2021 RikkaW. Licensed under the MIT License; the full notice is included at `app/src/main/assets/licenses/Shizuku-API-MIT.txt`. The versioned Maven POMs identify this license. Source: https://github.com/RikkaApps/Shizuku-API . The Shizuku manager application is a separate application installed by the user and is not bundled here.

### Standalone helper reference provenance

The standalone bootstrap and ownership code was authored for this application after consulting Shizuku and AOSP; it is not represented as a clean-room implementation. `StandaloneHelperMain`'s shell-process initialization was informed by Shizuku API's `server-shared/.../UserService.java` (MIT). `ShellProviderHandoff`'s external-provider acquisition/release and Android-version call signatures were informed by Shizuku's `server/.../ShizukuService.java` and `server/.../api/IContentProviderUtils.java` (Apache-2.0), then checked against Android 11/12 framework interfaces. Shizuku attribution remains with RikkaW and the respective contributors. The implementation uses application-specific reflection, one-use admissions and exact app-UID checks; it does not bundle the Shizuku manager/server application or copy its complete source files.

Reference locations: https://github.com/RikkaApps/Shizuku-API/blob/master/server-shared/src/main/java/rikka/shizuku/server/UserService.java ; https://github.com/RikkaApps/Shizuku/blob/master/server/src/main/java/rikka/shizuku/server/ShizukuService.java ; https://github.com/RikkaApps/Shizuku/blob/master/server/src/main/java/rikka/shizuku/server/api/IContentProviderUtils.java . MIT and Apache-2.0 license texts are included in the APK. This provenance explanation was added in 0.7.1; the application does not use Shizuku's name, launcher icon or reserved application ID as its own identity.

## AndroidX annotations

The explicit compile-only dependency remains `androidx.annotation:annotation:1.3.0`; the WindowManager runtime dependency graph resolves `androidx.annotation:annotation` and `annotation-jvm` to `1.7.0`. Both retained versions use Apache-2.0. Copyright belongs to The Android Open Source Project and the contributors identified in the original source headers. The full license is included at `app/src/main/assets/licenses/Apache-2.0.txt`; versioned source archives and digests are retained under `third_party/sources/`.

## Jetpack WindowManager and adaptive layout dependencies

The adaptive screen uses the official Java callback API in `androidx.window:window-java:1.3.0`, with `androidx.core:core:1.8.0` explicitly included for its public `Consumer` type. This pinned WindowManager version supports the existing Android SDK 35 build and ordinary `Activity` hosts. No OEM extension API is accessed through application-authored reflection. WindowManager reports separating folds and occluding hinges when supported by the device; ordinary measured-window layout is the fallback when the platform provides no folding feature.

The resolved runtime closure includes WindowManager `1.3.0`, Window Extensions Core `1.0.0`, AndroidX Core `1.8.0`, Collection `1.1.0`, Concurrent Futures `1.0.0`, Lifecycle `2.3.1`, Architecture Core `2.1.0`, VersionedParcelable `1.1.1`, Annotation `1.7.0`, Annotation Experimental `1.1.0`, Kotlin standard library/common `1.8.22` and JDK compatibility artifacts `1.8.20`, kotlinx.coroutines `1.7.3`, JetBrains annotations `23.0.0`, and the standalone Guava ListenableFuture `1.0` interface. All these artifacts declare Apache-2.0; Guava's license is inherited from its retained `26.0-android` parent POM. The Kotlin compiler and full Guava library are not bundled.

The 24 resolved coordinates, including metadata selectors and the coroutines BOM, are recorded in `third_party/sources/window-runtime-manifest.json`. Published source JAR contents are retained unchanged, with exact archive, POM and source-file SHA-256 digests. Original per-file copyright and license headers remain intact. APK assets `licenses/WindowManager-Runtime-NOTICE.txt`, `Kotlin-Apache-2.0.txt`, `Coroutines-Apache-2.0.txt`, `JetBrains-Annotations-LICENSE.txt`, and the shared `Apache-2.0.txt` retain the notices. AndroidX attribution belongs to The Android Open Source Project and its contributors; Kotlin/coroutines/annotations attribution belongs to JetBrains and the contributors named in their sources; ListenableFuture retains Copyright (C) 2007 The Guava Authors.

Primary API and release references: https://developer.android.com/jetpack/androidx/releases/window#1.3.0 and https://developer.android.com/develop/ui/views/layout/foldables/make-fold-aware . The versioned `WindowInfoTrackerCallbackAdapter.kt` source is retained with the dependency. These references and automated geometry tests do not establish physical foldable-device validation.

## Wireless ADB pairing and SPAKE2

`AdbPairingCrypto` and `AdbPublicKey` adapt the pairing envelope and RSA public-key encoding from libadb-android 3.1.1, commit `c849886ebc6d48e7b46d967e78a6bb65c90c3b74`, using its Apache-2.0 licensing option. The original files and acquisition digests are retained under `third_party/adb-crypto/`. JCA replaces BouncyCastle; the app does not bundle the full upstream ADB client. See https://github.com/MuntashirAkon/libadb-android .

The SPAKE2 native module uses spake2-c commit `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31`, pinned by spake2-java 2.2.1, under LGPL-3.0-or-later with its retained BoringSSL/ISC, public-domain ref10, Apache-2.0, and LGPL source notices. The complete modified native source and build recipe are in `app/src/main/cpp/`. The application as a whole remains GPL-3.0-or-later. Changes replace upstream random generation with Java SecureRandom entropy, remove native logging, check allocation failures, clear context memory, and support host JNI verification. Curve arithmetic is unchanged. The upstream Java implementation and upstream JNI wrapper are not included. See https://github.com/MuntashirAkon/spake2-c .

Original and modified hashes are recorded in `third_party/adb-crypto/spake2-source-manifest.json`. Full notices are in APK assets `ADB-CRYPTO-NOTICE.txt`, `SPAKE2-LGPL-3.0.txt`, `Apache-2.0.txt`, and `GPL-3.0.txt`. See `third_party/adb-crypto/README.md` for exact provenance, adaptation and test details. Desktop fixtures are independent reference calculations, not a claim of official AOSP vectors or a security audit.

## Conscrypt 2.8.0 and native dependencies

Runtime artifact `org.conscrypt:conscrypt-android:2.8.0` uses Conscrypt commit `7cff5d9ab2e4ed3f89957e34270b61d8a621c4b6`. Its published AAR identifies BoringSSL commit `427ec40cc8edc545253289232e885708c409e5ea`. Source: https://github.com/google/conscrypt and https://github.com/google/boringssl .

Conscrypt uses Apache-2.0 plus the third-party notices in its LICENSE and NOTICE, including Chromium, Apache Harmony and Netty material. The pinned BoringSSL release contains its own combined license, authors and fiat-crypto notices. The prebuilt JNI also carries Android LLVM runtime material; notices for libc++, libc++abi, libunwind and compiler-rt are retained from its identified LLVM revision. The exact notices are copied into APK `assets/licenses/` with `Conscrypt-`, `BoringSSL-`, and `NDK-` prefixes. No claim is made that this application authored these libraries.

`third_party/conscrypt/source-manifest.json` records Maven artifacts, embedded version evidence, upstream revisions, retained-file hashes and deliberate source omissions. The repository retains Java and production native/build sources; the unchanged full Conscrypt and BoringSSL source archives are separate release assets under `dependency-sources/`. Publish those two archives alongside the app source ZIP. Their names and hashes are in the manifest and release checksums. Native production-file coverage has been checked, but this project has not rebuilt or bit-reproduced the published Conscrypt AAR. Its Maven artifact is pinned for the normal app build.

## Protocol and operating-system references

`XboxReportEncoder.java` implements packet semantics corroborated by SDL `release-2.30.9`, specifically `src/joystick/hidapi/SDL_hidapi_xboxone.c`. This project does not bundle the SDL library or claim that its Java encoder is an SDL release. SDL: Copyright (C) 1997–2024 Sam Lantinga; zlib license text is retained in `licenses/SDL-zlib.txt` within the APK assets.

Linux v6.12 UAPI headers `include/uapi/linux/input.h`, `hid.h`, and `uhid.h` are references for event constants and packed user-space structures. Their notices include GPL-2.0 or GPL-2.0-or-later **with Linux-syscall-note**. The exception is included in `licenses/Linux-syscall-note.txt`, with the GPL-2.0 text. UHID header attribution: Copyright (c) 2012 David Herrmann. Input header attribution: Copyright (c) 1999–2002 Vojtech Pavlik. HID header attribution: Copyright (c) 1999 Andreas Gal, 2000–2001 Vojtech Pavlik, and 2006–2007 Jiri Kosina. The application's normal use of kernel system calls does not redistribute the kernel.

Android's `Vendor_045e_Product_02e0.kl` key layout and the USB HID specification are references for compatibility behavior. This project does not bundle their full documents, Android system binaries, controller firmware, or captured game APKs.

Test fixtures `app/src/test/resources/android-keylayouts/Vendor_054c_Product_09cc.kl` and `Vendor_054c_Product_0ce6.kl` reproduce Android Open Source Project key layout files under Apache-2.0. Their original copyright and license headers are retained. Pinned blob identities, source URLs and file digests are recorded in the adjacent `PROVENANCE.txt`. These fixtures are test resources and are not bundled in the APK.

## Build and test tools

Gradle wrapper scripts and `gradle-wrapper.jar` use Apache-2.0. Existing notices in the scripts and the JAR's `META-INF/LICENSE` are retained. Gradle and the Android build toolchain run during compilation and are not included in the APK.

JUnit `4.13.2` uses EPL-1.0 and Hamcrest Core `1.3` uses BSD licensing. They are test-only dependencies, not APK runtime libraries. Their original distributions contain their license notices. No JUnit or Hamcrest binary is committed in this source repository.

## Corresponding source and distribution

An APK release must be accompanied by access to the exact corresponding project source and build files for that version, under GPL-3.0-or-later. Publish the app source ZIP and both full dependency source archives beside the APK; retain dependency versions and source locations. `third_party/sources/` contains the versioned source files for the bundled Shizuku API, provider, aidl, shared, AndroidX annotation artifacts, and the WindowManager runtime closure, with acquisition digests and notices. These copies are retained as corresponding source; Gradle continues to resolve pinned Maven artifacts when building. The release does not depend on an unavailable private research workspace. Private signing keys are not part of the source archive.

The application includes these notices and license texts in `assets/licenses/`. Preserve their attributions when redistributing. Product names and identifiers do not grant trademark rights or imply endorsement.
