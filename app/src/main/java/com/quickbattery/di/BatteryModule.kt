package com.quickbattery.di

import com.quickbattery.data.provider.AndroidBatteryDataProvider
import com.quickbattery.data.provider.BatteryDataProvider
import com.quickbattery.data.repository.BatteryRepositoryImpl
import com.quickbattery.domain.repository.BatteryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BatteryModule {

    @Binds
    @Singleton
    abstract fun bindBatteryDataProvider(
        provider: AndroidBatteryDataProvider,
    ): BatteryDataProvider

    @Binds
    @Singleton
    abstract fun bindBatteryRepository(
        repository: BatteryRepositoryImpl,
    ): BatteryRepository
}
