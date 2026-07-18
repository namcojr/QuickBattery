package com.quickbattery.domain.repository

import com.quickbattery.domain.model.BatteryReport

interface BatteryRepository {
    suspend fun getBatteryReport(): BatteryReport
}
