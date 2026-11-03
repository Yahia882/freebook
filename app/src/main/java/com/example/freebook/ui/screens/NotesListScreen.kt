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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.model.CoverStyle
import com.example.freebook.ui.components.BookCoverView
import com.example.freebook.ui.components.BottomNavTab
import com.example.freebook.ui.components.CloseCrossIcon
import com.example.freebook.ui.components.FloatingBottomDock
import com.example.freebook.ui.components.PlusVectorIcon
import com.example.freebook.ui.components.SearchVectorIcon
import com.example.freebook.ui.components.TopBarMoreMenuButton
import com.example.freebook.ui.theme.LocalBookFreePalette

@Composable
fun NotesListScreen(
    books: List<Book>,
    annotations: List<BookAnnotation>,
    isDarkTheme: Boolean,
    pendingCustomCoverPath: String?,
    onClearPendingCustomCover: () -> Unit,
    onPickCustomCoverImage: () -> Unit,
    onAddCustomBookNoteEntry: (title: String, author: String, coverImagePath: String?, coverStyle: CoverStyle) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onBookNoteClick: (Book) -> Unit,
    onNavigateToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddBookNoteDialog by remember { mutableStateOf(false) }

    val annotationsByBook = remember(annotations) {
        annotations.groupBy { it.bookId }
    }

    val filteredBookEntries = remember(books, annotationsByBook, searchQuery) {
        books.filter { book ->
            if (searchQuery.isBlank()) {
                true
            } else {
                val q = searchQuery.trim()
                val titleMatch = book.title.contains(q, ignoreCase = true) ||
                    book.author.contains(q, ignoreCase = true)
                val noteMatch = annotationsByBook[book.id].orEmpty().any { ann ->
                    ann.text.contains(q, ignoreCase = true)
                }
                titleMatch || noteMatch
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

            // Top Bar: "Notes" + Search, Add (+), More Options (⋮)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notes",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textPrimary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Search Notes Button
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
                        SearchVectorIcon(color = palette.textPrimary, size = 20.dp)
                    }

                    // + Button: Add a new book note entry with customized name and custom/default cover
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable {
                                onClearPendingCustomCover()
                                showAddBookNoteDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        PlusVectorIcon(color = palette.textPrimary, size = 21.dp)
                    }

                    // More Options (⋮) Button: 1- Theme (Dark or Light), 2- About
                    TopBarMoreMenuButton(
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = onToggleTheme,
                        onOpenAbout = onOpenAbout
                    )
                }
            }

            // Expandable Search Bar for Notes
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
                                    text = "Search book notes or highlights...",
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

            // Notes Item List
            if (filteredBookEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "No notes matching \"$searchQuery\""
                            } else {
                                "No book note entries yet"
                            },
                            color = palette.textMuted,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap + to add a custom book note entry",
                            color = palette.textMuted.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(filteredBookEntries, key = { it.id }) { book ->
                        val bookNotes = annotationsByBook[book.id].orEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onBookNoteClick(book) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Thumbnail Cover (44dp x 58dp matching Stitch spec)
                            BookCoverView(
                                book = book,
                                isThumbnail = true,
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(58.dp)
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = book.title,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = palette.textPrimary.copy(alpha = 0.92f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (bookNotes.isNotEmpty() || searchQuery.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${bookNotes.size} ${if (bookNotes.size == 1) "entry" else "entries"}",
                                        fontSize = 12.sp,
                                        color = palette.textMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Bottom Navigation Dock
        FloatingBottomDock(
            selectedTab = BottomNavTab.NOTES,
            onSelectTab = { tab ->
                if (tab == BottomNavTab.LIBRARY) {
                    onNavigateToLibrary()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        )
    }

    // Dialog to add a new Book Note Entry with customized name and custom or default book cover
    if (showAddBookNoteDialog) {
        var customBookName by remember { mutableStateOf("") }
        var customAuthorName by remember { mutableStateOf("") }
        var selectedStyle by remember { mutableStateOf(CoverStyle.DEFAULT_EDITORIAL) }

        val previewBook = Book(
            title = customBookName.ifBlank { "Book Title" },
            author = customAuthorName.ifBlank { "Custom Notes" },
            coverStyle = selectedStyle,
            coverImagePath = pendingCustomCoverPath,
            pages = listOf("")
        )

        AlertDialog(
            onDismissRequest = {
                showAddBookNoteDialog = false
                onClearPendingCustomCover()
            },
            containerColor = palette.menuSurface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = {
                Text(
                    text = "New Book Note Entry",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = customBookName,
                        onValueChange = { customBookName = it },
                        label = { Text("Book Name") },
                        placeholder = { Text("Enter custom book title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = customAuthorName,
                        onValueChange = { customAuthorName = it },
                        label = { Text("Author / Subtitle (Optional)") },
                        placeholder = { Text("Default: Custom Notes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BookCoverView(
                            book = previewBook,
                            isThumbnail = false,
                            modifier = Modifier
                                .width(64.dp)
                                .height(92.dp)
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (pendingCustomCoverPath != null) {
                                    "Custom Cover Selected"
                                } else {
                                    "Default Cover Applied"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.textSecondary
                            )

                            OutlinedButton(
                                onClick = onPickCustomCoverImage,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (pendingCustomCoverPath != null) "Change Cover Image" else "Choose Cover Image",
                                    fontSize = 12.sp,
                                    color = palette.textPrimary
                                )
                            }

                            if (pendingCustomCoverPath != null) {
                                TextButton(
                                    onClick = onClearPendingCustomCover
                                ) {
                                    Text(
                                        text = "Use Default Cover",
                                        fontSize = 12.sp,
                                        color = palette.textMuted
                                    )
                                }
                            } else {
                                // Quick style selector for default covers
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val styles = listOf(
                                        CoverStyle.DEFAULT_EDITORIAL to "Classic",
                                        CoverStyle.ARCH_LIGHT to "Arch",
                                        CoverStyle.OVAL_DARK to "Dark"
                                    )
                                    styles.forEach { (style, label) ->
                                        val isPicked = selectedStyle == style
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isPicked) palette.dockTabActiveBg else Color.Transparent)
                                                .border(1.dp, palette.borderSubtle, RoundedCornerShape(8.dp))
                                                .clickable { selectedStyle = style }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                color = if (isPicked) Color.White else palette.textMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalTitle = customBookName.trim().ifBlank { "Untitled Book Notes" }
                        val finalAuthor = customAuthorName.trim().ifBlank { "BookFree Notes" }
                        onAddCustomBookNoteEntry(
                            finalTitle,
                            finalAuthor,
                            pendingCustomCoverPath,
                            selectedStyle
                        )
                        showAddBookNoteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.textPrimary,
                        contentColor = palette.appBackground
                    )
                ) {
                    Text("Add Entry", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddBookNoteDialog = false
                        onClearPendingCustomCover()
                    }
                ) {
                    Text("Cancel", color = palette.textMuted)
                }
            }
        )
    }
}
