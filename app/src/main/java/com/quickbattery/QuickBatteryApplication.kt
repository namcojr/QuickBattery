package com.quickbattery

import android.app.Application
import com.quickbattery.data.provider.BatteryMonitorService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class QuickBatteryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BatteryMonitorService.start(this)
    }
}
