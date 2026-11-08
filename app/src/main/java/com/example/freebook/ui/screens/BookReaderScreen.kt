package com.example.freebook.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.ReaderTextSize
import com.example.freebook.ui.pagination.PageLayout
import com.example.freebook.ui.components.BookmarkRibbonIcon
import com.example.freebook.ui.components.CloseCrossIcon
import com.example.freebook.ui.components.NotePencilIcon
import com.example.freebook.ui.components.SearchVectorIcon
import com.example.freebook.ui.pagination.ViewportPaginator
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookReaderScreen(
    book: Book,
    bookAnnotations: List<BookAnnotation>,
    fontSizeSp: Float,
    enablePageAnimations: Boolean,
    loadBookText: suspend (Book) -> String,
    onFontSizeChange: (Float) -> Unit,
    onPageChanged: (charOffset: Int, totalChars: Int, pageIndex: Int, pageCount: Int) -> Unit,
    onAddAnnotation: (type: AnnotationType, pageNumber: Int, text: String) -> Unit,
    onDeleteAnnotation: (BookAnnotation) -> Unit,
    onCloseReader: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    val coroutineScope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val baseDensity = LocalDensity.current
    // Reader typography is independent of the system font scale: sizes are predefined presets
    // (Normal / Large / Extra Large), so text can never scale out of the viewport.
    val readerDensity = remember(baseDensity.density) { Density(baseDensity.density, fontScale = 1f) }

    var isActiveReaderHudVisible by rememberSaveable { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val cleanedBookText by produceState(initialValue = "", key1 = book.id, key2 = book.textContentFilePath) {
        value = withContext(Dispatchers.Default) {
            ViewportPaginator.cleanText(loadBookText(book))
        }
    }

    val textSizePreset = remember(fontSizeSp) { ReaderTextSize.fromSp(fontSizeSp) }

    val readerTextStyle = remember(textSizePreset, palette.readerText) {
        TextStyle(
            fontFamily = FontFamily.Serif,
            fontSize = textSizePreset.sp.sp,
            lineHeight = (textSizePreset.sp * 1.6f).sp,
            color = palette.readerText,
            textAlign = TextAlign.Justify
        )
    }

    // Reading position is a layout-independent character offset (single source of truth).
    // It survives text-size / screen-size changes, which change the number of pages.
    var currentCharOffset by rememberSaveable(book.id) { mutableIntStateOf(book.readingCharOffset) }

    // Deterministic pagination for the (text, style, viewport) triple. The delay debounces
    // transient viewport sizes during the first layout passes so the page count is stable.
    val pageLayout by produceState<PageLayout?>(
        initialValue = null,
        key1 = cleanedBookText,
        key2 = viewportSize,
        key3 = readerTextStyle
    ) {
        if (cleanedBookText.isEmpty() || viewportSize.width <= 0 || viewportSize.height <= 0) {
            value = null
        } else {
            delay(120)
            value = withContext(Dispatchers.Default) {
                ViewportPaginator.paginate(
                    cleanedText = cleanedBookText,
                    textMeasurer = textMeasurer,
                    textStyle = readerTextStyle,
                    density = readerDensity,
                    availableWidthPx = viewportSize.width,
                    // Small safety margin so rendered text can never exceed the measured height
                    availableHeightPx = viewportSize.height - 2
                )
            }
        }
    }

    val layoutSnapshot = pageLayout
    val pageCount = layoutSnapshot?.pageCount ?: 1

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { pageCount }
    )

    // 1) Restore position for each new layout, 2) then persist EVERY page change (any direction).
    LaunchedEffect(layoutSnapshot) {
        val layout = layoutSnapshot ?: return@LaunchedEffect
        pagerState.scrollToPage(layout.pageIndexForOffset(currentCharOffset))
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { pageIdx ->
                val offset = layout.startOffsetOf(pageIdx)
                currentCharOffset = offset
                onPageChanged(offset, layout.text.length, pageIdx, layout.pageCount)
            }
    }

    val currentDisplayPage = (pagerState.currentPage + 1).coerceIn(1, pageCount)
    val readingPercent = remember(pagerState.currentPage, pageCount) {
        ((pagerState.currentPage + 1).toFloat() / pageCount.toFloat() * 100f).coerceIn(0.1f, 100f)
    }

    var showAddNoteOrHighlightDialog by rememberSaveable { mutableStateOf(false) }
    var initialDialogType by rememberSaveable { mutableStateOf(AnnotationType.NOTE) }
    var initialDialogText by rememberSaveable { mutableStateOf("") }
    var showSearchInBookDialog by rememberSaveable { mutableStateOf(false) }
    var annotationPendingDelete by remember { mutableStateOf<BookAnnotation?>(null) }

    val currentPageHighlights = remember(bookAnnotations, currentDisplayPage) {
        bookAnnotations.filter { it.pageNumber == currentDisplayPage }
    }
    val hasHighlightOnCurrentPage = currentPageHighlights.isNotEmpty()

    val currentPageText = layoutSnapshot?.pageText(pagerState.currentPage).orEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.readerBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isActiveReaderHudVisible = !isActiveReaderHudVisible
            },
        contentAlignment = Alignment.Center
    ) {
        // Adaptive container: centers reading column comfortably on phones, foldables, and tablets
        CompositionLocalProvider(LocalDensity provides readerDensity) {
            Box(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 26.dp, vertical = 62.dp)
                    .onSizeChanged { newSize ->
                        if (newSize != viewportSize && newSize.width > 0 && newSize.height > 0) {
                            viewportSize = newSize
                        }
                    }
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { pageIdx ->
                    val pageText = layoutSnapshot?.pageText(pageIdx).orEmpty()

                    // Highlights are matched by their text so they stay correct across layouts.
                    // Only background is styled (no font weight change) so text metrics never change.
                    val annotatedPageText = remember(pageText, bookAnnotations, palette.highlightMarkerBg) {
                        buildAnnotatedString {
                            append(pageText)
                            bookAnnotations.forEach { ann ->
                                val q = ann.text.trim()
                                if (q.isNotEmpty() && q.length <= pageText.length) {
                                    var startIdx = pageText.indexOf(q, ignoreCase = true)
                                    while (startIdx >= 0) {
                                        addStyle(
                                            style = SpanStyle(background = palette.highlightMarkerBg),
                                            start = startIdx,
                                            end = (startIdx + q.length).coerceAtMost(pageText.length)
                                        )
                                        startIdx = pageText.indexOf(q, startIdx + q.length, ignoreCase = true)
                                    }
                                }
                            }
                        }
                    }

                    // Strictly non-scrollable fitted page box — never scrolls up or down
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    isActiveReaderHudVisible = !isActiveReaderHudVisible
                                },
                                onLongClick = {
                                    initialDialogType = AnnotationType.HIGHLIGHT
                                    initialDialogText = pageText
                                        .split(Regex("(?<=[.!?])\\s+"))
                                        .firstOrNull { it.isNotBlank() }
                                        ?.trim()
                                        .orEmpty()
                                    showAddNoteOrHighlightDialog = true
                                }
                            ),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Text(
                            text = annotatedPageText,
                            style = readerTextStyle,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // =========================================================================
        // STATE 1: Distraction-Free Header & Page Footer
        // =========================================================================
        AnimatedVisibility(
            visible = !isActiveReaderHudVisible,
            enter = if (enablePageAnimations) fadeIn() else EnterTransition.None,
            exit = if (enablePageAnimations) fadeOut() else ExitTransition.None,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Text(
                text = book.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 18.dp, start = 24.dp, end = 24.dp)
            )
        }

        AnimatedVisibility(
            visible = !isActiveReaderHudVisible,
            enter = if (enablePageAnimations) fadeIn() else EnterTransition.None,
            exit = if (enablePageAnimations) fadeOut() else ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                Text(
                    text = "$currentDisplayPage",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textMuted
                )
                if (currentPageHighlights.isNotEmpty()) {
                    val firstAnn = currentPageHighlights.first()
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(palette.surfaceColor)
                            .border(1.dp, palette.borderSubtle, CircleShape)
                            .combinedClickable(
                                onClick = { isActiveReaderHudVisible = true },
                                onLongClick = { annotationPendingDelete = firstAnn }
                            )
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${currentPageHighlights.size} synced",
                            fontSize = 11.sp,
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }

        // =========================================================================
        // STATE 2: Active Book Reader HUD Overlays
        // =========================================================================
        AnimatedVisibility(
            visible = isActiveReaderHudVisible,
            enter = if (enablePageAnimations) fadeIn() + slideInVertically { -it / 2 } else EnterTransition.None,
            exit = if (enablePageAnimations) fadeOut() + slideOutVertically { -it / 2 } else ExitTransition.None,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.glassBackground)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: predefined text sizes (Normal / Large / Extra Large) — larger = more pages
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReaderTextSize.entries.forEach { preset ->
                        val selected = preset == textSizePreset
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (selected) palette.dockTabActiveBg else Color.Transparent)
                                .border(1.dp, palette.borderSubtle, CircleShape)
                                .clickable { onFontSizeChange(preset.sp) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "A",
                                fontFamily = FontFamily.Serif,
                                fontSize = when (preset) {
                                    ReaderTextSize.NORMAL -> 12.sp
                                    ReaderTextSize.LARGE -> 15.sp
                                    ReaderTextSize.EXTRA_LARGE -> 18.sp
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) palette.dockTabActiveText else palette.textPrimary
                            )
                        }
                    }
                }

                // Center Page Counter
                Text(
                    text = "$currentDisplayPage of $pageCount",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textMuted
                )

                // Right Circular Close Button (✕)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (palette.isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)
                        )
                        .clickable { onCloseReader() },
                    contentAlignment = Alignment.Center
                ) {
                    CloseCrossIcon(
                        color = if (palette.isDark) Color(0xFFD4D4D8) else Color(0xFF27272A),
                        size = 14.dp
                    )
                }
            }
        }

        // Bottom Floating Glassmorphic HUD Capsule
        AnimatedVisibility(
            visible = isActiveReaderHudVisible,
            enter = if (enablePageAnimations) fadeIn() + slideInVertically { it / 2 } else EnterTransition.None,
            exit = if (enablePageAnimations) fadeOut() + slideOutVertically { it / 2 } else ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(bottom = 22.dp)
            ) {
                // Show synced notes/highlights strip above HUD when active (supports long-press to delete!)
                if (currentPageHighlights.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.90f)
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.glassBackground)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "SYNCED ON PAGE $currentDisplayPage (LONG PRESS TO DELETE)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.7.sp,
                            color = palette.textMuted
                        )
                        currentPageHighlights.forEach { ann ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { annotationPendingDelete = ann }
                                    )
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "• [${ann.type.name}] ${ann.text}",
                                    fontSize = 12.5.sp,
                                    color = palette.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(palette.textMuted.copy(alpha = 0.55f))
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.90f)
                        .height(58.dp)
                        .shadow(
                            elevation = if (palette.isDark) 24.dp else 6.dp,
                            shape = CircleShape,
                            ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.6f else 0.10f),
                            spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.7f else 0.14f)
                        )
                        .border(1.dp, palette.dockBorder, CircleShape),
                    shape = CircleShape,
                    color = palette.glassBackground
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (palette.isDark) Color.White.copy(alpha = 0.11f)
                                    else Color.Black.copy(alpha = 0.08f)
                                )
                                .clickable {
                                    initialDialogType = AnnotationType.NOTE
                                    initialDialogText = ""
                                    showAddNoteOrHighlightDialog = true
                                }
                                .padding(horizontal = 13.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            NotePencilIcon(color = palette.readerText, size = 16.dp)
                            Text(
                                text = "Add Note",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.readerText
                            )
                        }

                        Text(
                            text = String.format(Locale.US, "%.1f%%", readingPercent),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textMuted
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val pageContent = currentPageText
                                        val firstSentence = pageContent
                                            .split(Regex("(?<=[.!?])\\s+"))
                                            .firstOrNull { it.isNotBlank() }
                                            ?.trim()
                                            .orEmpty()
                                        initialDialogType = AnnotationType.HIGHLIGHT
                                        initialDialogText = firstSentence
                                        showAddNoteOrHighlightDialog = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                BookmarkRibbonIcon(
                                    color = palette.readerText,
                                    filled = hasHighlightOnCurrentPage,
                                    size = 20.dp
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .clickable { showSearchInBookDialog = true },
                                contentAlignment = Alignment.Center
                            ) {
                                SearchVectorIcon(color = palette.readerText, size = 20.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog to Add Note or Highlight on the Current Page
    if (showAddNoteOrHighlightDialog) {
        var selectedType by remember(initialDialogType) { mutableStateOf(initialDialogType) }
        var textInput by remember(initialDialogText) { mutableStateOf(initialDialogText) }

        val sentencesOnPage = remember(currentPageText) {
            currentPageText
                .split(Regex("(?<=[.!?])\\s+|\\n+"))
                .map { it.trim() }
                .filter { it.length > 8 }
                .take(6)
        }

        AlertDialog(
            onDismissRequest = { showAddNoteOrHighlightDialog = false },
            containerColor = palette.menuSurface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = {
                Column {
                    Text(
                        text = if (selectedType == AnnotationType.HIGHLIGHT) "Add Highlight" else "Add Note",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${book.title} • Page $currentDisplayPage (syncs to Notes)",
                        fontSize = 12.sp,
                        color = palette.textMuted
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnnotationType.entries.forEach { type ->
                            val selected = selectedType == type
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) palette.dockTabActiveBg else Color.Transparent)
                                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                    .clickable { selectedType = type }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (type == AnnotationType.NOTE) "NOTE" else "HIGHLIGHT",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else palette.textMuted
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = {
                            Text(
                                if (selectedType == AnnotationType.HIGHLIGHT) {
                                    "Highlight passage on Page $currentDisplayPage"
                                } else {
                                    "Write your note for Page $currentDisplayPage"
                                }
                            )
                        },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (selectedType == AnnotationType.HIGHLIGHT && sentencesOnPage.isNotEmpty()) {
                        Text(
                            text = "Tap a passage from this page to highlight:",
                            fontSize = 11.sp,
                            color = palette.textMuted
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(
                                items = sentencesOnPage,
                                key = { it },
                                contentType = { "sentence_suggestion" }
                            ) { sentence ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(palette.surfaceColor)
                                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(8.dp))
                                        .clickable { textInput = sentence }
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = sentence,
                                        fontSize = 12.sp,
                                        color = palette.textSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = textInput.trim()
                        if (cleaned.isNotEmpty()) {
                            onAddAnnotation(selectedType, currentDisplayPage, cleaned)
                            showAddNoteOrHighlightDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.textPrimary,
                        contentColor = palette.appBackground
                    )
                ) {
                    Text("Save to Notes", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteOrHighlightDialog = false }) {
                    Text("Cancel", color = palette.textMuted)
                }
            }
        )
    }

    // Dialog to Search inside the current Book
    if (showSearchInBookDialog) {
        var query by rememberSaveable { mutableStateOf("") }
        val allPages = remember(layoutSnapshot) {
            layoutSnapshot?.let { l -> List(l.pageCount) { l.pageText(it) } } ?: emptyList()
        }
        val matchingPages = remember(query, allPages) {
            if (query.isBlank()) {
                allPages.mapIndexed { idx, content -> idx to content }
            } else {
                allPages.mapIndexedNotNull { idx, content ->
                    if (content.contains(query.trim(), ignoreCase = true)) idx to content else null
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showSearchInBookDialog = false },
            containerColor = palette.menuSurface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = {
                Text(
                    text = "Search in ${book.title}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search text or jump to page...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = matchingPages,
                            key = { it.first },
                            contentType = { "search_match" }
                        ) { (pageIdx, content) ->
                            val dispPage = pageIdx + 1
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surfaceColor)
                                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.scrollToPage(pageIdx)
                                        }
                                        showSearchInBookDialog = false
                                    }
                                    .padding(10.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "PAGE $dispPage",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textMuted
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = content,
                                        fontSize = 12.5.sp,
                                        color = palette.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSearchInBookDialog = false }) {
                    Text("Close", color = palette.textPrimary)
                }
            }
        )
    }

    // Long-press Delete Note/Highlight Confirmation Dialog inside Reader
    annotationPendingDelete?.let { targetAnn ->
        val label = if (targetAnn.type == AnnotationType.HIGHLIGHT) "Highlight" else "Note"
        AlertDialog(
            onDismissRequest = { annotationPendingDelete = null },
            containerColor = palette.menuSurface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = {
                Text(
                    text = "Delete $label",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Delete this $label from Page ${targetAnn.pageNumber}?\n\n\"${targetAnn.text}\"",
                    fontSize = 14.sp,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteAnnotation(targetAnn)
                        annotationPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    )
                ) {
                    Text("Delete", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { annotationPendingDelete = null }) {
                    Text("Cancel", color = palette.textMuted)
                }
            }
        )
    }
}
