package com.example.freebook.model

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.TextAlign as ReadiumTextAlign
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme
import org.readium.r2.shared.ExperimentalReadiumApi
import java.util.UUID
import kotlin.math.abs

enum class AnnotationType {
    HIGHLIGHT,
    NOTE,
    BOOKMARK
}

enum class LibraryFilter(val label: String) {
    ALL("All"),
    CONTINUE("Continue"),
    FINISHED("Finished")
}

/**
 * Distinguishes reflowable publications (font-size multiplier steps, reflow layout)
 * from fixed-layout publications (pinch-to-zoom + pan + fit modes).
 */
enum class PublicationLayoutType {
    REFLOWABLE,
    FIXED
}

/**
 * Publication-driven reading direction (from OPF `page-progression-direction` and `<dc:language>`).
 * Never forced by the device system locale.
 */
enum class BookReadingDirection {
    LTR,
    RTL
}

/**
 * User-selectable navigation mode for reflowable books.
 * Horizontal Swipe (Pages) is the default.
 */
enum class ReaderNavigationMode(val label: String) {
    HORIZONTAL_SWIPE("Horizontal Swipe (Pages)"),
    VERTICAL_SCROLL("Scrolling")
}

/**
 * Fit modes exclusively for fixed-layout publications (`PublicationLayoutType.FIXED`).
 */
enum class FixedLayoutFitMode(val label: String) {
    FIT_WIDTH("Fit Width"),
    FIT_PAGE("Fit Page"),
    ACTUAL_SIZE("Actual Size")
}

/**
 * Text alignment options for reflowable publications.
 */
enum class ReaderTextAlign(val label: String) {
    JUSTIFY("Justify"),
    START("Align Start")
}

/**
 * Standard 4 reading color themes wired through Readium Navigator Preferences and the reader UI.
 */
enum class ReaderTheme(
    val label: String,
    val subtitle: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val isDarkSurface: Boolean
) {
    LIGHT(
        label = "Light",
        subtitle = "Daytime white",
        backgroundArgb = 0xFFFFFFFF,
        textArgb = 0xFF1A1A1A,
        isDarkSurface = false
    ),
    SEPIA(
        label = "Sepia",
        subtitle = "Warm cream",
        backgroundArgb = 0xFFF4ECD8,
        textArgb = 0xFF5B4636,
        isDarkSurface = false
    ),
    DARK(
        label = "Dark",
        subtitle = "Night reading",
        backgroundArgb = 0xFF121212,
        textArgb = 0xFFE0E0E0,
        isDarkSurface = true
    ),
    AMOLED(
        label = "Black / AMOLED",
        subtitle = "OLED low light",
        backgroundArgb = 0xFF000000,
        textArgb = 0xFFB0B0B0,
        isDarkSurface = true
    );

    companion object {
        fun fromName(name: String?): ReaderTheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DARK
    }
}

/**
 * 8 discrete geometric (~1.2x) font-size multiplier steps on a 16sp base size.
 * Shows friendly names in the UI while storing the multiplier.
 */
enum class ReaderFontSizeStep(
    val stepNumber: Int,
    val multiplier: Float,
    val shortLabel: String,
    val friendlyName: String
) {
    STEP_1(1, 0.8f, "XS", "Small"),
    STEP_2(2, 0.9f, "S", "Compact"),
    STEP_3(3, 1.0f, "M", "Medium (Default)"),
    STEP_4(4, 1.15f, "L", "Large"),
    STEP_5(5, 1.3f, "XL", "XL"),
    STEP_6(6, 1.5f, "XXL", "XXL"),
    STEP_7(7, 1.75f, "3XL", "3XL"),
    STEP_8(8, 2.0f, "4XL", "Max");

    val computedSp: Float
        get() = BASE_FONT_SIZE_SP * multiplier

    companion object {
        const val BASE_FONT_SIZE_SP = 16f

        fun fromMultiplier(value: Float): ReaderFontSizeStep =
            entries.minByOrNull { abs(it.multiplier - value) } ?: STEP_3
    }
}

