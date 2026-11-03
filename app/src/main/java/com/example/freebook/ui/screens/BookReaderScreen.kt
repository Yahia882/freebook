package com.example.freebook.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.ui.components.BookmarkRibbonIcon
import com.example.freebook.ui.components.CloseCrossIcon
import com.example.freebook.ui.components.NotePencilIcon
import com.example.freebook.ui.components.SearchVectorIcon
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookReaderScreen(
    book: Book,
    bookAnnotations: List<BookAnnotation>,
    onPageChanged: (Int) -> Unit,
    onAddAnnotation: (type: AnnotationType, pageNumber: Int, text: String) -> Unit,
    onCloseReader: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    val coroutineScope = rememberCoroutineScope()

    // Starts in "Book Opened" distraction-free screen; clicking the screen switches to "Active Book Reader Screen"
    var isActiveReaderHudVisible by remember { mutableStateOf(false) }

    val pageCount = book.pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = book.currentPageIndex.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    val currentDisplayPage = (pagerState.currentPage + book.displayPageOffset)
        .coerceIn(1, book.displayTotalPages.coerceAtLeast(1))

    val readingPercent = remember(pagerState.currentPage, book.displayPageOffset, book.displayTotalPages, pageCount) {
        if (book.displayTotalPages > pageCount) {
            (currentDisplayPage.toFloat() / book.displayTotalPages.toFloat() * 100f).coerceIn(0.1f, 100f)
        } else {
            ((pagerState.currentPage + 1).toFloat() / pageCount.toFloat() * 100f).coerceIn(0.1f, 100f)
        }
    }

    // Dialog states
    var showAddNoteOrHighlightDialog by remember { mutableStateOf(false) }
    var initialDialogType by remember { mutableStateOf(AnnotationType.NOTE) }
    var initialDialogText by remember { mutableStateOf("") }
    var showSearchInBookDialog by remember { mutableStateOf(false) }

    val currentPageHighlights = remember(bookAnnotations, currentDisplayPage) {
        bookAnnotations.filter { it.pageNumber == currentDisplayPage }
    }

    val hasHighlightOnCurrentPage = currentPageHighlights.isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.readerBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isActiveReaderHudVisible = !isActiveReaderHudVisible
            }
    ) {
        // Horizontal Pager for Book Pages
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIdx ->
            val pageText = book.pages.getOrElse(pageIdx) { "" }
            val pageNumForThisPage = (pageIdx + book.displayPageOffset)
                .coerceIn(1, book.displayTotalPages.coerceAtLeast(1))
            val pageAnnotations = remember(bookAnnotations, pageNumForThisPage) {
                bookAnnotations.filter { it.pageNumber == pageNumForThisPage }
            }

            val paragraphs = remember(pageText) {
                pageText.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp)
                    .verticalScroll(rememberScrollState())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isActiveReaderHudVisible = !isActiveReaderHudVisible
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(64.dp))

                // Paragraphs with support for visual highlight markers and long-press to highlight
                paragraphs.forEachIndexed { idx, paragraph ->
                    val annotatedParagraph = remember(paragraph, pageAnnotations, palette.highlightMarkerBg) {
                        buildAnnotatedString {
                            append(paragraph)
                            pageAnnotations.forEach { ann ->
                                val q = ann.text.trim()
                                if (q.isNotEmpty()) {
                                    var startIdx = paragraph.indexOf(q, ignoreCase = true)
                                    while (startIdx >= 0) {
                                        addStyle(
                                            style = SpanStyle(
                                                background = palette.highlightMarkerBg,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            start = startIdx,
                                            end = (startIdx + q.length).coerceAtMost(paragraph.length)
                                        )
                                        startIdx = paragraph.indexOf(q, startIdx + q.length, ignoreCase = true)
                                    }
                                }
                            }
                        }
                    }

                    SelectionContainer {
                        Text(
                            text = annotatedParagraph,
                            fontFamily = FontFamily.Serif,
                            fontSize = 18.5.sp,
                            lineHeight = 31.5.sp,
                            color = palette.readerText,
                            textAlign = TextAlign.Justify,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        isActiveReaderHudVisible = !isActiveReaderHudVisible
                                    },
                                    onLongClick = {
                                        initialDialogType = AnnotationType.HIGHLIGHT
                                        initialDialogText = paragraph.split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty()
                                        showAddNoteOrHighlightDialog = true
                                    }
                                )
                        )
                    }

                    if (idx < paragraphs.lastIndex) {
                        Spacer(modifier = Modifier.height(22.dp))
                    }
                }

                // Show synced notes/highlights badge at bottom of page if any exist on this page
                if (pageAnnotations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.surfaceColor.copy(alpha = 0.65f))
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "SYNCED ON PAGE $pageNumForThisPage",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            color = palette.textMuted
                        )
                        pageAnnotations.forEach { ann ->
                            Text(
                                text = "• [${ann.type.name}] ${ann.text}",
                                fontSize = 13.sp,
                                color = palette.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(120.dp))
            }
        }

        // =========================================================================
        // STATE 1: "Book Opened Screen" (Distraction-Free Header & Page Footer)
        // Matching 03_book_opened.png ("Ancient Egypt" at top, page "58" at bottom)
        // =========================================================================
        AnimatedVisibility(
            visible = !isActiveReaderHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Text(
                text = book.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textMuted,
                modifier = Modifier.padding(top = 20.dp)
            )
        }

        AnimatedVisibility(
            visible = !isActiveReaderHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    text = "$currentDisplayPage",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textMuted
                )
            }
        }

        // =========================================================================
        // STATE 2: "Active Book Reader Screen" HUD Overlays
        // Matching 05_Active_Book_Reader_Screen.html & .png
        // =========================================================================
        AnimatedVisibility(
            visible = isActiveReaderHudVisible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.readerBackground.copy(alpha = 0.88f))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left balance spacer
                Spacer(modifier = Modifier.size(34.dp))

                // Center Page Counter ("51 of 512")
                Text(
                    text = "$currentDisplayPage of ${book.displayTotalPages}",
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
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 22.dp)
            ) {
                // Top mini swipe indicator bar above the pill HUD
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(palette.textMuted.copy(alpha = 0.55f))
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Glassmorphic Floating HUD Navigation Capsule
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.90f)
                        .height(58.dp)
                        .shadow(24.dp, CircleShape)
                        .border(1.dp, palette.dockBorder, CircleShape),
                    shape = CircleShape,
                    color = if (palette.isDark) Color(0xE81F1F23) else Color(0xF2FFFFFF)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: "Add Note" Pill Button
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

                        // Center: Reading Percentage (e.g., "9.9%")
                        Text(
                            text = String.format(Locale.US, "%.1f%%", readingPercent),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textMuted
                        )

                        // Right Quick Actions: Highlight/Bookmark & Search in Book
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            // Highlight / Bookmark button
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val pageContent = book.pages.getOrElse(pagerState.currentPage) { "" }
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

                            // Search in Book button
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

    // Dialog to Add Note or Highlight on the Current Page (synced with Notes Screen)
    if (showAddNoteOrHighlightDialog) {
        var selectedType by remember(initialDialogType) { mutableStateOf(initialDialogType) }
        var textInput by remember(initialDialogText) { mutableStateOf(initialDialogText) }

        val currentPageText = book.pages.getOrElse(pagerState.currentPage) { "" }
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
                    // Toggle between Note and Highlight
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
                            items(sentencesOnPage) { sentence ->
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
        var query by remember { mutableStateOf("") }
        val matchingPages = remember(query, book.pages) {
            if (query.isBlank()) {
                book.pages.mapIndexed { idx, content -> idx to content }
            } else {
                book.pages.mapIndexedNotNull { idx, content ->
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
                        items(matchingPages, key = { it.first }) { (pageIdx, content) ->
                            val dispPage = pageIdx + book.displayPageOffset
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
}
