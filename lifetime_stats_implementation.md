Implement a new feature called "Battery Lifetime Statistics".

The feature should be accessible from a small Material 3 "Info" icon (Icons.Outlined.Info) aligned to the far right of the Battery Summary card header.

The icon should be visually discreet while remaining accessible.

When pressed:

• If no purchase date has ever been configured, immediately display a Material 3 Date Picker dialog requesting the purchase date.

• If the purchase date already exists, open the Battery Lifetime screen directly.

The purchase date must always be editable.

Inside the Battery Lifetime screen provide an Edit Purchase Date button that opens the same Material 3 Date Picker.

Store the purchase date using DataStore so it persists across application restarts.

Never require background services.

Never collect historical data.

Everything must be calculated using only:

- Purchase Date
- Current Date
- Current Charge Cycles
- Battery Health
- Current Battery APIs already exposed by BatteryDataProvider

The feature must gracefully degrade when Charge Cycles or Battery Health are unavailable.

Display "Unavailable" instead of hiding cards.

-------------------------------------------------------

Create a new screen named:

Battery Lifetime

The screen should follow the same Material 3 design language already used throughout the application.

Use rounded Material cards.

Large typography.

Comfortable spacing.

Smooth Material animations.

The screen should contain the following sections.

-------------------------------------------------------

PHONE AGE

Display:

Phone Age

Examples:

184 days

or

6 months 3 days

Also display:

Purchase Date

Current Date

-------------------------------------------------------

CHARGING HABITS

Calculate:

Average Charge Cycles Per Day

Example

0.20/day

Average Charge Cycles Per Month

Example

6.1/month

Estimated Annual Charge Cycles

Example

72/year

Average Days Between Charge Cycles

Example

5.1 days

-------------------------------------------------------

BATTERY LIFETIME PROJECTION

Using the current charging rate estimate:

Projected 500th Charge Cycle

Projected 800th Charge Cycle

Projected 1000th Charge Cycle

Display both:

Expected Date

Remaining Years

Example

500th Cycle

May 2033

6.8 years remaining

These are projections only.

Clearly indicate that they are estimates.

-------------------------------------------------------

BATTERY HEALTH

If Battery Health exists display:

Battery Health

Charge Cycles

Phone Age

Estimated Usage Rate

Display an interpretation.

Possible values:

Excellent

Very Good

Normal

Heavy Usage

Very Heavy Usage

This interpretation should be based primarily on:

cycles/day

and

Battery Health

Never invent degradation percentages.

Never pretend to know future battery health.

Only provide qualitative interpretations.

-------------------------------------------------------

BATTERY USAGE PROFILE

Classify the user according to average cycles/day.

Suggested thresholds:

<0.25

Very Light User

0.25–0.50

Light User

0.50–0.90

Average User

0.90–1.30

Heavy User

>1.30

Power User

Show both:

Classification

Small explanation

Example

"You charge your phone less frequently than most users."

-------------------------------------------------------

BATTERY FACTS

Create a Material card titled:

Battery Facts

Generate several dynamic facts.

Examples:

"You have averaged 6.2 charge cycles per month."

"Your battery has completed only 36 full charge cycles."

"Your phone averages one full charge every 5.1 days."

"At your current usage, reaching 500 charge cycles will take approximately 6.8 more years."

"Your battery is still in the early part of its expected service life."

Randomize or rotate these facts on every screen load.

Never generate misleading facts.

Every displayed sentence must be directly derived from available data.

-------------------------------------------------------

LIFETIME TIMELINE

Create a vertical Material timeline.

Display:

Purchase Date

↓

Current Date

↓

Current Charge Cycle

↓

Projected 500th Cycle

↓

Projected 800th Cycle

↓

Projected 1000th Cycle

Animate the timeline when the screen opens.

-------------------------------------------------------

EDIT PURCHASE DATE

At the bottom of the screen provide:

Edit Purchase Date

Opening the Material Date Picker again.

Recalculate every statistic immediately after saving.

-------------------------------------------------------

Architecture Requirements

Create a new:

LifetimeStatisticsCalculator

This class should contain all calculations.

No calculations should exist inside the ViewModel.

The ViewModel should only coordinate data.

The Repository should only retrieve data.

The UI should only render immutable UI models.

Use dependency injection.

Unit-test every calculation independently.

-------------------------------------------------------

UI Requirements

Continue using:

Jetpack Compose

Material 3

Dynamic Colors

Edge-to-edge

Rounded Cards

AnimatedContent

Crossfade

Skeleton placeholders while calculations are performed.

Maintain the same premium look already implemented in the application.

The new screen should feel like a natural extension of the existing Battery Summary screen and match the visual quality of Samsung OneUI while remaining an original implementation.
