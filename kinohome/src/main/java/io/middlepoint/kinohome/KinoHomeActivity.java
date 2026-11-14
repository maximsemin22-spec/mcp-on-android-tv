package io.middlepoint.kinohome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

/**
 * Optional Android TV HOME replacement that immediately opens KinoWatch.
 * This helper is a separate package and does not alter the existing MCP app.
 */
public final class KinoHomeActivity extends Activity {
    private static final String TAG = "KinoHomeActivity";
    private static final String KINOWATCH_PACKAGE = "watch.kino";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent kinoWatch = getPackageManager().getLeanbackLaunchIntentForPackage(KINOWATCH_PACKAGE);
        if (kinoWatch == null) {
            kinoWatch = getPackageManager().getLaunchIntentForPackage(KINOWATCH_PACKAGE);
        }

        if (kinoWatch != null && tryLaunch(kinoWatch)) {
            finish();
            return;
        }

        // Emergency fallback if KinoWatch is deleted, disabled, or crashes on launch.
        Log.w(TAG, "KinoWatch launch unavailable, returning to standard TV Home");
        Intent stockHome = new Intent(Intent.ACTION_MAIN);
        stockHome.setClassName("com.google.android.tvlauncher",
                "com.google.android.tvlauncher.MainActivity");
        if (!tryLaunch(stockHome)) {
            tryLaunch(new Intent(Settings.ACTION_SETTINGS));
        }
        finish();
    }

    private boolean tryLaunch(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to launch " + intent, exception);
            return false;
        }
    }
}
