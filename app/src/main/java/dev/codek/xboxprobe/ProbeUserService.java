package dev.codek.xboxprobe;

import android.content.Context;
import android.os.Process;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import dev.codek.xboxprobe.core.GamepadState;
import dev.codek.xboxprobe.core.GamepadPulse;
import dev.codek.xboxprobe.core.AxisRange;
import dev.codek.xboxprobe.core.GamepadSourceLayout;
import dev.codek.xboxprobe.core.G8InputMapper;
import dev.codek.xboxprobe.core.G8KeyboardMapper;
import dev.codek.xboxprobe.core.G8GamepadMapper;
import dev.codek.xboxprobe.core.HidKeyboardProfile;
import dev.codek.xboxprobe.core.HidKeyboardReportEncoder;
import dev.codek.xboxprobe.core.HidProfile;
import dev.codek.xboxprobe.core.NativeGamepadProfile;
import dev.codek.xboxprobe.core.NativeGamepadLayout;
import dev.codek.xboxprobe.core.ControllerProfile;
import dev.codek.xboxprobe.core.UhidEventBuilder;

/**
 * Runs in a dedicated ADB/shell user service, never as root. A single owned /dev/uhid
 * descriptor is the entire authority of this service. Every action is serialized
 * by lock; nonblocking device IO prevents the Binder thread from waiting for a
 * kernel consumer. Closing the descriptor unregisters the virtual HID device.
 *
 * UHID ABI source: Linux include/uapi/linux/uhid.h (CREATE2/INPUT2 and replies).
 * Lifecycle: https://docs.kernel.org/hid/uhid.html
 */
