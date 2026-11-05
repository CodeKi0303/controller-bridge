package dev.codek.xboxprobe;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import dev.codek.xboxprobe.core.HidIdentity;
import dev.codek.xboxprobe.core.NativeGamepadLayout;

/** Same-process opaque selection tokens bind a parsed capability snapshot to a device node. */
final class UhidSourceCatalog {
    private static final int OUTPUT_LIMIT = 262144;
    private final Map<String, Entry> choices = new LinkedHashMap<>();
    private volatile Process producer;
    private volatile Thread producerReader;
    private volatile FileDescriptor unresolvedFd;
    private volatile boolean closed;
    private volatile String cleanupDiagnostic = "";

    static final class Identity {
        final long device, inode, rdev, ctime;
        Identity(StructStat stat) throws IOException {
            if (!OsConstants.S_ISCHR(stat.st_mode)) throw new IOException("Source is not a character device");
            device = stat.st_dev; inode = stat.st_ino; rdev = stat.st_rdev; ctime = stat.st_ctime;
        }
        boolean same(Identity other) {
            return device == other.device && inode == other.inode && rdev == other.rdev && ctime == other.ctime;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("device", device).put("inode", inode).put("rdev", rdev).put("ctime", ctime);
        }
    }

    private static final class Entry {
        final String token;
        final UhidSourceCapabilities.Device capabilities;
        final Identity identity;
        Entry(String token, UhidSourceCapabilities.Device capabilities, Identity identity) {
            this.token = token; this.capabilities = capabilities; this.identity = identity;
        }
    }

    static final class OpenedSource {
        final FileDescriptor fd;
        final UhidSourceCapabilities.Device capabilities;
        final Identity identity;
        OpenedSource(FileDescriptor fd, UhidSourceCapabilities.Device capabilities, Identity identity) {
            this.fd = fd; this.capabilities = capabilities; this.identity = identity;
        }
    }

    synchronized String list(BooleanSupplier cancelled) throws Exception {
        if (closed) throw new IOException("Source catalog has been closed");
        if (unresolvedFd != null) throw new IOException("A source close result is unresolved; restart the service");
        List<UhidSourceCapabilities.Device> devices = UhidSourceCapabilities.parse(inventory(null, cancelled));
        Map<String, Entry> next = new LinkedHashMap<>();
        JSONArray sources = new JSONArray(), errors = new JSONArray(), excluded = new JSONArray();
        for (UhidSourceCapabilities.Device device : devices) {
            String uncertainty = device.sourceInventoryProblem();
            if (uncertainty != null) {
                excluded.put(new JSONObject().put("path",device.path)
                        .put("name",device.name == null ? JSONObject.NULL : device.name)
                        .put("reason",uncertainty).put("isGamepad",device.isGamepadCandidate() ? true : JSONObject.NULL)
                        .put("generatedOutputLocation",false));
                errors.put(device.path + ": " + uncertainty);
                continue;
            }
            if (!device.isGamepadCandidate()) continue;
            if (!device.isSelectableSource()) {
                excluded.put(new JSONObject().put("path", device.path).put("name", device.name)
                        .put("reason", device.sourceSelectionProblem()).put("isGamepad", true)
                        .put("generatedOutputLocation", device.isBridgeOutputLocation()));
                if (!device.isBridgeOutputLocation()) errors.put(device.path + ": " + device.sourceSelectionProblem());
                continue;
            }
            try {
                Identity identity = new Identity(Os.lstat(device.path));
                String token = UUID.randomUUID().toString();
                Entry entry = new Entry(token, device, identity);
                next.put(token, entry);
                sources.put(toJson(entry));
            } catch (Exception e) {
                errors.put(device.path + ": " + e.getMessage());
            }
        }
        checkCancelled(cancelled);
        choices.clear();
        choices.putAll(next);
        return new JSONObject().put("sources", sources).put("errors", errors).put("excludedGamepads", excluded)
                .put("capturedAtElapsedMs", SystemClock.elapsedRealtime()).toString();
    }

