package com.quickbattery.data.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts monitoring after a reboot or an app update, so it resumes without the user having to
 * reopen the app.
 *
 * The reconcile pass matters as much as the restart here: the charger may well have been connected
 * or disconnected while the device was powered off or the new APK was being installed, and that
 * transition produced no broadcast anybody could have received.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                BatteryEventRecorder.reconcile(context)
                BatteryMonitorService.start(context)
                MonitorWatchdog.schedule(context)
            }
        }
    }
}