/**
 * Highlight color choices available in the floating contextual action bar when covering words.
 */
enum class HighlightColor(
    val label: String,
    val solidArgb: Long,
    val markerBgDarkArgb: Long,
    val markerBgLightArgb: Long
) {
    YELLOW("Yellow", 0xFFFACC15, 0x55FACC15, 0x77FDE047),
    GREEN("Green", 0xFF4ADE80, 0x554ADE80, 0x7786EFAC),
    BLUE("Blue", 0xFF60A5FA, 0x5560A5FA, 0x7793C5FD),
    PINK("Pink", 0xFFF472B6, 0x55F472B6, 0x77F9A8D4),
    ORANGE("Orange", 0xFFFB923C, 0x55FB923C, 0x77FDBA74);

    companion object {
        fun fromName(name: String?): HighlightColor =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: YELLOW
    }
}

data class ReaderPreferences(
    val isDarkTheme: Boolean = true,
    val readerTheme: ReaderTheme = ReaderTheme.DARK,
    val navigationMode: ReaderNavigationMode = ReaderNavigationMode.HORIZONTAL_SWIPE,
    val fontSizeMultiplier: Float = ReaderFontSizeStep.STEP_3.multiplier,
    val lineHeightMultiplier: Float = 1.5f,
    val pageMarginDp: Int = 24,
    val textAlignment: ReaderTextAlign = ReaderTextAlign.JUSTIFY,
    val publisherStyles: Boolean = false,
    val fixedLayoutFitMode: FixedLayoutFitMode = FixedLayoutFitMode.FIT_PAGE,
    val enablePageAnimations: Boolean = true
) {
    val fontSizeStep: ReaderFontSizeStep
        get() = ReaderFontSizeStep.fromMultiplier(fontSizeMultiplier)

    val fontSizeSp: Float
        get() = fontSizeStep.computedSp

    /**
     * Maps user settings directly to Readium's `EpubPreferences` API.
     */
    @OptIn(ExperimentalReadiumApi::class)
    fun toReadiumEpubPreferences(): EpubPreferences {
        val readiumTheme = when (readerTheme) {
            ReaderTheme.LIGHT -> ReadiumTheme.LIGHT
            ReaderTheme.SEPIA -> ReadiumTheme.SEPIA
            ReaderTheme.DARK, ReaderTheme.AMOLED -> ReadiumTheme.DARK
        }
        val readiumAlign = when (textAlignment) {
            ReaderTextAlign.JUSTIFY -> ReadiumTextAlign.JUSTIFY
            ReaderTextAlign.START -> ReadiumTextAlign.START
        }
        return EpubPreferences(
            fontSize = fontSizeStep.multiplier.toDouble(),
            theme = readiumTheme,
            backgroundColor = ReadiumColor(readerTheme.backgroundArgb.toInt()),
            textColor = ReadiumColor(readerTheme.textArgb.toInt()),
            lineHeight = lineHeightMultiplier.toDouble(),
            pageMargins = (pageMarginDp / 16.0),
            textAlign = readiumAlign,
            publisherStyles = publisherStyles,
            scroll = (navigationMode == ReaderNavigationMode.VERTICAL_SCROLL)
        )
    }
}

data class BookAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val type: AnnotationType,
    val pageNumber: Int,
    /** Stable start character-offset locator into the canonical book text. */
    val charOffset: Int = 0,
    /** Stable end character-offset locator into the canonical book text (for exact range highlights). */
    val endCharOffset: Int = 0,
    val text: String,
    /** Covered quote text specifically associated with a NOTE (when created from covered words). */
    val quotedText: String = "",
    val highlightColorName: String = HighlightColor.YELLOW.name,
    val timestampLabel: String = "Added today",
    val createdAt: Long = System.currentTimeMillis()
) {
    val highlightColor: HighlightColor
        get() = HighlightColor.fromName(highlightColorName)
}

