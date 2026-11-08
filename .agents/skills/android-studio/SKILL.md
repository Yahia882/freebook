---
name: android-studio
description: >-
  Connects to the running Android Studio instance and Android CLI tools for project
  analysis, Kotlin/Compose file diagnostics, symbol navigation (declarations and usages),
  Compose preview rendering, and Maven library version lookups for MyBookFreeApp.
---

# Android Studio Integration Skill (`MyBookFreeApp`)

Use the `android` CLI (`C:\Users\Yahia\AppData\AndroidCLI\android.exe`) to interact directly with the running Android Studio instance (`Quail 4 | 2026.1.4 Patch 1`) and inspect or build `MyBookFreeApp`.

## Core Android Studio Commands

1. **Check Android Studio Connection & Project Readiness**
   ```powershell
   C:\Users\Yahia\AppData\AndroidCLI\android.exe studio check
   ```
   Verifies that Android Studio is running and `freebook` (`C:/Users/Yahia/AndroidStudioProjects/MyBookFreeApp`) is in the `READY` state.

2. **Analyze a Kotlin / XML / Gradle File for Errors & Warnings**
   ```powershell
   C:\Users\Yahia\AppData\AndroidCLI\android.exe studio analyze-file <path-to-file>
   ```
   Runs Android Studio's live Kotlin compiler and lint inspections on the specified file. Run this after editing Kotlin source files to catch compilation or lint issues immediately.

3. **Find Symbol Declarations & Usages**
   ```powershell
   C:\Users\Yahia\AppData\AndroidCLI\android.exe studio find-declaration <args>
   C:\Users\Yahia\AppData\AndroidCLI\android.exe studio find-usages <args>
   ```
   Uses Android Studio's PSI index to locate declarations and references across the project and dependencies.

4. **Open a File in Android Studio**
   ```powershell
   C:\Users\Yahia\AppData\AndroidCLI\android.exe studio open-file <path-to-file>
   ```

5. **Render Jetpack Compose Previews**
   - Via Android Studio:
     ```powershell
     C:\Users\Yahia\AppData\AndroidCLI\android.exe studio render-compose-preview <args>
     ```
   - Standalone CLI preview renderer (also integrates with the Android Compose Preview sidecar pane):
     ```powershell
     C:\Users\Yahia\AppData\AndroidCLI\android.exe preview list
     C:\Users\Yahia\AppData\AndroidCLI\android.exe preview render
     ```

6. **Look Up Latest Library & SDK Versions**
   ```powershell
   C:\Users\Yahia\AppData\AndroidCLI\android.exe studio version-lookup <artifact-or-query>
   ```

7. **Describe, Build, and Run the Project**
   ```powershell
   C:\Users\Yahia\AppData\AndroidCLI\android.exe describe
   C:\Users\Yahia\AppData\AndroidCLI\android.exe build
   C:\Users\Yahia\AppData\AndroidCLI\android.exe run
   ```
