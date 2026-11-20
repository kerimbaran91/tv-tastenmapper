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
            Prefs.load(this);
        } catch (Throwable ignored) {
        }
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
            // Schneller Durchlauf: alle fremden Tasten sofort zurückgeben (nur Speicherzugriff).
            // So reagieren Steuerkreuz und Lautstärke ohne spürbare Verzögerung.
            if (Prefs.learnUntil == 0 && Prefs.diagUntil == 0) {
                if (!Prefs.enabled || !Prefs.matches(event.getKeyCode(), event.getScanCode())) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    sendPlayPause(this);
                }
                return true; // Taste (Drücken, Halten, Loslassen) komplett schlucken
            }
            return handleSpecial(event);
        } catch (Throwable t) {
            return false; // im Zweifel Taste normal durchlassen
        }
    }

    /** Anlern- und Diagnose-Modus (nur wenn in der App gestartet). */
    private boolean handleSpecial(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean firstDown = ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0;

        if (Prefs.isDiag()) {
            if (firstDown) {
                Diag.record(ev, "Dienst");
                notifyApp(Prefs.ACTION_DIAG);
            }
            return false; // Diagnose ändert nichts, Tasten gehen normal weiter
        }
        Prefs.stopDiag();

        if (Prefs.isLearning()) {
            if (Prefs.ignoredForLearning(code)) return false;
            if (firstDown) {
                Prefs.saveLearned(this, code, ev.getScanCode());
                notifyApp(Prefs.ACTION_LEARNED);
            }
            return true;
        }
        Prefs.stopLearning();

        if (!Prefs.enabled || !Prefs.matches(code, ev.getScanCode())) return false;
        if (firstDown) sendPlayPause(this);
        return true;
    }

    private void notifyApp(String action) {
        try {
            sendBroadcast(new Intent(action).setPackage(getPackageName()));
        } catch (Throwable ignored) {
        }
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
