package com.example.freebook.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun LibraryScreen(
    books: List<Book>,
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onImportEpubClick: () -> Unit,
    onBookClick: (Book) -> Unit,
    onNavigateToNotes: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    var selectedFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val libraryBooks = remember(books, selectedFilter, searchQuery) {
        books
            .filter { !it.isCustomNoteEntryOnly }
            .filter { book ->
                when (selectedFilter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.CONTINUE -> !book.isFinished && (book.isStarted || book.fixedProgressPercent != null || book.currentPageIndex > 0)
                    LibraryFilter.FINISHED -> book.isFinished
                }
            }
            .filter { book ->
                if (searchQuery.isBlank()) {
                    true
                } else {
                    book.title.contains(searchQuery.trim(), ignoreCase = true) ||
                        book.author.contains(searchQuery.trim(), ignoreCase = true)
                }
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.appBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Top Bar: Title + Search, Add (+ EPUB), More Options (⋮)
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
                    // Search Books Button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSearchActive) palette.borderSubtle else Color.Transparent)
                            .clickable {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) searchQuery = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        SearchVectorIcon(color = palette.textPrimary, size = 21.dp)
                    }

                    // + Button to Import EPUB
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onImportEpubClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        PlusVectorIcon(color = palette.textPrimary, size = 23.dp)
                    }

                    // More Options (⋮) Button: 1- Theme (Dark or Light), 2- About
                    TopBarMoreMenuButton(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = onToggleTheme,
                        onOpenAbout = onOpenAbout
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
                                modifier = Modifier.fillMaxWidth()
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
                            .clickable { selectedFilter = filter }
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

            // Books Shelf 3-Column Grid
            if (libraryBooks.isEmpty()) {
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap + in the top bar to import an EPUB book",
                            color = palette.textMuted.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(libraryBooks, key = { it.id }) { book ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBookClick(book) }
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
            }
        }

        // Floating Bottom Navigation Dock
        FloatingBottomDock(
            selectedTab = BottomNavTab.LIBRARY,
            onSelectTab = { tab ->
                if (tab == BottomNavTab.NOTES) {
                    onNavigateToNotes()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        )
    }
}
