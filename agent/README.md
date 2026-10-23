# AppFunctions Testing Agent

AppFunctions Testing Agent is a testing and debugging tool for Android AppFunctions.
It allows developers to troubleshoot integration issues, manually invoke functions with
deterministic input, and test functions using an LLM-based agent across text chat, real-time
**Gemini Live (`BidiGenerateContent`)** voice/multimodal sessions, and **Android XR Smart Glasses**.

## Multi-Modal & Multi-Form-Factor Architecture

The Testing Agent is designed to work across Android form factors while keeping the vanilla text
chat experience front and center on mobile:

-   **Vanilla Text Chat (Default)**: Standard turn-based chat agent (`AgentDemoScreen`) that
    discovers and invokes `@AppFunction` tools across connected apps (such as `ChatApp`).
-   **Gemini Live on Mobile (`Live` Icon)**: Real-time bidirectional voice mode powered by the
    Gemini Live WebSocket API (`models/gemini-3.1-flash-live-preview`). When glasses are
    disconnected, users can tap the **Live** icon in the top bar of either `agent` or `ChatApp` to
    invoke any discovered `@AppFunction` by voice with `<15ms` barge-in interruption and automatic
    tool execution.
-   **Android XR Audio & Display Glasses (`Glasses` Icon & Projected Activity)**:
    -   Observes `ProjectedContext.isProjectedDeviceConnected` to enable the **Glasses** icon in the
        top bar when Android XR glasses are connected (and disables it when glasses are off).
    -   Provides `GlassesAgentActivity` (`android:requiredDisplayCategory="xr_projected"` and
        `androidx.xr.projected.CATEGORY_PROJECTED`) built with **Jetpack Glimmer** (`GlimmerTheme`
        on pure `#000000` additive waveguide background) and **Jetpack Projected** hardware routing
        (`AudioRecord.Builder().setContext(projectedDeviceContext)` for temple microphones and
        `CameraX` `640x480` snapshots for point-of-view visual context).
-   **Holistic Form-Factor Support (`DeviceCapabilityRepository`)**: Adapts system instructions,
    audio routing, and local hardware tools across `HANDSET`, `TABLET`, `WEAR`, `AUTOMOTIVE`, `TV`,
    `XR_HEADSET`, `XR_GLASSES_AUDIO`, and `XR_GLASSES_DISPLAY`.

## Prerequisites

-   **ADB**: Ensure you have `adb` installed and in your PATH.
-   **Device/Emulator**: A connected Android device or emulator with developer options enabled.

## Building and Running

To build, install, and launch the app with elevated privileges (required for some AppFunctions APIs),
use the `run_privileged.sh` script.

### Usage

```bash
./run_privileged.sh [OPTIONS]
```

### Options

-   `-b, --build`: Force a rebuild of the app before installing.
-   `-r, --release`: Use the release build type instead of debug.
-   `-f, --flavor FLAVOR`: Specify the build flavor (`standard` or `retail`). Defaults to `standard`.
-   `-k, --api-key KEY`: The Gemini API key (required for `retail` builds if not set elsewhere).
-   `-h, --help`: Show the help message.

### Examples

**Standard Debug Build**
```bash
./run_privileged.sh --build
```

**Retail Debug Build with API Key**
```bash
./run_privileged.sh --build --flavor retail --api-key YOUR_API_KEY
```

## Testing

Always run relevant tests to verify your changes before committing.

### Unit Tests
Run unit tests for all variants:
```bash
./gradlew test
```
Or for a specific variant:
```bash
./gradlew testStandardDebugUnitTest
./gradlew testRetailDebugUnitTest -PGEMINI_API_KEY=YOUR_API_KEY
```

### Instrumented Tests
Run instrumented tests on a connected device:
```bash
./gradlew connectedAndroidTest
```
*Note: Some instrumented tests require a Gemini API key.
Pass it using `-Pandroid.testInstrumentationRunnerArguments.gemini_api_key=YOUR_API_KEY`.*

### Screenshot Tests
Verify UI changes with screenshot tests:
```bash
./gradlew updateDebugScreenshotTest
```

## Releasing (Bundling)

To create a distributable test suite bundle, use the `bundle.sh` script.
This script creates a zip file containing the APK, instructions, and a launch script.

### Usage

```bash
./bundle.sh [OPTIONS]
```

### Options

-   `-o, --output-dir DIR`: Directory to save the bundle (default: current directory).
-   `-r, --release`: Build the release version instead of debug.
-   `-f, --flavor FLAVOR`: Specify the build flavor (`standard` or `retail`). Defaults to `standard`.
-   `-k, --api-key KEY`: The Gemini API key (required for `retail` builds).

### Examples

**Bundle Standard Debug Version**
```bash
./bundle.sh
```

**Bundle Retail Debug Version**
```bash
./bundle.sh --flavor retail --api-key YOUR_API_KEY
```
