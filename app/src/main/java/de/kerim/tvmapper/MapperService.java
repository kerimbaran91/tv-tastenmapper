package de.kerim.tvmapper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
        Protokoll.add(this, "Dienst gestartet");
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
        try {
            if (Prefs.method == Prefs.METHOD_REAL) RealKey.checkAsync(this, false);
        } catch (Throwable ignored) {
        }
        registerNetworkWatch();
        startKeepAlive();
    }

    // ---------- Minuten-Prüfung des Hilfsprogramms (eigener Thread, blockiert nie die Taste) ----------
    private static HandlerThread watchThread;
    private static Handler watch;
    private static final long KEEPALIVE_MS = 60_000;

    private static volatile Context appCtx;

    // Statisch: es gibt immer nur EINE Prüf-Schleife, auch wenn der Dienst neu verbunden wird
    private static final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            try {
                Context app = appCtx;
                if (app != null && Prefs.method == Prefs.METHOD_REAL) {
                    RealKey.keepAlive(app);
                    RealKey.refreshAdbIfDue(app);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (running && watch != null) {
                    watch.removeCallbacks(this); // nie zwei Schleifen gleichzeitig
                    watch.postDelayed(this, KEEPALIVE_MS);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private void startKeepAlive() {
        try {
            appCtx = getApplicationContext();
            synchronized (MapperService.class) {
                if (watch == null) {
                    watchThread = new HandlerThread("echte-taste-pruefung");
                    watchThread.start();
                    watch = new Handler(watchThread.getLooper());
                }
            }
            watch.removeCallbacks(keepAlive);
            watch.postDelayed(keepAlive, KEEPALIVE_MS);
        } catch (Throwable ignored) {
        }
    }

    private void stopKeepAlive() {
        try {
            if (watch != null) watch.removeCallbacks(keepAlive);
        } catch (Throwable ignored) {
        }
    }

    private ConnectivityManager.NetworkCallback netCallback;

    /** Sobald das WLAN (wieder) verbunden ist: echte Taste sofort prüfen/starten. */
    private void registerNetworkWatch() {
        try {
            if (netCallback != null) return;
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            final Context app = getApplicationContext();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    try {
                        if (Prefs.method == Prefs.METHOD_REAL && !RealKey.active) {
                            main.postDelayed(() -> RealKey.checkAsync(app, false), 2000);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Throwable ignored) {
            netCallback = null;
        }
    }

    private void unregisterNetworkWatch() {
        try {
            if (netCallback != null) {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                if (cm != null) cm.unregisterNetworkCallback(netCallback);
            }
        } catch (Throwable ignored) {
        }
        netCallback = null;
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

    // Hintergrund-Thread für die echte Taste (Netzwerk darf nicht im Haupt-Thread laufen)
    private static HandlerThread bgThread;
    private static Handler bg;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static synchronized Handler bg() {
        if (bg == null) {
            bgThread = new HandlerThread("echte-taste");
            bgThread.start();
            bg = new Handler(bgThread.getLooper());
        }
        return bg;
    }

    /**
     * Sendet Play/Pause mit der gewählten Methode.
     * Echte Taste: über das Hilfsprogramm; klappt das nicht, sofort Rückfall auf die Kopfhörer-Taste.
     */
    static void sendPlayPause(Context c, MapperService svc) {
        final Context app = c.getApplicationContext();
        int m = Prefs.method;
        if (m == Prefs.METHOD_REAL) {
            bg().post(() -> {
                boolean ok = false;
                try {
                    ok = RealKey.pressKey(app, RealKey.KEYCODE_MEDIA_PLAY_PAUSE);
                } catch (Throwable ignored) {
                }
                if (!ok) {
                    main.post(() -> sendSystem(app, MapperService.instance, Prefs.METHOD_HEADSET));
                    RealKey.ensureAsync(app);
                }
            });
            return;
        }
        sendSystem(app, svc, m);
    }

    private static void sendSystem(Context c, MapperService svc, int m) {
        if (svc != null && m != Prefs.METHOD_SESSION) {
            try {
                if (m == Prefs.METHOD_OK && Build.VERSION.SDK_INT >= 33) {
                    if (svc.performGlobalAction(GA_DPAD_CENTER)) return;
                } else if (Build.VERSION.SDK_INT >= 31) {
                    if (svc.performGlobalAction(GA_HEADSETHOOK)) return;
                }
            } catch (Throwable ignored) {
            }
        }
        // Rückfall: Medien-Sitzung
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
        Protokoll.add(this, "Dienst vom System beendet");
        running = false;
        instance = null;
        unregisterNetworkWatch();
        stopKeepAlive();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        stopKeepAlive();
        super.onDestroy();
    }
}
