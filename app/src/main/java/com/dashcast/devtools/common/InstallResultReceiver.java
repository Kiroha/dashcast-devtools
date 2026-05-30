package com.dashcast.devtools.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import com.dashcast.devtools.R;

/**
 * Receives the result of a PackageInstaller session commit.
 * With INSTALL_PACKAGES (granted via platform.keystore), the install is silent.
 * STATUS_PENDING_USER_ACTION is the fallback when silent install is not permitted.
 */
public class InstallResultReceiver extends BroadcastReceiver {

    private static final String TAG = "InstallResultReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_SUCCESS) {
            AppLogger.i(TAG, "OTA install successful");
        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmIntent);
            }
        } else {
            String displayMsg = (message != null && !message.isEmpty()) ? message : "code=" + status;
            AppLogger.e(TAG, "OTA install failed: status=" + status + " msg=" + message);
            Toast.makeText(context,
                    context.getString(R.string.ota_install_failed_fmt, displayMsg),
                    Toast.LENGTH_LONG).show();
        }
    }
}
