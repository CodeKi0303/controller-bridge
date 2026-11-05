# Runtime dependency sources

This directory contains expanded source files for the exact Maven runtime dependencies used by Controller Bridge. It is part of the corresponding source distribution. Gradle still resolves the pinned Maven artifacts during a normal build; these retained copies are not added to the application's compilation source set.

`manifest.json` records each source archive's URL, size, SHA-256, published POM digest, and expanded file hashes. Source files are retained without content edits. The source archives themselves and compiled dependency binaries are not committed.

| Artifact | Version | License |
| --- | --- | --- |
| `dev.rikka.shizuku:api` | 13.1.5 | MIT |
| `dev.rikka.shizuku:provider` | 13.1.5 | MIT |
| `dev.rikka.shizuku:aidl` | 13.1.5 | MIT |
| `dev.rikka.shizuku:shared` | 13.1.5 | MIT |
| `androidx.annotation:annotation` | 1.3.0 | Apache-2.0 |
| `org.conscrypt:conscrypt-android` | 2.8.0 | Apache-2.0 and retained notices; see below |

Conscrypt's exact Maven Java sources, pinned JNI/BoringSSL source material, separate manifest, native runtime notices, full-archive provenance, and build-verification limits are maintained in [`../conscrypt/`](../conscrypt/README.md). Its reduced retained tree is not a complete upstream checkout; publish the two full pinned upstream source archives described there beside the application's source archive.

Each artifact directory includes its original license and a `SOURCE-NOTICE.txt`. Keep the source directories and notices in the same release source archive as the application and its build files. JUnit and Hamcrest are test dependencies and are not bundled in the APK. Android platform libraries and the general-purpose compiler toolchain are supplied by the Android SDK/NDK environment.
