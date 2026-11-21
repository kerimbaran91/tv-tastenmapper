package de.kerim.tvmapper;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Prüft regelmäßig (alle 15 Min.) und kurz nach dem Start, ob der Dienst läuft. */
public class Watchdog extends JobService {
    private static final int JOB_PERIODIC = 1001;
    private static final int JOB_SOON = 1002;
    private static final int JOB_LATER = 1003;

    /** Regelmäßige Prüfung einplanen (übersteht Neustarts). */
    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null || js.getPendingJob(JOB_PERIODIC) != null) return;
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, Watchdog.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    /** Einmalige Prüfung in ca. 45 Sekunden (nach Neustart / Update). */
    static void scheduleSoon(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(JOB_SOON, new ComponentName(c, Watchdog.class))
                    .setMinimumLatency(45_000)
                    .setOverrideDeadline(120_000)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    /** Zweite Prüfung ca. 3 Minuten nach dem Start (falls WLAN länger braucht). */
    static void scheduleLater(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(JOB_LATER, new ComponentName(c, Watchdog.class))
                    .setMinimumLatency(180_000)
                    .setOverrideDeadline(300_000)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            try {
                Healer.ensure(getApplicationContext(), true);
                schedule(getApplicationContext());
                Prefs.load(getApplicationContext());
                if (Prefs.method == Prefs.METHOD_REAL) RealKey.ensure(getApplicationContext());
            } catch (Throwable ignored) {
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
