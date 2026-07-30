# Battery Insights

> A modern Material 3 battery information application for Android, inspired by Samsung One UI while remaining fully compatible with any Android device using public Android APIs.

---

## Overview

Battery Insights is a lightweight, stateless battery information utility built with **Kotlin**, **Jetpack Compose** and **Material 3**.

Unlike traditional battery monitoring applications, Battery Insights **does not run background services**, **does not continuously monitor battery usage**, and **does not consume additional battery while closed**.

Every time the application is opened it gathers the information currently available from Android, performs all calculations locally and presents them through a modern Material You interface.

The result is a beautiful, responsive application with virtually zero impact on battery life.

---

# Features

## Battery Summary

Displays:

- Estimated remaining battery time
- Current battery percentage
- Animated Samsung-inspired battery indicator
- Estimated full battery runtime (100% → 0%)
- Runtime normalized from partial discharge sessions when possible

---

## Since Last Charge

Displays:

- Time since charging stopped
- Battery Health
- Charge Cycles
- Graceful fallback when unsupported

---

## Battery Insights

Displays all battery information exposed through public Android APIs.

Including:

- Battery Health
- Charge Cycles
- Voltage
- Temperature
- Battery Technology
- Charging Status
- Charging Source
- Current Battery Level
- Estimated Remaining Runtime
- Estimated Full Runtime
- Battery Current (when available)
- Average Current
- Energy Counter
- Battery Capacity
- Battery Saver Status
- Adaptive Battery Status (when available)
- Charging Speed (when inferable)

Unavailable values are displayed as **Unavailable** rather than omitted.

---

## Application Battery Usage

Displays battery usage information for installed applications.

Includes:

- Application icon
- Application name
- Screen-on time
- Estimated battery contribution
- Expandable "Show All" list

---

# Battery Lifetime

Accessible from the information icon located in the Battery Summary card.

Provides long-term ownership statistics derived from:

- Purchase date
- Current date
- Charge cycles
- Battery health

No background monitoring is required.

---

## Phone Age

Displays:

- Purchase date
- Current date
- Device age
- Elapsed days
- Elapsed months

---

## Charging Habits

Calculates:

- Average charge cycles/day
- Average charge cycles/month
- Estimated yearly charge cycles
- Average days between charge cycles

---

## Battery Lifetime Projection

Projects future battery milestones.

Including:

- 500th charge cycle
- 800th charge cycle
- 1000th charge cycle

Displays:

- Estimated calendar date
- Remaining years

These are statistical projections based on current usage.

---

## Battery Health Summary

Displays:

- Battery Health
- Charge Cycles
- Phone Age
- Usage Rate

Provides qualitative assessments such as:

- Excellent
- Very Good
- Normal
- Heavy Usage
- Very Heavy Usage

---

## Battery Usage Profile

Automatically classifies the user.

Possible profiles include:

- Very Light User
- Light User
- Average User
- Heavy User
- Power User

Classification is based primarily on average daily charge cycles.

---

## Battery Facts

Generates contextual insights unique to the current device.

Examples:

- Average monthly charge cycles
- Average days between charges
- Current battery age
- Estimated lifetime projections
- Charge cycle milestones

Every fact is generated from available device data.

---

## Lifetime Timeline

Displays a visual timeline including:

- Purchase Date
- Current Date
- Current Charge Cycle
- Projected 500th Cycle
- Projected 800th Cycle
- Projected 1000th Cycle

---

# Design

Battery Insights follows modern Android design principles.

Features include:

- Material 3
- Material You
- Dynamic Colors
- Edge-to-edge layout
- Rounded cards
- Material Motion animations
- Responsive layouts
- Light and Dark themes

The interface is inspired by Samsung One UI while remaining an original implementation.

---

# Performance

Battery Insights was intentionally designed to have negligible battery impact.

The application:

- Does not run foreground services
- Does not use WorkManager
- Does not schedule alarms
- Does not collect battery history
- Does not monitor the battery while closed

All calculations occur only while the application is open.

---

# Architecture

The project follows Clean Architecture principles.

```
UI
    ↓
ViewModel
    ↓
Repository
    ↓
BatteryDataProvider
    ↓
Android Public APIs
```

Battery information is abstracted behind the `BatteryDataProvider` interface, making the application future-proof and easily extensible.

---

# Technology Stack

- Kotlin
- Jetpack Compose
- Material 3
- Material You
- MVVM
- StateFlow
- Kotlin Coroutines
- Hilt Dependency Injection
- Repository Pattern
- Clean Architecture

---

# Supported Android APIs

The application uses only public Android APIs.

Including:

- BatteryManager
- UsageStatsManager
- ACTION_BATTERY_CHANGED
- PackageManager

The application intentionally avoids:

- Hidden APIs
- Reflection
- Root
- ADB
- OEM private APIs
- System-only permissions

---

# Privacy

Battery Insights is designed with privacy in mind.

The application:

- Does not transmit data
- Does not require an internet connection
- Does not upload battery information
- Does not create user accounts
- Performs all calculations locally

Your battery information never leaves your device.

---

# Compatibility

Designed for:

- Android 10+
- Samsung One UI
- Google Pixel
- OPPO ColorOS
- OnePlus OxygenOS
- Xiaomi HyperOS
- Nothing OS
- Motorola
- Any Android device exposing public battery APIs

Some battery metrics may vary depending on the manufacturer and Android version.

Unavailable metrics are clearly indicated instead of estimated.

---

# Project Goals

Battery Insights aims to provide:

- Beautiful design
- Accurate battery information
- Cross-device compatibility
- Zero background battery drain
- Clean architecture
- Modern Android development practices

The project focuses on presenting meaningful battery statistics while respecting Android's security and privacy model.

---

# License

This project is released under the MIT License.

---

# Acknowledgements

The visual design is inspired by Samsung One UI's battery interface and Google's Material 3 design language.

Battery Insights is an independent project and is not affiliated with or endorsed by Samsung Electronics or Google.
