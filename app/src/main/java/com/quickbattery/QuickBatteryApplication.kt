package com.quickbattery

import android.app.Application
import com.quickbattery.data.provider.BatteryMonitorService
import com.quickbattery.data.provider.MonitorWatchdog
import dagger.hilt.android.HiltAndroidApp

/**
 * Any wake-up of this process - the launcher, a power broadcast, boot, the watchdog alarm - is an
 * opportunity to get monitoring running again, so both are (re)started here.
 *
 * Deliberately no reconcile pass: Application.onCreate runs before the receiver that woke the
 * process, so repairing here would guess at the very event that is about to be reported exactly,
 * and the guess would win. Repair belongs to the entry points that are not racing a live broadcast
 * (the monitor service, the watchdog, boot, and opening the app).
 */
@HiltAndroidApp
class QuickBatteryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BatteryMonitorService.start(this)
        MonitorWatchdog.schedule(this)
    }
}
