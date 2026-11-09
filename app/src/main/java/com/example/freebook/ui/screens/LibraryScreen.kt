package com.example.freebook.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.Book
import com.example.freebook.model.LibraryFilter
import com.example.freebook.ui.components.BookCoverView
import com.example.freebook.ui.components.BottomNavTab
import com.example.freebook.ui.components.CloseCrossIcon
import com.example.freebook.ui.components.FloatingBottomDock
import com.example.freebook.ui.components.PlusVectorIcon
import com.example.freebook.ui.components.SearchVectorIcon
import com.example.freebook.ui.components.TopBarMoreMenuButton
import com.example.freebook.ui.theme.LocalBookFreePalette

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    isDarkTheme: Boolean,
    enablePageAnimations: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onTogglePageAnimations: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onImportEpubClick: () -> Unit,
    onBookClick: (Book) -> Unit,
    onDeleteBook: (Book) -> Unit,
    onNavigateToNotes: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }
    var selectedFilterName by rememberSaveable { mutableStateOf(LibraryFilter.ALL.name) }
    val selectedFilter = remember(selectedFilterName) {
        runCatching { LibraryFilter.valueOf(selectedFilterName) }.getOrDefault(LibraryFilter.ALL)
    }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var bookPendingDelete by remember { mutableStateOf<Book?>(null) }

    fun abandonSearch() {
        if (isSearchActive) {
            focusManager.clearFocus()
            isSearchActive = false
            searchQuery = ""
        }
    }

    BackHandler(enabled = isSearchActive) {
        abandonSearch()
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            runCatching { searchFocusRequester.requestFocus() }
        } else {
            focusManager.clearFocus()
        }
    }

    val gridState = rememberLazyGridState()

    val allLibraryBooks = remember(books) {
        books.filter { !it.isCustomNoteEntryOnly }
    }

    val libraryBooks = remember(allLibraryBooks, selectedFilter, searchQuery) {
        allLibraryBooks
            .filter { book ->
                when (selectedFilter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.CONTINUE -> !book.isFinished && (book.isStarted || book.readingProgress > 0f || book.currentPageIndex > 0)
                    LibraryFilter.FINISHED -> book.isFinished
                }
            }
            .filter { book ->
                if (searchQuery.isBlank()) {
                    true
                } else {
                    val q = searchQuery.trim()
                    book.title.contains(q, ignoreCase = true) ||
                        book.author.contains(q, ignoreCase = true)
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.appBackground)
            .clickable(
                enabled = isSearchActive,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                abandonSearch()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Top Bar: Title + Search, Add (+ Book), More Options (⋮)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Library",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSearchActive) palette.borderSubtle else Color.Transparent)
                            .clickable {
                                if (isSearchActive) {
                                    abandonSearch()
                                } else {
                                    isSearchActive = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        SearchVectorIcon(color = palette.textPrimary, size = 21.dp)
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                abandonSearch()
                                onImportEpubClick()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        PlusVectorIcon(color = palette.textPrimary, size = 23.dp)
                    }

                    TopBarMoreMenuButton(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = onToggleTheme,
                        onOpenAbout = onOpenAbout,
                        enablePageAnimations = enablePageAnimations,
                        onTogglePageAnimations = onTogglePageAnimations
                    )
                }
            }

            // Expandable Search Bar
            AnimatedVisibility(visible = isSearchActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceColor)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            runCatching { searchFocusRequester.requestFocus() }
                        }
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SearchVectorIcon(color = palette.textMuted, size = 17.dp)
                        Spacer(modifier = Modifier.size(10.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search books by title or author...",
                                    color = palette.textMuted,
                                    fontSize = 14.sp
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = palette.textPrimary,
                                    fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(palette.textPrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester)
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable { searchQuery = "" },
                                contentAlignment = Alignment.Center
                            ) {
                                CloseCrossIcon(color = palette.textMuted, size = 12.dp)
                            }
                        }
                    }
                }
            }

            // Filter Pills: All, Continue, Finished
            if (allLibraryBooks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryFilter.entries.forEach { filter ->
                        val selected = selectedFilter == filter
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) palette.pillSelectedBg else Color.Transparent)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) palette.pillSelectedBorder else palette.pillUnselectedBorder,
                                    shape = CircleShape
                                )
                                .clickable { selectedFilterName = filter.name }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = filter.label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) {
                                    Color.White
                                } else {
                                    palette.textPrimary.copy(alpha = 0.6f)
                                }
                            )
                        }
                    }
                }
            }

            // Empty State vs. Adaptive Books Shelf Grid
            if (allLibraryBooks.isEmpty()) {
                // Centered "Add Books" Empty State when the user has no books yet
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 84.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceColor)
                                .border(1.dp, palette.borderSubtle, CircleShape)
                                .clickable { onImportEpubClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            PlusVectorIcon(color = palette.textPrimary, size = 32.dp)
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Add Books",
                            fontFamily = FontFamily.Serif,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Add Epub files to begin reading",
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = palette.textMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (libraryBooks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No books matching \"$searchQuery\""
                            } else {
                                "No books in ${selectedFilter.label}"
                            },
                            color = palette.textMuted,
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clickable(
                            enabled = isSearchActive,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            abandonSearch()
                        }
                ) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(
                            items = libraryBooks,
                            key = { it.id },
                            contentType = { "book_card" }
                        ) { book ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isSearchActive && searchQuery.isBlank()) {
                                                abandonSearch()
                                            } else {
                                                abandonSearch()
                                                onBookClick(book)
                                            }
                                        },
                                        onLongClick = { bookPendingDelete = book }
                                    )
                            ) {
                                BookCoverView(
                                    book = book,
                                    isThumbnail = false,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f / 1.45f)
                                        .shadow(6.dp, RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = book.statusText,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // When search is open and query is blank, tapping anywhere below the search bar
                    // abandons search and returns to the normal screen
                    if (isSearchActive && searchQuery.isBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    abandonSearch()
                                }
                        )
                    }
                }
            }
        }

        // Floating Bottom Navigation Dock (hidden while searching so it never floats above keyboard)
        AnimatedVisibility(
            visible = !isSearchActive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        ) {
            FloatingBottomDock(
                selectedTab = BottomNavTab.LIBRARY,
                onSelectTab = { tab ->
                    if (tab == BottomNavTab.NOTES) {
                        onNavigateToNotes()
                    }
                }
            )
        }
    }

    // Long-press Delete Book Confirmation Dialog
    bookPendingDelete?.let { targetBook ->
        AlertDialog(
            onDismissRequest = { bookPendingDelete = null },
            containerColor = palette.menuSurface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = {
                Text(
                    text = "Delete Book",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Delete \"${targetBook.title}\"? This will remove the book file, cover image, and all its synced notes and highlights.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteBook(targetBook)
                        bookPendingDelete = null
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
                TextButton(onClick = { bookPendingDelete = null }) {
                    Text("Cancel", color = palette.textMuted)
                }
            }
        )
    }
}
