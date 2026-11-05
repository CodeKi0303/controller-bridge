# WindowManager dependency source provenance

`../window-runtime-manifest.json` records the resolved runtime source closure added
for `androidx.window:window-java:1.3.0` and `androidx.core:core:1.8.0`. The versions
match the application's Gradle `debugRuntimeClasspath`, including multiplatform
selector modules and the coroutines BOM. Metadata-only entries are identified.

Sources were obtained from the versioned Google Maven repository and Maven Central
URLs recorded in the manifest. Published source JARs were expanded without changing
file bytes. Source archives remain reproducible from their URLs and SHA-256 digests;
expanded source files and POMs are distributed in the project source tree. No
dependency binaries are committed here. Gradle continues to build against the
pinned published artifacts.

All 24 coordinates declare Apache-2.0 licensing. The ListenableFuture POM inherits
its license from `com.google.guava:guava-parent:26.0-android`; that parent POM is
retained in the same directory and its acquisition digest is recorded. Full
upstream Kotlin, kotlinx.coroutines and JetBrains annotations license texts are
retained here and in the APK. AndroidX and Guava use the shared Apache-2.0 license
text already included in APK assets. Original per-file attributions remain in
the source copies, and the APK notice collects their copyright statements.

The callback APIs used by `AdaptiveScreenLayout` were checked against the actual
WindowManager 1.3.0 source archive. They accept an ordinary Android `Activity`,
an `Executor`, and `androidx.core.util.Consumer<WindowLayoutInfo>`. The application
does not bundle Kotlin's compiler or the full Guava library. The sources are retained
for redistribution, not compiled directly by the application build.
