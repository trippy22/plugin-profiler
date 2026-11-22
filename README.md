# Plugin Profiler

A RuneLite sidebar for ranking plugins while you play. Click a column header to sort or a plugin name to select its row. Click **On** or press Space to toggle the selected plugin. RuneLite’s Configuration and Jagex Auth controls are read-only.

## Build and run

Use a JDK 11 installation or newer with the included Gradle wrapper. The project targets RuneLite 1.13.7 and Java 11.

```powershell
.\gradlew.bat build
.\gradlew.bat run
```

On macOS or Linux, use `./gradlew`. The `run` task loads the plugin in a RuneLite developer client. Open **Plugin Profiler** in the sidebar. The standalone plugin JAR is under `build/libs/`.

## Reading the ranking

**Share %** answers “How often did a sample of the main client thread point to this plugin?” A single long call can appear repeatedly. Many short calls can fit between samples. This approximates elapsed presence on this thread, including waits. Call counts, CPU usage, allocated memory, and milliseconds added to a frame require other measurements.

- **Last 60 sec** is a plugin’s percentage of all nonempty main-thread stack samples in the past minute. The default ordering puts running plugins first. A stopped plugin’s old hits age out of this view after one minute. **Session** includes all activity since the profiler started.
- The list includes zero-usage plugins by default. **Hide 0% Usage** hides rows that round to zero or have no value for the selected metric. The preference is saved in the RuneLite profile. Unattributed samples count toward the denominator and have no visible row. The sidebar displays aggregate usage only.
- **Slow-frame lift · 5 min** compares the plugin’s share of samples during slow and normal frames over the past five minutes. The difference is measured in percentage points. The profiler measures intervals between draw completions. A slow frame lasts at least 50 ms. Gaps over two seconds and samples without surrounding frame timestamps contribute only to overall activity. The panel shows a comparison once both frame groups have ten samples and the plugin has appeared in three distinct slow frames.
- For example, a 12% share during slow frames and a 3% share during normal frames gives +9 percentage points of lift. That association can help choose which plugin to investigate. Measuring a particular callback’s elapsed duration requires timing its entry and return. Estimating its effect on frame pacing requires a comparable run with it enabled and disabled.
- The sampler runs on the injected executor at randomized delays of 80–140 ms, including while the sidebar is closed. Opening the panel requests a fresh snapshot on the next sample. The visible ranking then refreshes about every five seconds. The internal RuneLite config key `pluginprofiler.panelRefreshSeconds` accepts 1–60 seconds and affects only the display. All observations stay in memory. Stopping the profiler clears its history.

## Attribution

The sampler starts empty and gets display names and configuration-group aliases from the live RuneLite roster. A plugin, overlay, or panel class can match a unique alias or a sufficiently distinctive approximate spelling. An exact plugin-entry caller can teach the sampler the identities of individual helper classes it actually calls in the same package.

New evidence updates earlier recent and session counts, including samples already outside the five-minute window. If two plugin callers establish conflicting ownership of a helper, helper-only samples become unattributed again. A plugin can receive credit only for periods in which it was active. An unqualified game class supplies no ownership evidence by itself, but a recognizable plugin caller on the same stack can supply it. These measurements include waits on the main thread and exclude worker-thread and GPU execution.

Method-level paths stay in the sampler’s five-minute history. The sidebar receives numeric aggregates. Session counters use distinct combinations of candidate class names, attribution evidence, and active plugins. Equivalent roster rebuilds reuse the same counter keys. New combinations can grow these in-memory tables until the profiler stops. The plugin sends no profiling data over the network and writes no profiling samples to config or log files. Local error logs can include plugin names and exception traces.

The repository includes the Gradle wrapper (including its JAR), build files, plugin metadata, Java source, and tests. Generated builds, compiled classes, IDE settings, local profiles, and diagnostic recordings are excluded by `.gitignore`.
