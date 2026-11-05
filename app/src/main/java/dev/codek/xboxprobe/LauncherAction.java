package dev.codek.xboxprobe;

import java.nio.charset.StandardCharsets;

/** Owns the foreground launcher's pending intent and rejects superseded callbacks. */
public final class LauncherAction {
    public enum Kind { NONE, CONNECT, GAME }

    public static final class Snapshot {
        public final long generation;
        public final Kind kind;
        public final String packageName;
        public final String profileJson;
        public final long token;

        private Snapshot(long generation, Kind kind, String packageName, String profileJson,
                long token) {
            this.generation = generation;
            this.kind = kind;
            this.packageName = packageName;
            this.profileJson = profileJson;
            this.token = token;
        }
    }

    private Snapshot current = new Snapshot(0, Kind.NONE, "", "", 0);

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Snapshot beginConnect() {
        current = new Snapshot(nextGeneration(), Kind.CONNECT, "", "", 0);
        return current;
    }

    /** Call clear() before resolving a new selection if invalid selections must cancel the old one. */
    public synchronized Snapshot beginGame(String packageName, String profileJson) {
        validatePackage(packageName);
        validateProfileJson(profileJson);
        current = new Snapshot(nextGeneration(), Kind.GAME, packageName, profileJson, 0);
        return current;
    }

    public synchronized Snapshot clear() {
        current = new Snapshot(nextGeneration(), Kind.NONE, "", "", 0);
        return current;
    }

    public synchronized boolean markPrepared(long expectedGeneration, long token) {
        if (current.generation != expectedGeneration || current.kind != Kind.GAME
                || current.token != 0) return false;
        if (token <= 0) throw new IllegalArgumentException("Prepared token must be positive");
        current = new Snapshot(current.generation, Kind.GAME, current.packageName, "", token);
        return true;
    }

    public synchronized boolean clearIfGeneration(long expectedGeneration) {
        if (current.generation != expectedGeneration || current.kind == Kind.NONE) return false;
        clear();
        return true;
    }

    public synchronized boolean clearIfToken(long expectedToken) {
        if (expectedToken <= 0 || current.kind != Kind.GAME || current.token != expectedToken)
            return false;
        clear();
        return true;
    }

    /** Restores an immutable saved state without reusing an older action generation. */
    public synchronized void restore(long generation, Kind kind, String packageName,
            String profileJson, long token) {
        if (generation < 0 || generation == Long.MAX_VALUE || kind == null || token < 0
                || packageName == null || profileJson == null)
            throw new IllegalArgumentException("Invalid saved launcher action");
        if (kind == Kind.GAME) {
            if (generation == 0) throw new IllegalArgumentException("Game generation is missing");
            validatePackage(packageName);
            if (token == 0) validateProfileJson(profileJson);
            else if (!profileJson.isEmpty())
                throw new IllegalArgumentException("Prepared game must not retain profile JSON");
        } else {
            if (!packageName.isEmpty() || !profileJson.isEmpty() || token != 0
                    || (kind == Kind.CONNECT && generation == 0))
                throw new IllegalArgumentException("Unexpected fields in non-game action");
        }
        if (generation < current.generation)
            throw new IllegalArgumentException("Saved action would roll back its generation");
        if (generation == current.generation && (kind != current.kind
                || token != current.token || !packageName.equals(current.packageName)
                || !profileJson.equals(current.profileJson)))
            throw new IllegalArgumentException("Generation already identifies a different action");
        current = new Snapshot(generation, kind, packageName, profileJson, token);
    }

    private long nextGeneration() {
        if (current.generation >= Long.MAX_VALUE - 1)
            throw new IllegalStateException("Launcher action generation exhausted");
        return current.generation + 1;
    }

    private static void validatePackage(String packageName) {
        ProfileCodec.validatePackageName(packageName);
        if (packageName.length() > 255)
            throw new IllegalArgumentException("Package name is too long");
    }

    /** JSON is opaque here; ProfileCodec owns its syntax and profile semantics. */
    private static void validateProfileJson(String json) {
        if (json == null || json.isEmpty() || json.length() > ProfileCodec.MAX_PROFILE_BYTES)
            throw new IllegalArgumentException("Profile JSON is missing or too large");
        for (int index = 0; index < json.length(); index++) {
            char value = json.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= json.length() || !Character.isLowSurrogate(json.charAt(index)))
                    throw new IllegalArgumentException("Profile JSON has malformed Unicode");
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("Profile JSON has malformed Unicode");
            }
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > ProfileCodec.MAX_PROFILE_BYTES)
            throw new IllegalArgumentException("Profile JSON is too large");
    }
}
