package dev.codek.xboxprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only an app-issued explicit PendingIntent can reach this non-exported receiver. */
public final class PairingReplyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // A stale notification must never recreate a pairing session or an ADB client.
        WirelessPairingService.acceptNotification(intent);
    }
}
