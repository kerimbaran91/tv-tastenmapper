package de.kerim.tvmapper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Rückmeldung vom System-Installer. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(context, "Update installiert", Toast.LENGTH_LONG).show();
            } else if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context, "Update fehlgeschlagen" + (msg != null ? ": " + msg : ""),
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignored) {
        }
    }
}
