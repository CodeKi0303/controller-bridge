package dev.codek.xboxprobe.core;

import static org.junit.Assert.*;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Test;

public final class EvdevInputRecoveryTest {
    private static final Map<Integer, AxisRange> AXES = axes();
    private static final Set<Integer> BUTTONS = buttons();

    @Test public void dropReleasesThenSnapshotRestoresHeldControlsOnTheExactOwnedFd() throws Exception {
        FileDescriptor owned = new FileDescriptor();
        AtomicInteger reads = new AtomicInteger(), constructions = new AtomicInteger();
        Supplier<G8InputMapper> factory = () -> {
            constructions.incrementAndGet();
            return nativeMapper();
        };
        EvdevInputStream stream = new EvdevInputStream(owned, factory, fd -> {
            assertSame(owned, fd);
            reads.incrementAndGet();
            // A stayed physically held; B was released while its event was lost.
            return snapshot(Map.of(304, 1), Map.of(0, 64, 1, -64, 9, 200));
        }, fd -> {
            assertSame(owned, fd);
            return EvdevInputStream.QueueState.EMPTY;
        });
        stream.accept(key(304, 1));
        stream.accept(key(305, 1));
        assertNotNull(stream.accept(sync()).getReport());
        assertArrayEquals(nativeMapper().neutralReport(), stream.accept(dropped()).getReport());
        assertTrue(stream.isDiscarding());
        // Corrupt/incomplete interval must never reach mapper validation.
        assertNull(stream.accept(key(305, 9)).getReport());
        assertNull(stream.accept(abs(0, Integer.MAX_VALUE)).getReport());
        assertEquals(EvdevInputStream.RecoveryAttempt.Outcome.NOT_NEEDED,
                stream.onQueueDrained().getOutcome()); // no SYN_REPORT yet
        assertEquals(0, reads.get());
        stream.accept(sync());
        stream.accept(key(307, 1)); // remainder of an old buffered read is discarded
        stream.accept(sync());
        assertEquals(0, reads.get());
        EvdevInputStream.RecoveryAttempt recovery = stream.onQueueDrained();
        assertEquals(EvdevInputStream.RecoveryAttempt.Outcome.RESTORED, recovery.getOutcome());
        MappingUpdate restored = recovery.getUpdate();
        assertFalse(restored.getError(), restored.isFailed());
        // ABS_GAS (9) is the right trigger; ABS_BRAKE (10) is the left.
        assertArrayEquals(new byte[] {64,-64,0,0,0,(byte)200,8,1,0}, restored.getReport());
        assertEquals(1, reads.get());
        assertEquals(2, constructions.get());
        assertEquals(1, stream.getDroppedCount());
        assertEquals(1, stream.getRecoveryCount());
        assertFalse(stream.isDiscarding());
        stream.accept(key(304, 0));
        assertEquals(0, stream.accept(sync()).getReport()[7]);
    }