public final class ProbeUserService extends IProbeService.Stub {
    private enum OutputKind { NONE, KEYBOARD, LEGACY_GAMEPAD, NATIVE_GAMEPAD, CONFIGURED }
    private static final int PULSE_MS = 250;
    private static final int STICK_PULSE_MS = 1500;
    private static final long LEASE_MS = 60_000;
    private static final GamepadState NEUTRAL = GamepadState.NEUTRAL;
    private final Object lock = new Object();
    private final String ownedLocation = "g8-input-bridge:" + UUID.randomUUID();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "xbox-probe-uhid");
        thread.setDaemon(true);
        return thread;
    });
    private final byte[] readBuffer = new byte[UhidEventBuilder.EVENT_SIZE];
    private FileDescriptor fd;
    private HidProfile legacyProfile;
    private NativeGamepadProfile nativeProfile;
    private ControllerProfile configuredProfile;
    private OutputKind outputKind = OutputKind.NONE;
    private final UhidSourceCatalog sourceCatalog = new UhidSourceCatalog();
    private final AtomicLong operationGeneration = new AtomicLong();
    private final AtomicBoolean sessionClosing = new AtomicBoolean();
    private final int directOwnerUid;
    private UhidSourceBridge bridge;
    private long bridgeGeneration;
    private ScheduledFuture<?> startTask;
    private ScheduledFuture<?> neutralTask;
    private boolean started;
    private boolean consumerOpen;
    private boolean outputLayoutVerified;
    private String androidKeyLayout="";
    private boolean cleanupUnresolved;
    private boolean activePulse;
    private boolean destroyed;
    private long pulseAt;
    private long pulseToken;
    private long lastKeepAlive = SystemClock.elapsedRealtime();
    private long inputReports;
    private long outputReports;
    private long unsupportedRequests;
    private String lastError = "";
    private String warning = "";
    private String stoppedReason = "stopped";

    public ProbeUserService() {
        this(null);
    }

    /** Shizuku 13 supplies the package context for native-library lookup. */
    public ProbeUserService(Context context) {
        this(context, -1);
    }

    /** Direct helpers are accessible only to their verified application's UID. */
    ProbeUserService(Context context, int ownerUid) {
        directOwnerUid = ownerUid;
        NativeEvdevState.initialize(context);
        worker.setRemoveOnCancelPolicy(true);
        // These are lifecycle/control messages, not the user's real-time input.
        // A 100 ms poll avoids a 50 Hz idle wakeup loop in this bounded probe.
        worker.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (directOwnerUid >= 0) BootstrapAuthorization.requireUid(Binder.getCallingUid(), directOwnerUid);
        return super.onTransact(code, data, reply, flags);
    }

    @Override
    public String create(String name, int vendorId, int productId, int bus) {
        synchronized (lock) {
            String problem = creationProblem();
            if (problem != null) return reject(problem);
            if (name == null || name.trim().isEmpty() || name.indexOf('\0') >= 0
                    || name.getBytes(StandardCharsets.UTF_8).length > 127) {
                return reject("Device name must contain 1–127 UTF-8 bytes and no NUL.");
            }
            if (vendorId < 1 || vendorId > 0xffff || productId < 1 || productId > 0xffff) {
                return reject("Vendor and product identifiers must be between 1 and 65535.");
            }
            if (bus != 0x05 && bus != 0x03 && bus != 0x06) return reject("Only Bluetooth (5), USB (3), or virtual (6) input-bus metadata is supported.");
            outputKind = OutputKind.LEGACY_GAMEPAD;
            configuredProfile = null;
            nativeProfile = null;
            legacyProfile = new HidProfile(name, vendorId, productId, bus);
            return openOwned(UhidEventBuilder.create2(legacyProfile, ownedLocation));
        }
    }

    @Override
    public String createKeyboard() {
        synchronized (lock) {
            String problem = creationProblem();
            if (problem != null) return reject(problem);
            outputKind = OutputKind.KEYBOARD;
            configuredProfile = null;
            legacyProfile = null;
            nativeProfile = null;
            return openOwned(UhidEventBuilder.createKeyboard(HidKeyboardProfile.INSTANCE, ownedLocation));
        }
    }

    @Override
    public String createGamepadProbe(int profileId) {
        synchronized (lock) {
            String problem = creationProblem();
            if (problem != null) return reject(problem);
            final NativeGamepadProfile selected;
            try { selected = selectNativeProfile(profileId); }
            catch (IllegalArgumentException e) { return reject(e.getMessage()); }
            outputKind = OutputKind.NATIVE_GAMEPAD;
            configuredProfile = null;
            legacyProfile = null;
            nativeProfile = selected;
            return openOwned(UhidEventBuilder.createGamepad(selected, ownedLocation));
        }
    }

    private static NativeGamepadProfile selectNativeProfile(int profileId) {
        return NativeGamepadProfile.forProbeId(profileId);
    }

    private String outputProfileId() {
        switch (outputKind) {
            case KEYBOARD: return "keyboard";
            case LEGACY_GAMEPAD: return "legacy-xbox";
            case NATIVE_GAMEPAD: return nativeProfile.getId();
            case CONFIGURED: return "configured:" + configuredProfile.getId();
            default: return "none";
        }
    }

    @Override public String createConfiguredProbe(String profileJson) {
        synchronized(lock) {
            String problem=creationProblem();if(problem!=null)return reject(problem);
            try {
                ControllerProfile selected=ProfileCodec.decode(profileJson);
                if(selected.getKind()==ControllerProfile.Kind.DIRECT)return reject("Direct profiles do not create a virtual device");
                outputKind=OutputKind.CONFIGURED;configuredProfile=selected;legacyProfile=null;nativeProfile=null;
                return openOwned(UhidEventBuilder.createConfigured(selected,ownedLocation));
            } catch(IllegalArgumentException e){return reject("Invalid profile: "+e.getMessage());}
        }
    }

    @Override public String startConfiguredBridge(String sourceToken,String profileJson) {
        long generation=operationGeneration.get();
        synchronized(lock) {
            if(sessionClosing.get())return reject("Session is closing. Reconnect before starting a bridge.");
            if(operationGeneration.get()!=generation)return reject("Bridge start was cancelled");
            try {
                ControllerProfile selected=ProfileCodec.decode(profileJson);
                if(selected.getKind()==ControllerProfile.Kind.DIRECT)return reject("Direct profiles must close the virtual session instead of creating a bridge");
                return startSelectedSource(sourceToken,generation,OutputKind.CONFIGURED,null,Collections.emptyMap(),selected);
            }catch(IllegalArgumentException e){return reject("Invalid profile: "+e.getMessage());}
        }
    }

    /** Verify once after Android opens this exact owned device. No polling or
     * external command runs while holding the input/heartbeat monitor. */
    @Override public String verifyConfiguredOutputLayout(){
        final FileDescriptor expectedFd;final ControllerProfile expected;final long generation;
        synchronized(lock){
            if(sessionClosing.get()||fd==null||!started||!consumerOpen)return reject("Output is not ready for Android layout verification");
            if(outputLayoutVerified)return snapshot();
            expectedFd=fd;expected=configuredProfile;generation=operationGeneration.get();
            if(expected==null||expected.getKind()!=ControllerProfile.Kind.NATIVE)return reject("No configured native output to verify");
        }
        String verifiedPath="",problem="";
        try{
            verifiedPath=sourceCatalog.verifyOutputLayout(ownedLocation,expected.getIdentity(),expected.nativeProfile().getLayout(),
                    ()->sessionClosing.get()||operationGeneration.get()!=generation);
        }catch(Exception error){problem="Android의 PS 버튼 배치를 확인하지 못했습니다: "+describe(error);}
        synchronized(lock){
            if(sessionClosing.get()||fd!=expectedFd||configuredProfile!=expected||operationGeneration.get()!=generation)return snapshot();
            if(!problem.isEmpty()){lastError=problem;stopOwned("error");return snapshot();}
            androidKeyLayout=verifiedPath;outputLayoutVerified=true;
            try{if(bridge!=null&&bridge.isRunning())sendReport(bridge.currentReport());else sendNeutral();}
            catch(Exception error){lastError="Verified output start failed: "+describe(error);stopOwned("error");}
            return snapshot();
        }
    }

    private String creationProblem() {
        if (sessionClosing.get()) return "Session is closing. Reconnect to start a fresh service.";
        if (destroyed) return "Service has been destroyed.";
        if (Process.myUid() != 2000) return "This service requires ADB/shell mode (UID 2000), not root.";
        if (fd != null || cleanupUnresolved) return "A device is already owned. Stop it before creating another; unresolved cleanup requires restarting the service.";
        if (bridge != null && !bridge.isClosed()) return "The previous source reader is still stopping or cleanup is unresolved; wait or restart the service.";
        return null;
    }

    @Override
    public String listSources() {
        long generation = operationGeneration.get();
        try {
            if (sessionClosing.get()) throw new IOException("Session is closing; reconnect before listing sources");
            if (Process.myUid() != 2000) throw new IOException("Source inventory requires ADB/shell UID 2000");
            return sourceCatalog.list(() -> sessionClosing.get() || operationGeneration.get() != generation);
        } catch (Exception e) {
            try {
                return new JSONObject().put("sources", new org.json.JSONArray())
                        .put("errors", new org.json.JSONArray().put(describe(e)))
                        .put("capturedAtElapsedMs", SystemClock.elapsedRealtime()).toString();
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    @Override
    public String startBridge(String sourceToken, String mappingsJson) {
        long generation = operationGeneration.get();
        synchronized (lock) {
            if (sessionClosing.get()) return reject("Session is closing. Reconnect before starting a bridge.");
            if (operationGeneration.get() != generation) return reject("Bridge start was cancelled");
            try {
                return startSelectedSource(sourceToken, generation, OutputKind.KEYBOARD, null, parseBindings(mappingsJson),null);
            } catch (Exception e) {
                return reject("Bridge configuration failed: " + describe(e));
            }
        }
    }

    @Override
    public String startGamepadBridge(String sourceToken, int profileId) {
        long generation = operationGeneration.get();
        synchronized (lock) {
            if (sessionClosing.get()) return reject("Session is closing. Reconnect before starting a bridge.");
            if (operationGeneration.get() != generation) return reject("Bridge start was cancelled");
            try {
                return startSelectedSource(sourceToken, generation, OutputKind.NATIVE_GAMEPAD,
                        selectNativeProfile(profileId), Collections.emptyMap(),null);
            } catch (IllegalArgumentException e) { return reject(e.getMessage()); }
        }
    }

    /** One ownership path for both output protocols; only the mapper/profile differs. */
    private String startSelectedSource(String sourceToken, long generation, OutputKind requestedKind,
                                       NativeGamepadProfile requestedProfile, Map<Integer, Integer> bindings,ControllerProfile requestedConfiguration) {
        if (sessionClosing.get()) return reject("Session is closing. Reconnect before starting a bridge.");
        if (operationGeneration.get() != generation) return reject("Bridge start was cancelled");
        if (destroyed || Process.myUid() != 2000) return reject("Bridge requires a live ADB/shell service");
        if (cleanupUnresolved) return reject("Virtual-device cleanup is unresolved; restart the service");
        if (bridge != null && !bridge.isClosed()) return reject("A source is active or still closing; stop the session before selecting another");
        if (fd != null && (requestedKind==OutputKind.CONFIGURED || outputKind != requestedKind
                || requestedKind == OutputKind.NATIVE_GAMEPAD && nativeProfile != requestedProfile)) {
            return reject("Stop and reconnect before changing the output profile");
        }
        UhidSourceCatalog.OpenedSource opened = null;
        UhidSourceBridge selected = null;
        boolean transferred = false;
        boolean createdOutput = false;
        try {
            opened = sourceCatalog.open(sourceToken, () -> sessionClosing.get() || operationGeneration.get() != generation);
            if (sessionClosing.get() || operationGeneration.get() != generation) throw new IOException("Bridge start was cancelled");
            final UhidSourceCatalog.OpenedSource selectedSource = opened;
            Supplier<G8InputMapper> mapperFactory;
            String requestedId;
            if(requestedKind==OutputKind.CONFIGURED) {
                Map<Integer,AxisRange> ranges=new LinkedHashMap<>();
                for(int code:opened.capabilities.axes.keySet()){AxisRange range=axisRange(opened.capabilities,code);if(range!=null)ranges.put(code,range);}
                // The profile owns active-binding validation. Its complete output mapping
                // can include missing source buttons, and disabled keyboard bindings need none.
                mapperFactory=()->requestedConfiguration.createMapper(ranges,selectedSource.capabilities.keyCodes);
                requestedId="configured:"+requestedConfiguration.getId();
            } else if (requestedKind == OutputKind.KEYBOARD) {
                for (int key : bindings.keySet()) {
                    if (!opened.capabilities.keyCodes.contains(key)) {
                        throw new IOException("Configured source key is absent from the selected device: " + key);
                    }
                }
                mapperFactory = () -> new G8KeyboardMapper(axisRange(selectedSource.capabilities, 0), axisRange(selectedSource.capabilities, 1),
                        axisRange(selectedSource.capabilities, 16), axisRange(selectedSource.capabilities, 17), bindings);
                requestedId = "keyboard";
            } else {
                Map<Integer, AxisRange> ranges = new LinkedHashMap<>();
                for (int code : opened.capabilities.axes.keySet()) {
                    AxisRange range = axisRange(opened.capabilities, code);
                    if (range != null) ranges.put(code, range);
                }
                mapperFactory = () -> new G8GamepadMapper(requestedProfile, ranges, selectedSource.capabilities.keyCodes);
                requestedId = requestedProfile.getId();
            }
            selected = new UhidSourceBridge(opened, mapperFactory, requestedId, sourceListener,
                    requestedKind == OutputKind.CONFIGURED && requestedConfiguration.isSourceExclusive());
            if (fd == null) {
                if (requestedKind == OutputKind.KEYBOARD) createKeyboard();
                else if(requestedKind==OutputKind.CONFIGURED) {
                    outputKind=OutputKind.CONFIGURED;configuredProfile=requestedConfiguration;legacyProfile=null;nativeProfile=null;
                    openOwned(UhidEventBuilder.createConfigured(requestedConfiguration,ownedLocation));
                }
                else createGamepadProbe(requestedProfile.getProbeId());
                if (fd == null) throw new IOException("Could not create bridge output: " + lastError);
                createdOutput = true;
            }
            if (sessionClosing.get() || operationGeneration.get() != generation) throw new IOException("Bridge start was cancelled");
            cancelPulse();
            sendNeutral();
            bridge = selected;
            bridgeGeneration = generation;
            lastError = "";
            warning = requestedKind == OutputKind.CONFIGURED && requestedConfiguration.isSourceExclusive()
                    ? "Exclusive source input was requested; wait until the reader reports acquisition. The original device remains listed. Release controls before starting."
                    : "Original gamepad remains active (no exclusive grab). Release controls before starting; initial control states are unknown.";
            lastKeepAlive = SystemClock.elapsedRealtime();
            selected.start();
            transferred = true;
        } catch (Exception e) {
            lastError = "Bridge start failed: " + describe(e);
            if (createdOutput) stopOwned("error");
        } finally {
            if (opened != null && !transferred) {
                if (bridge == selected) bridge = null;
                try { sourceCatalog.closeUnclaimed(opened); }
                catch (Exception e) { lastError = "Source cleanup failed: " + describe(e); }
            }
        }
        return snapshot();
    }

    private static AxisRange axisRange(UhidSourceCapabilities.Device source, int code) {
        UhidSourceCapabilities.Axis axis = source.axes.get(code);
        return axis == null ? null : new AxisRange(axis.min, axis.max, axis.flat);
    }

    private static Map<Integer, Integer> parseBindings(String text) throws Exception {
        if (text == null || text.length() > 8192) throw new IOException("Mapping JSON must be present and at most 8 KiB");
        JSONObject root = new JSONObject(text);
        if (root.length() != 1 || !root.has("bindings")) throw new IOException("Mapping JSON must contain exactly a bindings object");
        JSONObject values = root.getJSONObject("bindings");
        if (values.length() > 64) throw new IOException("At most 64 source bindings are supported");
        Map<Integer, Integer> bindings = new LinkedHashMap<>();
        for (Iterator<String> it = values.keys(); it.hasNext();) {
            String keyText = it.next();
            if (!keyText.matches("[1-9][0-9]{0,2}")) throw new IOException("Source key codes must be canonical decimal integers");
            int key = Integer.parseInt(keyText);
            if (key > 0x2ff) throw new IOException("Source key exceeds Linux KEY_MAX");
            Object raw = values.get(keyText);
            if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new IOException("Keyboard usages must be JSON integers");
            long usage = ((Number) raw).longValue();
            if (usage < 0x04 || usage > 0x65) throw new IOException("Keyboard usage must be 0x04..0x65");
            bindings.put(key, (int) usage);
        }
        return bindings;
    }

    private final UhidSourceBridge.Listener sourceListener = new UhidSourceBridge.Listener() {
        @Override public void onReport(UhidSourceBridge source, byte[] report) throws IOException {
            synchronized (lock) {
                if (sessionClosing.get() || bridge != source || bridgeGeneration != operationGeneration.get()
                        || !source.isRunning() || fd == null || !outputLayoutVerified || !source.outputProfile().equals(outputProfileId())) return;
                try { sendReport(report); }
                catch (Exception e) {
                    lastError = "Bridge input write failed: " + describe(e);
                    stopOwned("error");
                    throw new IOException(lastError, e);
                }
            }
        }
        @Override public void onFailure(UhidSourceBridge source, String reason) {
            synchronized (lock) {
                if (bridge != source) return;
                source.requestStop();
                lastError = "Bridge stopped: " + reason;
                if (fd != null && !cleanupUnresolved && source.outputProfile().equals(outputProfileId())) {
                    try { sendNeutral(); }
                    catch (Exception e) { lastError += "; neutral release failed: " + describe(e); stopOwned("error"); }
                }
            }
        }
    };

    private String openOwned(byte[] createEvent) {
        lastError = warning = "";
        stoppedReason = "stopped";
        inputReports = outputReports = unsupportedRequests = 0;
        started = consumerOpen = activePulse = false;
        androidKeyLayout="";
        outputLayoutVerified=!(outputKind==OutputKind.CONFIGURED&&configuredProfile.getKind()==ControllerProfile.Kind.NATIVE
                &&configuredProfile.nativeProfile().getLayout()!=NativeGamepadLayout.GENERIC);
        try {
            fd = Os.open("/dev/uhid", OsConstants.O_RDWR | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            write(createEvent);
            lastKeepAlive = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            lastError = "Could not create virtual HID device: " + describe(e);
            stopOwned("error");
        }
        return snapshot();
    }

    @Override
    public String schedulePulse(int kind, int delayMs) {
        synchronized (lock) {
            if (sessionClosing.get()) return reject("Session is closing. Reconnect before scheduling input.");
            if (outputKind != OutputKind.LEGACY_GAMEPAD && outputKind != OutputKind.NATIVE_GAMEPAD
                    && !(outputKind==OutputKind.CONFIGURED&&(configuredProfile.getKind()==ControllerProfile.Kind.NATIVE||configuredProfile.getKind()==ControllerProfile.Kind.XBOX))) {
                return reject("Create a gamepad before requesting a gamepad test.");
            }
            final GamepadPulse pulse;
            try { pulse = GamepadPulse.forId(kind); }
            catch (IllegalArgumentException e) { return reject(e.getMessage()); }
            if(outputKind==OutputKind.CONFIGURED) {
                return scheduleReport(configuredProfile.encodeProbe(pulse.getState()),delayMs,pulse.getHoldMs());
            }
            if (outputKind == OutputKind.NATIVE_GAMEPAD) {
                return scheduleReport(nativeProfile.encode(pulse.getNativeState()), delayMs, pulse.getHoldMs());
            }
            return scheduleReport(legacyProfile.encode(pulse.getState()), delayMs, pulse.getHoldMs());
        }
    }

    @Override
    public String cancelPendingPulse() {
        synchronized (lock) {
            if (sessionClosing.get() || destroyed) return reject("Session is closing; input is being released.");
            if (cleanupUnresolved) return snapshot();
            if (bridge != null && !bridge.isClosed()) return reject("A source is owned or closing; stop its session to release its input.");
            cancelPulse();
            lastError = "";
            if (fd != null && !cleanupUnresolved && started) {
                try { sendNeutral(); }
                catch (Exception e) {
                    lastError = "Could not release cancelled test input: " + describe(e);
                    stopOwned("error");
                }
            }
            return snapshot();
        }
    }

    @Override
    public String scheduleKeyboardPulse(int hidUsage, int delayMs) {
        synchronized (lock) {
            if (sessionClosing.get()) return reject("Session is closing. Reconnect before scheduling input.");
            if (!isKeyboardOutput()) return reject("Create a keyboard before requesting a keyboard test.");
            boolean movement = hidUsage == HidKeyboardReportEncoder.USAGE_W
                    || hidUsage == HidKeyboardReportEncoder.USAGE_A
                    || hidUsage == HidKeyboardReportEncoder.USAGE_S
                    || hidUsage == HidKeyboardReportEncoder.USAGE_D;
            boolean action = hidUsage == HidKeyboardReportEncoder.USAGE_K
                    || hidUsage == HidKeyboardReportEncoder.USAGE_J
                    || hidUsage == HidKeyboardReportEncoder.USAGE_U
                    || hidUsage == HidKeyboardReportEncoder.USAGE_SPACE
                    || hidUsage == HidKeyboardReportEncoder.USAGE_ESCAPE;
            if (!movement && !action) return reject("Supported keyboard tests: W, A, S, D, K, J, U, Space, Esc.");
            return scheduleReport(HidKeyboardReportEncoder.reportForUsage(hidUsage), delayMs,
                    movement ? STICK_PULSE_MS : PULSE_MS);
        }
    }

    private String scheduleReport(byte[] report, int delayMs, int holdMs) {
        if (sessionClosing.get()) return reject("Session is closing. Reconnect before scheduling input.");
        if (!outputLayoutVerified) return reject("Verify the Android output layout before testing input.");
        if (bridge != null && !bridge.isClosed()) return reject("Stop the owned source before scheduling a diagnostic pulse");
        if (fd == null || cleanupUnresolved || !started || !consumerOpen) {
            return reject("The kernel must start the HID device and an input consumer must open it before testing.");
        }
        if (startTask != null || activePulse) return reject("A test is already pending or running. Cancel it before scheduling another.");
        if (delayMs < 5000 || delayMs > 30000) return reject("The test delay must be between 5 and 30 seconds.");
        lastError = "";
        pulseAt = SystemClock.elapsedRealtime() + delayMs;
        long token = ++pulseToken;
        startTask = worker.schedule(() -> startPulse(report, holdMs, token), delayMs, TimeUnit.MILLISECONDS);
        return snapshot();
    }

    @Override
    public String status() {
        synchronized (lock) {
            return snapshot();
        }
    }

    @Override
    public void keepAlive() {
        synchronized (lock) {
            if (!destroyed && !sessionClosing.get()) lastKeepAlive = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public String stop() {
        sessionClosing.set(true);
        operationGeneration.incrementAndGet();
        sourceCatalog.cancel();
        synchronized (lock) {
            stopOwned("stopped");
            return snapshot();
        }
    }

    @Override
    public void destroy() {
        sessionClosing.set(true);
        operationGeneration.incrementAndGet();
        sourceCatalog.cancel();
        synchronized (lock) {
            if (destroyed) return;
            stopOwned("destroyed");
            destroyed = true;
        }
        worker.shutdownNow();
        try { sourceCatalog.close(); }
        catch (Exception e) { System.err.println("Source catalog teardown: " + describe(e)); }
        // This is a dedicated, owned process. Process exit also closes an
        // fd whose cleanup result was uncertain; never reuse its numeric handle.
        System.exit(0);
    }

    private void tick() {
        synchronized (lock) {
            if (destroyed || fd == null || cleanupUnresolved) return;
            if (SystemClock.elapsedRealtime() - lastKeepAlive > LEASE_MS) {
                lastError = "Client heartbeat expired; the virtual pad was stopped.";
                stopOwned("lease_expired");
                return;
            }
            try {
                // Bound work per turn, even if a driver repeatedly asks for reports.
                for (int i = 0; i < 32 && fd != null; i++) {
                    int count;
                    try {
                        count = Os.read(fd, readBuffer, 0, readBuffer.length);
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.EAGAIN) break;
                        if (e.errno == OsConstants.EINTR) continue;
                        throw e;
                    }
                    if (count == 0) throw new IOException("Unexpected EOF from /dev/uhid");
                    handleKernelEvent(count);
                }
            } catch (Exception e) {
                lastError = "UHID event processing failed: " + describe(e);
                stopOwned("error");
            }
        }
    }

    private void handleKernelEvent(int count) throws IOException, ErrnoException {
        if (count < 4) throw new IOException("Truncated UHID event: " + count + " bytes");
        ByteBuffer event = ByteBuffer.wrap(readBuffer, 0, count).order(ByteOrder.LITTLE_ENDIAN);
        int type = event.getInt();
        switch (type) {
            case 2: // UHID_START
                started = true;
                break;
            case 3: // UHID_STOP
                started = consumerOpen = false;
                cancelPulse();
                lastError = "Kernel stopped this HID device; its source bridge has been stopped.";
                stopOwned("error");
                break;
            case 4: // UHID_OPEN
                consumerOpen = true;
                if (!sessionClosing.get() && outputLayoutVerified && bridge != null && bridgeGeneration == operationGeneration.get()
                        && bridge.isRunning() && bridge.outputProfile().equals(outputProfileId())) sendReport(bridge.currentReport());
                else sendNeutral();
                break;
            case 5: // UHID_CLOSE
                consumerOpen = false;
                cancelPulse();
                // Ensure a later consumer never receives a previously held input.
                sendNeutral();
                break;
            case 6: // UHID_OUTPUT: haptics are intentionally not implemented.
                if (count < 4103) throw new IOException("Truncated UHID_OUTPUT");
                int size = event.getShort(4100) & 0xffff;
                if (size > 4096) throw new IOException("Invalid UHID_OUTPUT size: " + size);
                outputReports++;
                warning = isKeyboardOutput() ? "Keyboard LED output received; LED forwarding is not implemented."
                        : "Output reports received; rumble/output forwarding is not implemented.";
                break;
            case 9: // UHID_GET_REPORT
                if (count < 10) throw new IOException("Truncated UHID_GET_REPORT");
                unsupportedRequests++;
                write(UhidEventBuilder.getReportReply(event.getInt(4), OsConstants.EOPNOTSUPP));
                warning = "A HID control-report request was declined (EOPNOTSUPP); only input reports are implemented.";
                break;
            case 13: // UHID_SET_REPORT
                if (count < 12) throw new IOException("Truncated UHID_SET_REPORT");
                unsupportedRequests++;
                write(UhidEventBuilder.setReportReply(event.getInt(4), OsConstants.EOPNOTSUPP));
                warning = "A HID control-report request was declined (EOPNOTSUPP); only input reports are implemented.";
                break;
            default:
                warning = "Unrecognized kernel UHID event type " + type + "; not interpreted.";
        }
    }

    private void startPulse(byte[] report, int holdMs, long token) {
        synchronized (lock) {
            // A cancelled task can already be waiting for this lock. It must not
            // send its old report to a device created by a newer session.
            if (sessionClosing.get() || token != pulseToken) return;
            startTask = null;
            pulseAt = 0;
            if (!outputLayoutVerified) {
                lastError = "Test cancelled: the Android output layout has not been verified.";
                return;
            }
            if (bridge != null && !bridge.isClosed()) {
                lastError = "Test cancelled: a source owns input, including startup or cleanup.";
                return;
            }
            if (fd == null || cleanupUnresolved || !started || !consumerOpen) {
                lastError = "Test cancelled: the virtual input device no longer has an input consumer.";
                return;
            }
            if (SystemClock.elapsedRealtime() - lastKeepAlive > LEASE_MS) {
                lastError = "Test cancelled: client heartbeat expired.";
                stopOwned("lease_expired");
                return;
            }
            try {
                sendReport(report);
                activePulse = true;
                neutralTask = worker.schedule(() -> finishPulse(token), holdMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                lastError = "Test input failed: " + describe(e);
                stopOwned("error");
            }
        }
    }

    private void finishPulse(long token) {
        synchronized (lock) {
            if (token != pulseToken) return;
            neutralTask = null;
            if (fd == null || cleanupUnresolved) return;
            try {
                sendNeutral();
                activePulse = false;
            } catch (Exception e) {
                lastError = "Could not release test input: " + describe(e);
                stopOwned("error");
            }
        }
    }

    private void sendNeutral() throws IOException, ErrnoException {
        switch (outputKind) {
            case KEYBOARD: sendReport(HidKeyboardReportEncoder.neutral()); break;
            case LEGACY_GAMEPAD: sendReport(legacyProfile.encode(NEUTRAL)); break;
            case NATIVE_GAMEPAD: sendReport(nativeProfile.neutralReport()); break;
            case CONFIGURED: sendReport(configuredProfile.neutralReport()); break;
            default: throw new IOException("No output profile owns the current device");
        }
    }

    private boolean isKeyboardOutput(){return outputKind==OutputKind.KEYBOARD||outputKind==OutputKind.CONFIGURED&&configuredProfile.getKind()==ControllerProfile.Kind.KEYBOARD;}

    private void sendReport(byte[] report) throws IOException, ErrnoException {
        write(UhidEventBuilder.input2(report));
        inputReports++;
    }

    private void write(byte[] event) throws IOException, ErrnoException {
        if (fd == null) throw new IOException("No owned UHID descriptor");
        for (;;) {
            try {
                int written = Os.write(fd, event, 0, event.length);
                // UHID events must be written atomically; never append a remainder.
                if (written != event.length) throw new IOException("Short UHID write: " + written + "/" + event.length);
                return;
            } catch (ErrnoException error) {
                // EINTR means no bytes were written. Other errors remain failures.
                if (error.errno != OsConstants.EINTR || sessionClosing.get()
                        || Thread.currentThread().isInterrupted()) throw error;
            }
        }
    }

    private void cancelPulse() {
        pulseToken++;
        if (startTask != null) startTask.cancel(false);
        if (neutralTask != null) neutralTask.cancel(false);
        startTask = neutralTask = null;
        pulseAt = 0;
        activePulse = false;
    }

    private void stopOwned(String reason) {
        cancelPulse();
        if (bridge != null) bridge.requestStop();
        stoppedReason = reason;
        if (fd == null || cleanupUnresolved) return;
        try {
            if (started) sendNeutral();
        } catch (Exception e) {
            warning = "Neutral report failed during stop: " + describe(e);
        }
        try {
            write(UhidEventBuilder.destroy());
        } catch (Exception e) {
            warning = "UHID_DESTROY failed; closing its descriptor will unregister it: " + describe(e);
        }
        try {
            Os.close(fd);
            fd = null;
            started = consumerOpen = false;
        } catch (ErrnoException e) {
            // Do not retry a numeric fd after uncertain close: it could be reused.
            // Keep the ownership record and disallow new creation until process exit.
            cleanupUnresolved = true;
            lastError = "UHID close result is unresolved; restart the Shizuku user service: " + describe(e);
        }
    }

    private String reject(String error) {
        lastError = error;
        return snapshot();
    }

    private String snapshot() {
        JSONObject out = new JSONObject();
        try {
            String state = cleanupUnresolved ? "cleanup_failed" : fd == null ? stoppedReason
                    : activePulse ? "pulse_active" : startTask != null ? "pulse_wait"
                    : !started ? "awaiting_kernel" : !consumerOpen ? "consumer_closed" : "ready";
            out.put("state", state);
            out.put("sessionClosing", sessionClosing.get());
            out.put("uid", Process.myUid());
            out.put("deviceOpened", fd != null);
            out.put("kernelStarted", started);
            out.put("consumerOpen", consumerOpen);
            out.put("outputLayoutVerified",outputLayoutVerified);
            out.put("androidKeyLayout",androidKeyLayout);
            out.put("pendingPulse", startTask != null);
            out.put("activePulse", activePulse);
            out.put("pulseRemainingMs", startTask == null ? 0 : Math.max(0, pulseAt - SystemClock.elapsedRealtime()));
            out.put("leaseRemainingMs", fd == null ? 0 : Math.max(0, LEASE_MS - (SystemClock.elapsedRealtime() - lastKeepAlive)));
            out.put("inputReports", inputReports);
            out.put("outputReports", outputReports);
            out.put("unsupportedRequests", unsupportedRequests);
            out.put("lastError", lastError);
            out.put("warning", warning);
            out.put("sourceCleanup", sourceCatalog.cleanupDiagnostic());
            try {
                JSONObject catalogCleanup=sourceCatalog.cleanupStatus();
                boolean outputClosed=fd==null&&!cleanupUnresolved;
                boolean sourceClosed=bridge==null||bridge.isClosed();
                out.put("cleanup",new JSONObject().put("outputClosed",outputClosed).put("sourceClosed",sourceClosed)
                        .put("catalog",catalogCleanup).put("complete",outputClosed&&sourceClosed&&catalogCleanup.getBoolean("complete")));
            } catch (Exception e) {
                out.put("cleanup",new JSONObject().put("complete",false).put("error",describe(e)));
            }
            out.put("deviceKind", outputKind == OutputKind.NONE ? "none" : isKeyboardOutput() ? "keyboard" : "gamepad");
            out.put("outputKind", outputKind == OutputKind.KEYBOARD ? "keyboard" : outputKind == OutputKind.LEGACY_GAMEPAD ? "legacy_xbox"
                    : outputKind == OutputKind.NATIVE_GAMEPAD ? "native_gamepad" : outputKind==OutputKind.CONFIGURED?"configured":"none");
            out.put("outputProfile", outputProfileId());
            out.put("profileId", outputKind == OutputKind.NATIVE_GAMEPAD ? nativeProfile.getProbeId() : JSONObject.NULL);
            out.put("ownedLocation", ownedLocation);
            if(configuredProfile!=null){out.put("configuredProfileId",configuredProfile.getId());out.put("configuredProfileLabel",configuredProfile.getLabel());out.put("configuredProfileKind",configuredProfile.getKind().name());}
            org.json.JSONArray limitations = new org.json.JSONArray();
            limitations.put("UHID exposes an Android input device; it does not create a physical USB or paired Bluetooth device.");
            if (outputKind == OutputKind.NATIVE_GAMEPAD || outputKind==OutputKind.CONFIGURED&&configuredProfile.getKind()==ControllerProfile.Kind.NATIVE) {
                limitations.put("This output uses a standard gamepad HID layout with the selected identity.");
                limitations.put("Sony identities adapt buttons and axes to a verified Android key layout; proprietary Sony protocols are not emulated.");
                limitations.put("The original gamepad remains active; the bridge does not exclusively grab its native input.");
            }
            out.put("limitations", limitations);
            if (bridge != null) {
                try { out.put("bridge", bridge.status()); }
                catch (Exception e) { out.put("bridge", new JSONObject().put("state", "status_error").put("error", describe(e))); }
            }
            if(outputKind==OutputKind.CONFIGURED) {
                out.put("name",configuredProfile.getDeviceName());out.put("vendorId",configuredProfile.getVendorId());
                out.put("productId",configuredProfile.getProductId());out.put("bus",configuredProfile.getBus());
            } else if (outputKind == OutputKind.KEYBOARD) {
                HidKeyboardProfile keyboard = HidKeyboardProfile.INSTANCE;
                out.put("name", keyboard.getName());
                out.put("vendorId", keyboard.getVendorId());
                out.put("productId", keyboard.getProductId());
                out.put("bus", keyboard.getBus());
            } else if (outputKind == OutputKind.NATIVE_GAMEPAD) {
                out.put("name", nativeProfile.getName());
                out.put("vendorId", nativeProfile.getVendorId());
                out.put("productId", nativeProfile.getProductId());
                out.put("bus", nativeProfile.getBus());
            } else if (legacyProfile != null) {
                out.put("name", legacyProfile.getName());
                out.put("vendorId", legacyProfile.getVendorId());
                out.put("productId", legacyProfile.getProductId());
                out.put("bus", legacyProfile.getBus());
            }
            return out.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Failed to serialize a finite service status", impossible);
        }
    }

    private static String describe(Exception e) {
        StringBuilder text = new StringBuilder(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        Throwable[] suppressed = e.getSuppressed();
        for (int i = 0; i < Math.min(2, suppressed.length); i++) {
            text.append("; cleanup: ").append(suppressed[i].getClass().getSimpleName()).append(": ").append(suppressed[i].getMessage());
        }
        return text.toString();
    }

}
