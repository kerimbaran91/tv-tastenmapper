package de.kerim.tvmapper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Der eigentliche Dienst: fängt die angelernte Taste ab und sendet Play/Pause.
 * Jeder Fehler wird abgefangen, damit Android den Dienst nie wegen eines Absturzes abschaltet.
 */
public class MapperService extends AccessibilityService {

    static volatile boolean running = false;

    @Override
    protected void onServiceConnected() {
        running = true;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
            }
        } catch (Throwable ignored) {
        }
        try {
            Watchdog.schedule(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            return handle(event);
        } catch (Throwable t) {
            return false; // im Zweifel Taste normal durchlassen
        }
    }

    private boolean handle(KeyEvent ev) {
        int code = ev.getKeyCode();
        int scan = ev.getScanCode();
        boolean firstDown = ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0;

        // Anlern-Modus: erste passende Taste merken
        if (Prefs.isLearning(this)) {
            if (Prefs.ignoredForLearning(code)) return false;
            if (firstDown) {
                Prefs.saveLearned(this, code, scan);
                try {
                    sendBroadcast(new Intent(Prefs.ACTION_LEARNED).setPackage(getPackageName()));
                } catch (Throwable ignored) {
                }
            }
            return true;
        }

        if (!Prefs.isEnabled(this)) return false;
        if (!Prefs.matches(this, code, scan)) return false;

        if (firstDown) sendPlayPause(this);
        return true; // Taste (Drücken, Halten, Loslassen) komplett schlucken
    }

    static void sendPlayPause(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now + 50, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // nicht benötigt
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
