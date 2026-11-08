---
name: kotlin-android
description: >-
  Kotlin 2.2+ and Jetpack Compose development skill configured for the MyBookFreeApp
  (com.example.freebook) Android project. Use when writing, refactoring, or debugging
  Kotlin source code, Compose UI screens, coroutines/state persistence, or Gradle
  Kotlin DSL configurations in this project.
---

# Kotlin & Jetpack Compose Skill (`MyBookFreeApp`)

## Project Stack & Configuration

- **Namespace / Application ID**: `com.example.freebook`
- **Kotlin Version**: `2.2.10` (`gradle/libs.versions.toml`)
- **Android Gradle Plugin (AGP)**: `9.4.1`
- **SDK Levels**: `compileSdk = 37`, `targetSdk = 37`, `minSdk = 24`
- **UI Toolkit**: Jetpack Compose (`compose-bom = 2026.02.01`, Material 3)

## Architecture & Package Structure

- `com.example.freebook.MainActivity`: Single-activity entry point (`BookFreeApp`) using sealed interface navigation (`ScreenDestination`) and `enableEdgeToEdge()`.
- `com.example.freebook.model`: Immutable Kotlin `data class` and `enum class` models (`Book`, `BookAnnotation`, `AnnotationType`, `CoverStyle`).
- `com.example.freebook.data`:
  - `BookRepository`: Local state persistence (`PersistedAppState`).
  - `EpubParser`: EPUB archive and custom cover parsing executed on `Dispatchers.IO`.
- `com.example.freebook.ui.theme`: Custom editorial palette (`LocalBookFreePalette`) and `FreebookTheme` supporting light/dark modes.
- `com.example.freebook.ui.screens`: Screen-level `@Composable` functions (`LibraryScreen`, `BookReaderScreen`, `NotesListScreen`, `BookNotesDetailScreen`, `AboutScreen`).
- `com.example.freebook.ui.components`: Reusable `@Composable` UI building blocks (`BookFreeComponents.kt`).

## Kotlin & Compose Best Practices for This Project

1. **Coroutines & I/O**:
   - Run all file, URI, and EPUB parsing or persistence operations on `Dispatchers.IO` via `withContext(Dispatchers.IO)` or `coroutineScope.launch(Dispatchers.IO)`.
2. **State Hoisting & Immutability**:
   - Prefer immutable `data class` state updates (`.copy(...)`) and hoist state mutations (`onToggleTheme`, `onAddAnnotation`, `onPageChanged`) to `BookFreeApp` or screen state holders.
3. **Theme & Styling**:
   - Access colors via `LocalBookFreePalette.current` rather than hardcoding hex values in screens so light and dark themes stay consistent.
4. **Edge-to-Edge & Insets**:
   - Maintain `enableEdgeToEdge()` and `WindowInsets.safeDrawing` padding at the root container.
5. **Verification**:
   - After editing Kotlin or Compose files, verify compilation and IDE diagnostics using the `android-studio` skill (`android studio analyze-file`) or `./gradlew assembleDebug`.
