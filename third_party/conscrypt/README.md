# Conscrypt 2.8.0 release material

The application resolves `org.conscrypt:conscrypt-android:2.8.0` from Maven Central. This directory preserves its published Java sources, pinned Conscrypt JNI/build sources, pinned BoringSSL production sources, and notices. These copies are not added to the app's source sets. No AAR, JAR, native library, application signing key, or other compiled dependency is retained here. Public upstream test-support files may contain published example keys; they are not application credentials.

| Component | Exact revision / provenance |
| --- | --- |
| Conscrypt Android | `2.8.0`, Git commit `7cff5d9ab2e4ed3f89957e34270b61d8a621c4b6` |
| BoringSSL | `427ec40cc8edc545253289232e885708c409e5ea`, annotated tag `0.20260929.0` |
| Android LLVM runtime notices | `d8003a456d14a3deb8054cdaa529ffbf02d9b262` |

The published AAR's `classes.jar!/org/conscrypt/conscrypt.properties` records BoringSSL's exact commit. Its four JNI libraries cover arm64-v8a, armeabi-v7a, x86, and x86_64. The arm64 library's compiler identification records the Android LLVM commit above. The AAR does not include a LICENSE or NOTICE entry. `source-manifest.json` records the AAR digest, each native-entry digest, source acquisition URLs/digests, every retained upstream file, and every omitted upstream file. `metadata/conscrypt.properties` preserves the version evidence.

## Notices to ship in the APK

Copy all files in `notices/` to the APK's license assets. Preserve the original text. Conscrypt's root LICENSE includes Apache-2.0 and the Chromium blocklist notice; its NOTICE identifies the Android Open Source Project and the adapted Netty and Apache Harmony portions. Exact BoringSSL LICENSE/AUTHORS and fiat-crypto notices are retained. Android NDK libc++, libc++abi, libunwind, and compiler-rt notices cover the C++/compiler runtime used by the native toolchain. These component licenses do not replace the application's GPL-3.0-or-later license.

The BoringSSL root license at this revision is Apache-2.0 and also retains a Go test-support license. The public-domain SUPERCOP attribution remains in `boringssl/crypto/poly1305/poly1305_arm_asm.S`. Source copyright notices and generator/perlasm notices remain with their original files. `third_party/googletest`, `third_party/benchmark`, and other retained source-only support code keep their own licenses; their presence in the source tree is not a claim that those test libraries are linked into the APK.

## Coverage and verification limit

`maven-sources/` contains all 211 files of the published source JAR without edits. `conscrypt/` contains 418 upstream text/source/build files. `boringssl/` contains 1,978 upstream text/source/build files, including generated production assembly, headers, perlasm generators, `util/pregenerate`, CMake lists, and `build.json`.

The reduced trees omit binary fixtures/tools, test vectors/corpora, fuzz data, most test-resource data, Conscrypt test source sets, and upstream assistant instructions. Every omission is enumerated with its original digest and reason. They are not complete upstream checkouts, and the reduced trees must not be described as independently verified complete corresponding source or a way to run the upstream test suite.

`verify_sources.py` checks retained file bytes and confirms that all 765 files listed in the selected BoringSSL production CMake source/header/assembly lists and all five Conscrypt JNI translation units exist. This is static source coverage, not compilation or bit-for-bit reproduction. The published Conscrypt AAR has not been rebuilt during this release preparation.

Full unchanged upstream source archives are prepared under `dist/dependency-sources/` (ignored by Git). **Publish both archives alongside the app source archive**, or include them inside that release source package. Their exact official acquisition URLs are also in the manifest:

| Archive | SHA-256 |
| --- | --- |
| `conscrypt-2.8.0-7cff5d9ab2e4ed3f89957e34270b61d8a621c4b6-full-source.zip` | `58b03350ffa3952093766f16d0ab662e34483b868e8a907875919b716974ca76` |
| `boringssl-427ec40cc8edc545253289232e885708c409e5ea-full-source.zip` | `fb8641640878f526dbb17622b2e6c95bfc9d6d73a08349115028215827e38e11` |

## Rebuilding the Android dependency

Use the full archives/checkouts for upstream Gradle builds. The tag's files pin Gradle 7.5, AGP 7.4.2, Android compile SDK 26, NDK `27.3.13750724`, and CMake `3.22.1`; `conventions.jvm.gradle` selects JDK 11 and Java 8-compatible output. Supply the ordinary SDK/NDK, CMake/Ninja, Java, and host C++ toolchains yourself. The app's own NDK setting is separate from the toolchain used to publish Conscrypt.

The upstream BoringSSL Gradle convention reads the Git HEAD to generate `conscrypt.properties`. For the unmodified upstream build, use a checkout at the exact commit, not an arbitrary synthetic Git history around an extracted archive:

```sh
git clone https://github.com/google/conscrypt.git conscrypt-full
git -C conscrypt-full checkout --detach 7cff5d9ab2e4ed3f89957e34270b61d8a621c4b6
git clone https://github.com/google/boringssl.git boringssl-full
git -C boringssl-full checkout --detach 427ec40cc8edc545253289232e885708c409e5ea
export BORINGSSL_HOME="$PWD/boringssl-full"
# Set ANDROID_HOME to your configured Android SDK before this command.
cd conscrypt-full
./gradlew :conscrypt-android:assembleRelease
```

For a production JNI-only rebuild from the reduced retained trees, disable tests and fuzzing explicitly. This command follows the retained Android CMake target and requires the NDK/CMake toolchain above; it has not been executed as part of this source audit:

```sh
# Run from third_party/conscrypt; set ANDROID_NDK_HOME to NDK 27.3.13750724.
cmake -S conscrypt/android -B build/arm64-v8a -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
  -DANDROID_STL=c++_static -DCMAKE_CXX_STANDARD=17 \
  -DBORINGSSL_HOME="$PWD/boringssl" -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_TESTING=OFF -DFUZZ=OFF \
  '-DCMAKE_SHARED_LINKER_FLAGS=-z max-page-size=16384 -z common-page-size=16384'
cmake --build build/arm64-v8a --target conscrypt_jni
```

Repeat with the other three ABI names if needed. This creates JNI libraries, not a replacement Maven AAR. To publish a rebuilt AAR, use the full upstream Gradle project and preserve version/source metadata. Neither command above is a claim that the output will match the upstream binary digest: compiler options, generated version properties, paths, timestamps, and environment can affect the bytes.

Run the local source verification from the repository root:

```sh
python third_party/conscrypt/verify_sources.py
```
