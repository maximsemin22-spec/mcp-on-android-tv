package io.middlepoint.mcponandroid

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.middlepoint.mcponandroid.mcp.McpService

/**
 * Optional Android TV HOME activity that forwards to KinoWatch.
 * Install/update the APK first, then explicitly select this as the default HOME.
 * The original Android TV launcher remains installed and can be restored through ADB.
 */
class KinoHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the MCP endpoint available even if users never open the MCP UI after boot.
        try {
            ContextCompat.startForegroundService(this, Intent(this, McpService::class.java))
        } catch (exception: Exception) {
            Log.w("KinoHomeActivity", "Could not start MCP service", exception)
        }

        val kinoWatch = packageManager.getLeanbackLaunchIntentForPackage("watch.kino")
            ?: packageManager.getLaunchIntentForPackage("watch.kino")

        if (kinoWatch != null && launch(kinoWatch)) {
            finish()
            return
        }

        Log.w("KinoHomeActivity", "KinoWatch unavailable, opening stock Android TV Home")
        val googleHome = Intent(Intent.ACTION_MAIN)
            .setClassName("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!launch(googleHome)) {
            launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun launch(intent: Intent): Boolean =
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (exception: Exception) {
            Log.w("KinoHomeActivity", "Could not launch ${intent.component}", exception)
            false
        }
}
