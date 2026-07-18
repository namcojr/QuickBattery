# Copilot Instructions — Battery Insights

## Project Overview

This project is a native Android application written in Kotlin using **Jetpack Compose** and **Material 3 (Material You / Material 3 Expressive)**.

The goal is to create a beautiful battery information application inspired by Samsung OneUI's Battery page while remaining completely compatible with any Android device using only **public Android APIs**.

The application should feel like something Google or Samsung could have shipped.

---

# Philosophy

This application is **not** a battery monitor.

It is a **stateless battery information utility**.

It must never perform background monitoring, schedule work, collect battery history while closed or consume measurable battery itself.

The application lifecycle is:

Open App

↓

Query Android APIs

↓

Calculate statistics

↓

Display UI

↓

Exit

Nothing should continue running after the application closes.

---

# Core Principles

Always prioritize:

- Clean Architecture
- SOLID principles
- Readability
- Maintainability
- Extensibility
- Production-quality code
- Kotlin idioms
- Material 3 best practices

Never generate "quick fixes" if a clean architecture solution exists.

---

# Technology Stack

Always use:

- Kotlin
- Jetpack Compose
- Material 3
- Dynamic Color
- MVVM
- Repository Pattern
- StateFlow
- Coroutines
- Dependency Injection (Hilt preferred)

Never generate XML layouts.

Everything should be Compose.

---

# Architecture

Always keep the application separated into layers.

```
UI

↓

ViewModel

↓

Repository

↓

BatteryDataProvider (interface)

↓

AndroidBatteryDataProvider
```

The UI must never communicate directly with Android framework classes.

Only the provider implementation may access:

- BatteryManager
- UsageStatsManager
- ACTION_BATTERY_CHANGED
- PackageManager
- Intent extras
- Android system services

Every Android API interaction should remain inside the provider layer.

---

# Extensibility

Battery information must always be exposed through interfaces.

Future Android versions may expose richer battery APIs.

Replacing the provider should require **zero modifications** to the UI.

Design for future Android versions.

---

# Supported APIs

Use only public Android APIs.

Never use:

- Reflection
- Hidden APIs
- Root
- ADB
- OEM private APIs
- BATTERY_STATS permission
- System-only permissions

Gracefully degrade when information is unavailable.

Never crash because a device does not expose a metric.

Display:

Unavailable

instead.

---

# Battery Runtime Estimation

Estimate battery runtime from the information available from Android.

Always normalize partial discharge sessions into a theoretical 100% → 0% runtime.

Example:

Battery

90%

↓

30%

Consumed = 60%

Elapsed = 18 hours

Estimated Runtime

18 / 60 × 100

= 30 hours

Remaining time should always be derived from:

Estimated Full Runtime × Current Battery Percentage

Never fake values.

Never invent data.

---

# UI Design

The application should feel premium.

Use:

- Material 3
- Dynamic Colors
- Rounded Cards (~24dp)
- Large typography
- Edge-to-edge layout
- Material Motion
- Adaptive layouts
- Proper spacing
- Smooth animations

The UI should resemble Samsung OneUI while remaining an original implementation.

Never clone Samsung assets.

---

# Screen Layout

The main screen is a vertically scrolling LazyColumn.

Cards:

## Card 1

Battery Summary

Display:

- Remaining battery time
- Battery percentage
- Animated pill battery indicator
- Estimated full runtime

---

## Card 2

Since Last Charge

Display:

- Time since charging stopped
- Battery Health
- Charge Cycles

Unavailable metrics should display:

Unavailable

---

## Card 3

Battery Insights

Display every battery metric available from public APIs including:

- Battery Health
- Charge Cycles
- Battery Status
- Charging Source
- Voltage
- Temperature
- Technology
- Battery Current
- Average Current
- Energy Counter
- Capacity
- Battery Saver status
- Charging state
- Charging speed (when inferable)
- Remaining Runtime
- Estimated Full Runtime

Display only metrics supported by the current device.

---

## Card 4

Application Battery Usage

Display:

- App icon
- App name
- Screen-on time
- Estimated battery contribution

Initially show only the first ten applications.

Provide:

Show All

to expand the list.

---

# Loading Experience

The application should never display an empty screen while loading.

Immediately render the card layout using Material 3 placeholder skeletons.

Use shimmer or placeholder animations while:

- querying Android
- calculating statistics
- loading application icons

Replace placeholders with animated content once available.

The transition should feel polished.

---

# Compose Guidelines

Prefer:

- Small composables
- Stateless composables
- Immutable UI state
- remember only when necessary
- Stable models
- Derived state
- CompositionLocal only when appropriate

Avoid:

Large composables exceeding ~200 lines.

Split UI into reusable components.

---

# ViewModels

ViewModels should:

- expose immutable UI state
- never expose mutable state
- contain business logic
- never reference Android Views
- never contain UI code

---

# Repository

Repositories coordinate providers.

Repositories never perform UI logic.

Repositories return domain models.

---

# Provider

AndroidBatteryDataProvider is the only layer allowed to communicate with Android framework APIs.

If Android changes in the future, replacing this provider should be sufficient.

---

# Error Handling

Never allow crashes because a metric is unavailable.

Handle:

- Unsupported Android versions
- Missing permissions
- Null values
- Missing battery properties
- Manufacturer differences

Gracefully.

---

# Performance

The application should feel instant.

Avoid unnecessary recompositions.

Use:

- remember
- derivedStateOf
- LazyColumn
- immutable models

when appropriate.

Avoid allocations inside composables.

---

# Visual Quality

Animations should be subtle.

Prefer:

AnimatedContent

animateFloatAsState

animateDpAsState

Crossfade

Material Motion

Avoid flashy animations.

The application should feel calm, modern and premium.

---

# Code Style

Prefer expressive Kotlin.

Use:

Extension Functions

Data Classes

Sealed Classes

Value Classes

Enums

Avoid large utility classes.

Keep functions short.

Prefer composition over inheritance.

---

# Project Structure

```
app/

ui/
components/
screens/

viewmodel/

repository/

provider/

domain/

model/

theme/

util/
```

Each package should have a single responsibility.

---

# Documentation

Generate production-ready code.

Document public classes.

Document complex algorithms.

Explain battery estimation logic.

Write code as though it will be maintained for years.

---

# Final Goal

The finished application should feel like a polished first-party Android application.

The emphasis is on:

- Excellent architecture
- Beautiful UI
- Smooth animations
- Low battery usage
- Public Android APIs only
- Future-proof design
- High-quality Kotlin code