    @Test public void repeatedDropsRequireTheNewestBoundaryAndRecoveryDoesNotInventPresses() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        EvdevInputStream stream = new EvdevInputStream(new FileDescriptor(), EvdevInputRecoveryTest::nativeMapper, fd -> {
            reads.incrementAndGet();
            return snapshot(Map.of(), Map.of());
        }, fd -> EvdevInputStream.QueueState.EMPTY);
        stream.accept(dropped()); stream.accept(sync()); stream.accept(dropped());
        assertEquals(EvdevInputStream.RecoveryAttempt.Outcome.NOT_NEEDED, stream.onQueueDrained().getOutcome());
        assertEquals(0, reads.get());
        stream.accept(key(304, 1)); stream.accept(sync());
        assertArrayEquals(stream.neutralReport(), stream.onQueueDrained().getUpdate().getReport());
        assertEquals(2, stream.getDroppedCount());
        assertEquals(1, reads.get());
        assertNull(stream.accept(key(304, 2)).getReport());
        assertNull(stream.accept(sync()).getReport());
    }

    @Test public void disconnectCancelsRecoveryAndNativeReadFailureNeverBecomesFalseSuccess() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        EvdevInputStream stream = new EvdevInputStream(new FileDescriptor(), EvdevInputRecoveryTest::nativeMapper, fd -> {
            reads.incrementAndGet();
            throw new IOException("same fd disconnected");
        }, fd -> EvdevInputStream.QueueState.EMPTY);
        stream.accept(dropped()); stream.accept(sync());
        assertThrows(IOException.class, stream::onQueueDrained);
        assertEquals(0, stream.getRecoveryCount());
        assertTrue(stream.isDiscarding());
        MappingUpdate disconnected = stream.disconnect();
        assertTrue(disconnected.isFailed());
        assertEquals(MappingUpdate.Failure.SOURCE_LOST, disconnected.getFailure());
        assertArrayEquals(stream.neutralReport(), disconnected.getReport());
        assertEquals(EvdevInputStream.RecoveryAttempt.Outcome.NOT_NEEDED, stream.onQueueDrained().getOutcome());
        assertEquals(1, reads.get());
        assertTrue(stream.accept(key(304, 1)).isFailed());
    }

    @Test public void invalidSnapshotStillFailsRangeValidation() throws Exception {
        EvdevInputStream stream = new EvdevInputStream(new FileDescriptor(), EvdevInputRecoveryTest::nativeMapper,
                fd -> snapshot(Map.of(), Map.of(0, 128)), fd -> EvdevInputStream.QueueState.EMPTY);
        stream.accept(dropped()); stream.accept(sync());
        EvdevInputStream.RecoveryAttempt recovery = stream.onQueueDrained();
        assertEquals(EvdevInputStream.RecoveryAttempt.Outcome.FAILED, recovery.getOutcome());
        MappingUpdate invalid = recovery.getUpdate();
        assertTrue(invalid.isFailed());
        assertEquals(MappingUpdate.Failure.AXIS_OUT_OF_RANGE, invalid.getFailure());
        assertEquals(0, stream.getRecoveryCount());
        assertArrayEquals(stream.neutralReport(), invalid.getReport());
    }

    @Test public void fragmentedBurstPreservesNativeAndXboxPressReleaseTransitions() throws Exception {
        List<Supplier<G8InputMapper>> factories = List.of(EvdevInputRecoveryTest::nativeMapper,
                () -> xboxProfile().createMapper(AXES, BUTTONS));
        for (Supplier<G8InputMapper> factory : factories) {
            AtomicInteger snapshots = new AtomicInteger();
            EvdevInputStream stream = new EvdevInputStream(new FileDescriptor(), factory, fd -> {
                snapshots.incrementAndGet();
                return snapshot(Map.of(), Map.of());
            }, fd -> EvdevInputStream.QueueState.EMPTY);
            EvdevDecoder decoder = new EvdevDecoder(24);
            List<byte[]> reports = new ArrayList<>();
            // More than 60k raw events, many simultaneous controls, no timed sleeps.
            for (int frame = 0; frame < 10000; frame++) {
                int pressed = frame & 1;
                ByteBuffer bytes = ByteBuffer.allocate(24 * 7).order(ByteOrder.LITTLE_ENDIAN);
                raw(bytes, 1, 304, pressed); raw(bytes, 1, 305, pressed); raw(bytes, 1, 310, pressed);
                raw(bytes, 3, 0, pressed == 1 ? 100 : 0); raw(bytes, 3, 9, pressed == 1 ? 255 : 0);
                raw(bytes, 3, 10, pressed == 1 ? 128 : 0); raw(bytes, 0, 0, 0);
                byte[] data = bytes.array();
                for (int offset = 0; offset < data.length;) {
                    int count = Math.min(17, data.length - offset);
                    decoder.accept(data, offset, count, event -> {
                        MappingUpdate update = stream.accept(event);
                        assertFalse(update.getError(), update.isFailed());
                        if (update.getReport() != null) reports.add(update.getReport());
                    });
                    offset += count;
                }
            }
            decoder.finish();
            assertEquals(9999, reports.size());
            assertEquals(0, snapshots.get());
            assertEquals(0, stream.getDroppedCount());
            for (int i = 0; i < reports.size(); i += 2) assertFalse(java.util.Arrays.equals(stream.neutralReport(), reports.get(i)));
            for (int i = 1; i < reports.size(); i += 2) assertArrayEquals(stream.neutralReport(), reports.get(i));
        }
    }

    @Test public void eventsQueuedDuringSnapshotAreDiscardedBeforeAReplacementCanPublish() throws Exception {
        for (Supplier<G8InputMapper> mapper : List.<Supplier<G8InputMapper>>of(
                EvdevInputRecoveryTest::nativeMapper, () -> xboxProfile().createMapper(AXES, BUTTONS))) {
            FileDescriptor owned = new FileDescriptor();
            Queue<EvdevEvent> queue = new ArrayDeque<>();
            AtomicInteger reads = new AtomicInteger(), constructions = new AtomicInteger(), checks = new AtomicInteger();
            EvdevInputStream stream = new EvdevInputStream(owned, () -> {
                constructions.incrementAndGet();
                return mapper.get();
            }, fd -> {
                assertSame(owned, fd);
                if (reads.incrementAndGet() == 1) {
                    // The first snapshot contains newer values than these queued
                    // events. Publishing it before draining them would replay an
                    // old press/motion after restoring a newer released state.
                    queue.add(key(304, 1)); queue.add(abs(0, 100)); queue.add(sync());
                    queue.add(key(304, 0)); queue.add(abs(0, 0)); queue.add(sync());
                }
                return snapshot(Map.of(), Map.of());
            }, fd -> {
                assertSame(owned, fd);
                checks.incrementAndGet();
                return queue.isEmpty() ? EvdevInputStream.QueueState.EMPTY : EvdevInputStream.QueueState.PENDING;
            });
            stream.accept(dropped()); stream.accept(sync());
            EvdevInputStream.RecoveryAttempt pending = stream.onQueueDrained();
            assertEquals(EvdevInputStream.RecoveryAttempt.Outcome.DEFERRED, pending.getOutcome());
            assertNull(pending.getUpdate().getReport());
            assertFalse(pending.getUpdate().isFailed());
            assertEquals(1, constructions.get());
            assertEquals(0, stream.getRecoveryCount());
            assertTrue(stream.isDiscarding());
            while (!queue.isEmpty()) {
                MappingUpdate discarded = stream.accept(queue.remove());
                assertNull(discarded.getReport());
                assertFalse(discarded.isFailed());
            }
            EvdevInputStream.RecoveryAttempt recovered = stream.onQueueDrained();
            assertEquals(EvdevInputStream.RecoveryAttempt.Outcome.RESTORED, recovered.getOutcome());
            assertArrayEquals(stream.neutralReport(), recovered.getUpdate().getReport());
            assertEquals(2, reads.get());
            assertEquals(2, checks.get());
            assertEquals(2, constructions.get());
            assertEquals(1, stream.getRecoveryCount());
            assertFalse(stream.isDiscarding());
            stream.accept(key(304, 1));
            assertNotNull(stream.accept(sync()).getReport());
            stream.accept(key(304, 0));
            assertArrayEquals(stream.neutralReport(), stream.accept(sync()).getReport());
        }
    }

    @Test public void failedPostSnapshotQueueCheckDoesNotPublishOrCountRecovery() throws Exception {
        FileDescriptor owned = new FileDescriptor();
        AtomicInteger constructions = new AtomicInteger();
        EvdevInputStream stream = new EvdevInputStream(owned, () -> {
            constructions.incrementAndGet();
            return nativeMapper();
        }, fd -> snapshot(Map.of(304, 1), Map.of(0, 100)), fd -> {
            assertSame(owned, fd);
            throw new IOException("same fd disconnected during readiness check");
        });
        stream.accept(dropped()); stream.accept(sync());
        IOException failure = assertThrows(IOException.class, stream::onQueueDrained);
        assertEquals("same fd disconnected during readiness check", failure.getMessage());
        assertEquals(1, constructions.get());
        assertEquals(0, stream.getRecoveryCount());
        assertTrue(stream.isDiscarding());
        assertTrue(stream.disconnect().isFailed());
    }

    private static G8InputMapper nativeMapper() { return new G8GamepadMapper(NativeGamepadProfile.KISHI_IDENTITY, AXES, BUTTONS); }
    private static ControllerProfile xboxProfile() {
        return new ControllerProfile("burst-xbox", "Xbox", ControllerProfile.Kind.XBOX,
                "Xbox Wireless Controller",0x045e,0x02e0,5,
                ControllerProfile.defaultButtons(ControllerProfile.Kind.XBOX), Map.of(), ControllerProfile.AxisConfig.DEFAULT);
    }
    private static InputStateSnapshot snapshot(Map<Integer,Integer> held, Map<Integer,Integer> moved) {
        Map<Integer,Integer> keys = new HashMap<>(), axes = new HashMap<>();
        for (int button : BUTTONS) keys.put(button, held.getOrDefault(button,0));
        for (int axis : AXES.keySet()) axes.put(axis, moved.getOrDefault(axis,0));
        return new InputStateSnapshot(keys, axes);
    }
    private static Map<Integer,AxisRange> axes() {
        Map<Integer,AxisRange> axes=new HashMap<>();
        for(int code:new int[]{0,1,2,5})axes.put(code,new AxisRange(-127,127,15));
        for(int code:new int[]{9,10})axes.put(code,new AxisRange(0,255,15));
        for(int code:new int[]{16,17})axes.put(code,new AxisRange(-1,1,0));
        return axes;
    }
    private static Set<Integer> buttons() { Set<Integer> keys=new HashSet<>(); for(int i=304;i<=318;i++)keys.add(i); return keys; }
    private static EvdevEvent key(int code,int value){return new EvdevEvent(1,code,value);}
    private static EvdevEvent abs(int code,int value){return new EvdevEvent(3,code,value);}
    private static EvdevEvent sync(){return new EvdevEvent(0,0,0);}
    private static EvdevEvent dropped(){return new EvdevEvent(0,3,0);}
    private static void raw(ByteBuffer out,int type,int code,int value){out.putLong(42).putLong(123).putShort((short)type).putShort((short)code).putInt(value);}
}
