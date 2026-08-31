# Pure Watch Collector v0.1

Android calibration build for collecting product-list text that is already visible in the user's normally logged-in 奢当家 account.

- No password capture.
- No TLS interception or API-signature bypass.
- AccessibilityService is restricted to `com.sdangjs.android`.
- Two modes: auto-navigation and current-page collection.
- Default cap: 50 candidate cards.
- Export path: `Downloads/PureWatchCollector/` as JSON.

The first phone run is for calibrating the actual card structure before adding hourly scheduling or any Pure Watch server sync.
