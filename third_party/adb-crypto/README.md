# Embedded wireless ADB sources

The app uses its own bounded, TLS-only client, fixed to `127.0.0.1`. It exposes
only the backend-generated bootstrap command. It does not embed a general ADB
terminal or accept remote host addresses. The user explicitly enters the
Android wireless-debugging pairing code, pairing port, and connection port.

`AdbPairingCrypto` and `AdbPublicKey` adapt libadb-android 3.1.1
([commit c849886](https://github.com/MuntashirAkon/libadb-android/tree/c849886ebc6d48e7b46d967e78a6bb65c90c3b74))
under its Apache-2.0 option. Original corresponding source is retained in
`upstream-libadb`; `source-manifest.json` records its digests. JCA replaces
BouncyCastle for HKDF and AES-GCM. No full libadb transport is included.

SPAKE2 uses the C implementation pinned by spake2-java 2.2.1:
[spake2-c 0d15933](https://github.com/MuntashirAkon/spake2-c/tree/0d15933e5ba3e662cb01245a7ac0dc9fca3eac31).
Complete modified corresponding source is in `app/src/main/cpp/spake2`, built
by `Android.mk`; no precompiled third-party binary is vendored. Its combined
license is LGPL-3.0-or-later, with ISC-style BoringSSL, public-domain ref10,
Apache-2.0, and LGPL-2.1-or-later source notices retained. The LGPL-3.0 option
is used for LGPL portions. See the APK's `ADB-CRYPTO-NOTICE.txt`,
`SPAKE2-LGPL-3.0.txt`, and `GPL-3.0.txt` assets.

`spake2-source-manifest.json` records original and modified file digests.
Modifications replace upstream `rand()` with 64 bytes supplied by Java
`SecureRandom`, check allocation failures, erase the context on release,
remove native error logging, and allow Windows Clang host tests. Curve math
is unchanged. `AdbSpake` and `adb_spake_jni.c` are new bounded ownership
wrappers; upstream JNI and the upstream pure-Java implementation are not used.

TLS is pinned to `org.conscrypt:conscrypt-android:2.8.0` (Apache-2.0 plus its
included native notices). The private identity is a non-exportable Android
Keystore RSA key. Conscrypt 2.8.0's
[CryptoUpcalls](https://github.com/google/conscrypt/blob/2.8.0/common/src/main/java/org/conscrypt/CryptoUpcalls.java)
delegates opaque RSA-PSS signing via a raw RSA operation. The key permits
`DIGEST_NONE` and `ENCRYPTION_PADDING_NONE` for that operation; Android's
Keystore RSA implementation maps private-key encryption to signing. Before
network access, the app performs the same raw private operation and public
recovery with a non-secret test block. This catches incompatible OEM key
providers early; it does not establish Samsung TLS integration without a
phone test. There is no plaintext private-key export/fallback.

adbd uses self-signed certificates. Certificate-chain trust is deliberately
not the authentication boundary here: pairing uses the code plus TLS exporter
in SPAKE2, transport is restricted to device loopback, and success additionally
requires the separate one-use Binder handoff from UID 2000. The connection
does not pin an adbd certificate. This code has not had an independent security
audit. Native ABI loading, actual Android pairing, Keystore, and bootstrap need
real-device verification.

## Reproducing tests

The normal JVM suite runs framing/RSA/HKDF tests; `AdbNativeCryptoTest` explicitly
skips unless a host JNI library is provided. Android `.so` files cannot be
loaded by a desktop JVM. On Linux with a JDK and C compiler:

```sh
bash scripts/build-adb-host-test-library.sh
./gradlew testDebugUnitTest -PadbNativeTestDir="$PWD/.tools/adb-host-tests"
python3 scripts/adb-pairing-reference.py
```

On Windows, compile the same three C files with a C compiler supporting JNI
DLLs, include `$JAVA_HOME/include` and `include/win32`, and name the output
`bridgespake.dll`. Pass its absolute parent directory with `adbNativeTestDir`.
Local verification used Zig 0.15.2 `cc -shared -O2 -std=c11`, not an app runtime
dependency. No host binaries or downloaded toolchains belong in Git/releases.

The deterministic native test compares both messages and the 64-byte shared
key to `scripts/adb-pairing-reference.py`, a separate arbitrary-precision
Edwards25519/transcript implementation. Its public fixture entropy/password
come from upstream tests. It also verifies the derived AES key. These are
independent desktop reference fixtures, **not official AOSP golden vectors**;
the pinned AOSP test itself has a TODO for fixed vectors. Random round trips,
wrong secrets, replay, corrupted GCM tags, context lifetime, RSA public-key
layout, fragmented reads, malformed lengths/checksums, and refusal of legacy
plaintext ADB are covered. Passing host tests does not establish adbd/OEM
interoperability or an independent cryptographic audit.