    /** Only a previously displayed opaque token can choose a path; no caller-supplied path. */
    synchronized OpenedSource open(String token, BooleanSupplier cancelled) throws Exception {
        if (closed) throw new IOException("Source catalog has been closed");
        if (unresolvedFd != null) throw new IOException("A source close result is unresolved; restart the service");
        checkCancelled(cancelled);
        Entry entry = choices.get(token);
        if (entry == null) throw new IOException("Source token expired or is unknown; refresh and select the device again");
        Identity before = new Identity(Os.lstat(entry.capabilities.path));
        if (!entry.identity.same(before)) throw new IOException("Selected source node changed; refresh and select it again");
        FileDescriptor fd = Os.open(entry.capabilities.path,
                OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        boolean retained = false;
        try {
            Identity opened = new Identity(Os.fstat(fd));
            if (!before.same(opened)) throw new IOException("Source identity changed while opening it");
            List<UhidSourceCapabilities.Device> fresh = UhidSourceCapabilities.parse(inventory(entry.capabilities.path, cancelled));
            if (fresh.size() != 1) throw new IOException("Selected source did not produce one unambiguous capability record");
            UhidSourceCapabilities.Device actual = fresh.get(0);
            if (!actual.isSelectableSource()) throw new IOException("Selected source is not selectable: " + actual.sourceSelectionProblem());
            if (!entry.capabilities.capabilitySignature().equals(actual.capabilitySignature())) {
                throw new IOException("Selected source capabilities or identity changed; refresh and select it again");
            }
            Identity after = new Identity(Os.lstat(actual.path));
            if (!opened.same(after) || !opened.same(new Identity(Os.fstat(fd)))) {
                throw new IOException("Source node changed during capability verification");
            }
            checkCancelled(cancelled);
            choices.remove(token); // A successful selection is consumed; never reopen after disconnect.
            retained = true;
            return new OpenedSource(fd, actual, opened);
        } finally {
            if (!retained) {
                try { Os.close(fd); }
                catch (Exception closeFailure) {
                    unresolvedFd = fd;
                    throw new IOException("Source cleanup is unresolved; restart the service", closeFailure);
                }
            }
        }
    }

    synchronized void closeUnclaimed(OpenedSource source) throws IOException {
        try { Os.close(source.fd); }
        catch (Exception e) {
            unresolvedFd = source.fd;
            throw new IOException("Unclaimed source fd cleanup is unresolved; restart the service", e);
        }
    }

    void cancel() {
        // Do not wait for the inventory monitor: stop must interrupt an in-flight
        // producer even while its Binder caller is waiting for that process.
        Process current = producer;
        if (current != null && current.isAlive()) {
            try { current.destroyForcibly(); }
            catch (RuntimeException e) {
                // The inventory owner still runs its bounded finally cleanup.
                // Stop must proceed to release currently held keyboard keys.
                cleanupDiagnostic = "Producer cancellation request failed: " + e.getMessage();
            }
        }
    }

    String cleanupDiagnostic() { return cleanupDiagnostic; }

    /** Handle-based liveness facts; no PID/path ownership inference. */
    JSONObject cleanupStatus() throws Exception {
        Process current=producer;Thread reader=producerReader;
        boolean processClosed=current==null||!current.isAlive();
        boolean readerClosed=reader==null||!reader.isAlive();
        boolean fdResolved=unresolvedFd==null;
        return new JSONObject().put("producerClosed",processClosed).put("readerClosed",readerClosed)
                .put("fdResolved",fdResolved).put("diagnostic",cleanupDiagnostic)
                .put("complete",processClosed&&readerClosed&&fdResolved&&cleanupDiagnostic.isEmpty());
    }

    void close() throws Exception {
        closed = true;
        cancel();
        synchronized (this) {
            choices.clear();
            reapOwned(producer, producerReader);
        }
        // An uncertainly closed numeric fd is deliberately not retried. The
        // dedicated service process exits immediately after this teardown.
    }

    private static JSONObject toJson(Entry entry) throws Exception {
        UhidSourceCapabilities.Device d = entry.capabilities;
        JSONObject axes = new JSONObject();
        for (UhidSourceCapabilities.Axis a : d.axes.values()) {
            axes.put(Integer.toString(a.code), new JSONObject().put("value", a.value).put("min", a.min)
                    .put("max", a.max).put("fuzz", a.fuzz).put("flat", a.flat)
                    .put("resolution", a.resolution == null ? JSONObject.NULL : a.resolution));
        }
        return new JSONObject().put("token", entry.token).put("path", d.path).put("name", d.name)
                .put("location", d.location == null ? JSONObject.NULL : d.location)
                .put("uniqueId", d.uniqueId == null ? JSONObject.NULL : d.uniqueId)
                .put("bus", d.bus).put("vendorId", d.vendorId).put("productId", d.productId)
                .put("version", d.version).put("keyCodes", new JSONArray(d.keyCodes))
                .put("axes", axes).put("identity", entry.identity.json());
    }

    private String inventory(String selectedPath, BooleanSupplier cancelled) throws Exception {
        ProcessBuilder command = selectedPath == null ? new ProcessBuilder("/system/bin/getevent", "-i")
                : new ProcessBuilder("/system/bin/getevent", "-i", selectedPath);
        return capture(command,OUTPUT_LIMIT,"getevent inventory",cancelled);
    }

    /** Uses the same owned/cancellable producer as source inventory. Read the
     * layout Android actually selected, not a guessed vendor-file search order. */
    synchronized String verifyOutputLayout(String location,HidIdentity identity,NativeGamepadLayout layout,BooleanSupplier cancelled)throws Exception {
        String dump=capture(new ProcessBuilder("/system/bin/dumpsys","input"),AndroidOutputKeyLayout.MAX_DUMP_BYTES,"Android input layout",cancelled);
        String path=AndroidOutputKeyLayout.selectedPath(dump,location,identity);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream file=new FileInputStream(path)){
            byte[] buffer=new byte[4096];int count;
            while((count=file.read(buffer))!=-1){checkCancelled(cancelled);if(output.size()+count>AndroidOutputKeyLayout.MAX_LAYOUT_BYTES)throw new IOException("Android key layout exceeds 64 KiB");output.write(buffer,0,count);}
        }
        checkCancelled(cancelled);
        String contents=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        AndroidOutputKeyLayout.verifyContents(contents,layout);
        return path;
    }

