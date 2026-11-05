package dev.codek.xboxprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LauncherActionTest {
    @Test public void stalePermissionDenialCannotCancelNewDirectGame() {
        LauncherAction action = new LauncherAction();
        long permissionGeneration = action.beginConnect().generation;
        LauncherAction.Snapshot direct = action.beginGame("game.direct", "{\"kind\":\"direct\"}");
        assertFalse(action.clearIfGeneration(permissionGeneration));
        assertSame(direct, action.snapshot());
    }

    @Test public void oldGamePermissionDenialCannotCancelReplacementGame() {
        LauncherAction action = new LauncherAction();
        long oldGeneration = action.beginGame("game.old", "{}").generation;
        LauncherAction.Snapshot newer = action.beginGame("game.newer", "{}");
        assertFalse(action.clearIfGeneration(oldGeneration));
        assertEquals("game.newer", action.snapshot().packageName);
        assertTrue(action.clearIfGeneration(newer.generation));
        assertFalse(action.clearIfGeneration(newer.generation));
    }

    @Test public void clearingBeforeInvalidSelectionRevokesPriorGame() {
        LauncherAction action = new LauncherAction();
        long oldGeneration = action.beginGame("game.old", "{}").generation;
        action.clear(); // The UI cancels first, then resolves and validates the new selection.
        assertThrows(IllegalArgumentException.class, () -> action.beginGame("bad/package", "{}"));
        assertEquals(LauncherAction.Kind.NONE, action.snapshot().kind);
        assertFalse(action.markPrepared(oldGeneration, 14));
        assertFalse(action.clearIfGeneration(oldGeneration));
    }

    @Test public void preparationBelongsToOneGenerationAndHandoffClearsOnlyOnce() {
        LauncherAction action = new LauncherAction();
        long first = action.beginGame("game.first", "{}").generation;
        long second = action.beginGame("game.second", "{}").generation;
        assertFalse(action.markPrepared(first, 21));
        assertTrue(action.markPrepared(second, 22));
        assertEquals("", action.snapshot().profileJson);
        assertEquals("game.second", action.snapshot().packageName);
        assertEquals(second, action.snapshot().generation);
        assertFalse(action.markPrepared(second, 23));
        assertFalse(action.clearIfToken(21));
        assertTrue(action.clearIfToken(22));
        assertFalse(action.clearIfToken(22));
        assertEquals(LauncherAction.Kind.NONE, action.snapshot().kind);
    }

    @Test public void zeroTokenCannotClearUnpreparedGameOrConnection() {
        LauncherAction action = new LauncherAction();
        long generation = action.beginGame("game.one", "{}").generation;
        assertFalse(action.clearIfToken(0));
        assertThrows(IllegalArgumentException.class, () -> action.markPrepared(generation, 0));
        action.beginConnect();
        assertFalse(action.clearIfToken(0));
        assertFalse(action.markPrepared(generation, 31));
        assertEquals(LauncherAction.Kind.CONNECT, action.snapshot().kind);
    }

    @Test public void snapshotsRemainImmutableWhenActionChanges() {
        LauncherAction action = new LauncherAction();
        LauncherAction.Snapshot pending = action.beginGame("game.one", "{}");
        assertTrue(action.markPrepared(pending.generation, 42));
        action.clear();
        assertEquals("{}", pending.profileJson);
        assertEquals(0, pending.token);
        assertEquals(LauncherAction.Kind.GAME, pending.kind);
        assertEquals("", action.snapshot().packageName);
    }

    @Test public void pendingAndPreparedSavedStatesRestoreWithoutTokenReissue() {
        LauncherAction pending = new LauncherAction();
        pending.restore(7, LauncherAction.Kind.GAME, "game.saved", "{}", 0);
        assertTrue(pending.markPrepared(7, 81));
        LauncherAction restored = new LauncherAction();
        restored.restore(7, LauncherAction.Kind.GAME, "game.saved", "", 81);
        restored.restore(7, LauncherAction.Kind.GAME, "game.saved", "", 81);
        assertFalse(restored.markPrepared(7, 82));
        assertTrue(restored.clearIfToken(81));
        assertEquals(8, restored.snapshot().generation);
        assertFalse(restored.clearIfGeneration(7));
    }

    @Test public void restoreRejectsInconsistentStateAndGenerationRollbackAtomically() {
        LauncherAction action = new LauncherAction();
        action.restore(5, LauncherAction.Kind.CONNECT, "", "", 0);
        LauncherAction.Snapshot before = action.snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> action.restore(4, LauncherAction.Kind.NONE, "", "", 0));
        assertThrows(IllegalArgumentException.class,
                () -> action.restore(5, LauncherAction.Kind.GAME, "game.other", "{}", 0));
        assertThrows(IllegalArgumentException.class,
                () -> action.restore(6, LauncherAction.Kind.GAME, "game.one", "{}", 9));
        assertThrows(IllegalArgumentException.class,
                () -> action.restore(6, LauncherAction.Kind.GAME, "game.one", "", 0));
        assertThrows(IllegalArgumentException.class,
                () -> action.restore(6, LauncherAction.Kind.NONE, "game.one", "", 0));
        assertThrows(IllegalArgumentException.class,
                () -> action.restore(0, LauncherAction.Kind.CONNECT, "", "", 0));
        assertSame(before, action.snapshot());
    }

    @Test public void textBudgetCountsUtf8AndRejectsMalformedUnicodeBeforeMutation() {
        LauncherAction action = new LauncherAction();
        LauncherAction.Snapshot before = action.beginConnect();
        String asciiAtLimit = repeat('a', ProfileCodec.MAX_PROFILE_BYTES);
        assertThrows(IllegalArgumentException.class,
                () -> action.beginGame("game.one", asciiAtLimit + "a"));
        assertThrows(IllegalArgumentException.class,
                () -> action.beginGame("game.one", repeat('\uac00', 21846)));
        assertThrows(IllegalArgumentException.class,
                () -> action.beginGame("game.one", "\ud800"));
        assertThrows(IllegalArgumentException.class,
                () -> action.beginGame("game.one", "\udc00"));
        assertThrows(IllegalArgumentException.class,
                () -> action.beginGame(repeat('a', 256), "{}"));
        assertSame(before, action.snapshot());
        assertEquals(asciiAtLimit, action.beginGame("game.one", asciiAtLimit).profileJson);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