/**
 * Lightweight domain representation of a book.
 * Reading position is persisted as a stable locator (`readingCharOffset` + `locatorJson` + `readingProgress`),
 * independent of any specific text layout or font-size step.
 */
data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val contentHash: String = "",
    val bookFilePath: String = "",
    val textContentFilePath: String = "",
    val coverImagePath: String? = null,
    val currentPageIndex: Int = 0,
    val totalPages: Int = 1,
    /** Stable canonical character offset locator into the book content. */
    val readingCharOffset: Int = 0,
    /** Serialized Readium Locator JSON (`href`, `progression`, `totalProgression`, `charOffset`). */
    val locatorJson: String = "",
    val readingProgress: Float = 0f,
    val layoutType: PublicationLayoutType = PublicationLayoutType.REFLOWABLE,
    val readingDirection: BookReadingDirection = BookReadingDirection.LTR,
    val language: String = "en",
    val isFinished: Boolean = false,
    val isStarted: Boolean = false,
    val isCustomNoteEntryOnly: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = System.currentTimeMillis()
) {
    val currentDisplayPage: Int
        get() = (currentPageIndex + 1).coerceIn(1, totalPages.coerceAtLeast(1))

    /**
     * Resolves the saved canonical character offset from `readingCharOffset`, falling back to
     * parsing `charOffset` or `totalProgression` from `locatorJson` if needed.
     */
    fun resolveSavedCharOffset(totalChars: Int): Int {
        val safeTotal = totalChars.coerceAtLeast(1)
        if (readingCharOffset > 0) {
            return readingCharOffset.coerceIn(0, safeTotal - 1)
        }
        if (locatorJson.isNotBlank()) {
            val charOffsetMatch = Regex("\"charOffset\"\\s*:\\s*(\\d+)").find(locatorJson)
            val parsedOffset = charOffsetMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (parsedOffset != null && parsedOffset > 0) {
                return parsedOffset.coerceIn(0, safeTotal - 1)
            }
            val progMatch = Regex("\"totalProgression\"\\s*:\\s*([0-9.]+)").find(locatorJson)
            val parsedProg = progMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (parsedProg != null && parsedProg > 0.0) {
                return (parsedProg * safeTotal).toInt().coerceIn(0, safeTotal - 1)
            }
        }
        if (readingProgress > 1f) {
            return ((readingProgress / 100f) * safeTotal).toInt().coerceIn(0, safeTotal - 1)
        }
        return 0
    }

    val progressPercentage: Float
        get() {
            if (isFinished) return 100f
            if (!isStarted && readingCharOffset == 0 && readingProgress <= 0f) return 0f
            if (readingProgress > 0f) return readingProgress.coerceIn(1f, 99.9f)
            return 0f
        }

    val statusText: String
        get() = when {
            isFinished -> "Finished"
            !isStarted && readingCharOffset == 0 && readingProgress <= 0f -> "New"
            else -> "${progressPercentage.toInt()}%"
        }
}

/**
 * Staged files and Readium-extracted metadata for a newly picked book before committing to Room.
 */
data class StagedBookImport(
    val title: String,
    val author: String,
    val normalizedTitle: String,
    val normalizedAuthor: String,
    val contentHash: String,
    val rawFilePath: String,
    val textContentFilePath: String,
    val coverImagePath: String?,
    val estimatedTotalPages: Int,
    val normalizedCharLength: Int,
    val layoutType: PublicationLayoutType = PublicationLayoutType.REFLOWABLE,
    val readingDirection: BookReadingDirection = BookReadingDirection.LTR,
    val language: String = "en"
)

sealed interface BookImportResult {
    data class Imported(val book: Book) : BookImportResult
    data class ExactDuplicateSilentBlock(val existingBook: Book) : BookImportResult
    data class FuzzyDuplicateRequiresConfirmation(
        val stagedImport: StagedBookImport,
        val existingBook: Book,
        val reason: String
    ) : BookImportResult
    data class Error(val message: String) : BookImportResult
}
