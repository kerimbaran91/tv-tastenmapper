package de.kerim.tvmapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Nach Neustart oder App-Update: Dienst sofort sicherstellen und Wächter einplanen. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Context app = context.getApplicationContext();
            Healer.ensure(app, false);
            Watchdog.schedule(app);
            Watchdog.scheduleBoot(app);
            Watchdog.scheduleSoon(app);
            Watchdog.scheduleLater(app);
        } catch (Throwable ignored) {
        }
    }
}
