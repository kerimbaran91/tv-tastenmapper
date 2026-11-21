package de.kerim.tvmapper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Der eigentliche Dienst: fängt die angelernte Taste ab und sendet Play/Pause.
 * Jeder Fehler wird abgefangen, damit Android den Dienst nie wegen eines Absturzes abschaltet.
 */
public class MapperService extends AccessibilityService {

    static volatile boolean running = false;
    static volatile MapperService instance = null;

    private static final int GA_HEADSETHOOK = 10; // AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK (API 31)
    private static final int GA_DPAD_CENTER = 20; // AccessibilityService.GLOBAL_ACTION_DPAD_CENTER (API 33)

    @Override
    protected void onServiceConnected() {
        running = true;
        instance = this;
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
                    sendPlayPause(this, this);
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
        if (firstDown) sendPlayPause(this, this);
        return true;
    }

    private void notifyApp(String action) {
        try {
            sendBroadcast(new Intent(action).setPackage(getPackageName()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Sendet Play/Pause mit der gewählten Methode.
     * Kopfhörer- und OK-Taste werden vom System als echter Tastendruck an die App im Vordergrund geschickt.
     * @return true, wenn gesendet
     */
    static boolean sendPlayPause(Context c, MapperService svc) {
        int m = Prefs.method;
        if (svc != null && m != Prefs.METHOD_SESSION) {
            try {
                if (m == Prefs.METHOD_OK && Build.VERSION.SDK_INT >= 33) {
                    if (svc.performGlobalAction(GA_DPAD_CENTER)) return true;
                } else if (Build.VERSION.SDK_INT >= 31) {
                    if (svc.performGlobalAction(GA_HEADSETHOOK)) return true;
                }
            } catch (Throwable ignored) {
            }
        }
        // Rückfall: Medien-Sitzung
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now + 50, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
        return true;
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
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        super.onDestroy();
    }
}