    private String capture(ProcessBuilder command,int outputLimit,String label,BooleanSupplier cancelled)throws Exception {
        if (producer != null && (producer.isAlive() || producerReader != null && producerReader.isAlive())) {
            throw new IOException("Previous capability producer cleanup is unresolved; retry after it exits");
        }
        checkCancelled(cancelled);
        producer = null;
        producerReader = null;
        Process process = null;
        Thread reader = null;
        Exception primaryFailure = null;
        try {
            process = command.redirectErrorStream(true).start();
            producer = process;
            checkCancelled(cancelled); // Covers cancellation during ProcessBuilder.start().
            final Process owned = process;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            IOException[] readError = new IOException[1];
            reader = new Thread(() -> {
                try (InputStream stream = owned.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (output.size() + count > outputLimit) {
                            throw new IOException(label+" exceeded its bounded output limit");
                        }
                        output.write(buffer, 0, count);
                    }
                } catch (IOException e) {
                    readError[0] = e;
                    owned.destroy();
                }
            }, "g8-source-capabilities");
            reader.setDaemon(true);
            producerReader = reader;
            reader.start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) throw new IOException(label+" timed out after 3 seconds");
            reader.join(500);
            checkCancelled(cancelled);
            if (reader.isAlive()) throw new IOException(label+" output reader did not finish");
            if (readError[0] != null) throw readError[0];
            if (process.exitValue() != 0) throw new IOException(label+" failed (exit " + process.exitValue() + ")");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            primaryFailure = new IOException(label+" was interrupted", e);
            throw primaryFailure;
        } catch (Exception e) {
            primaryFailure = e;
            throw e;
        } finally {
            try { reapOwned(process, reader); }
            catch (IOException cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    private void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (closed || cancelled.getAsBoolean()) throw new IOException("Source operation was cancelled");
    }

    /** Finally-path cleanup has its own deadline and restores interruption afterwards. */
    private void reapOwned(Process process, Thread reader) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            if (process != null && process.isAlive()) {
                try { process.destroyForcibly(); }
                catch (RuntimeException e) {
                    cleanupDiagnostic = "Owned input-inspection process termination failed: " + e.getMessage();
                    throw new IOException(cleanupDiagnostic, e);
                }
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (process != null && process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (reader != null && reader.isAlive() && System.nanoTime() < deadline) {
                try { reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (process != null && process.isAlive() || reader != null && reader.isAlive()) {
                cleanupDiagnostic = "Owned input-inspection producer/reader cleanup remains unresolved; restart the service";
                throw new IOException(cleanupDiagnostic);
            }
            if (producer == process) producer = null;
            if (producerReader == reader) producerReader = null;
            cleanupDiagnostic = "";
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
