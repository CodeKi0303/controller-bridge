package dev.codek.xboxprobe;

/** Starts only this installed APK's fixed input-service entry point. */
interface IStandaloneHost {
    void startSession(String nonce);
    boolean finishSession(String nonce);
    boolean shutdown();
}
