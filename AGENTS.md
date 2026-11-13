# Workspace Rules (`MyBookFreeApp`)

## Gradle Builds & Android CLI Execution (Windows Sandbox)

- **Never run Gradle (`gradlew`, `gradlew.bat`, `./gradlew`) inside the terminal sandbox (`BypassSandbox: false`)** in this workspace. On Windows, sandboxed Gradle commands cannot reach `~/.gradle`, the Android SDK, or the Gradle Daemon loopback socket and will hang indefinitely or fail.
- When a Gradle build, test, or compilation check is needed, either:
  1. **Run Gradle outside the sandbox (`BypassSandbox: true`)** (which requests user approval), or
  2. **Skip sandboxed Gradle runs** and rely on static code review / let the user build in Android Studio if unsandboxed execution is not needed.
- Similarly, if invoking external host tools outside the workspace such as `android.exe` (`C:\Users\Yahia\AppData\AndroidCLI\android.exe`) or `adb`, run them with `BypassSandbox: true` rather than inside the sandbox.
