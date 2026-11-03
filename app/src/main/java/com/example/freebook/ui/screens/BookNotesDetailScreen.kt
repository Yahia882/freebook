package com.example.freebook.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.AnnotationType
import com.example.freebook.model.Book
import com.example.freebook.model.BookAnnotation
import com.example.freebook.ui.components.BackArrowIcon
import com.example.freebook.ui.components.BottomNavTab
import com.example.freebook.ui.components.FloatingBottomDock
import com.example.freebook.ui.components.TopBarMoreMenuButton
import com.example.freebook.ui.theme.LocalBookFreePalette

@Composable
fun BookNotesDetailScreen(
    book: Book,
    annotations: List<BookAnnotation>,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onAddAnnotation: (type: AnnotationType, pageNumber: Int, text: String) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToNotesList: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    var showAddAnnotationDialog by remember { mutableStateOf(false) }

    // Always sorted ascending based on book pages
    val sortedAnnotations = remember(annotations) {
        annotations.sortedWith(compareBy<BookAnnotation> { it.pageNumber }.thenBy { it.createdAt })
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.appBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(8.dp))

            // Top Navigation Bar: Back Arrow, Book Title, More Options (⋮)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    BackArrowIcon(color = palette.textPrimary, size = 20.dp)
                }

                Text(
                    text = book.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                TopBarMoreMenuButton(
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = onToggleTheme,
                    onOpenAbout = onOpenAbout,
                    extraMenuItemLabel = "+ Add Note / Highlight",
                    onExtraMenuItemClick = { showAddAnnotationDialog = true }
                )
            }

            HorizontalDivider(color = palette.borderSubtle, thickness = 1.dp)

            if (sortedAnnotations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No highlights or notes yet for \"${book.title}\"",
                            fontSize = 15.sp,
                            color = palette.textMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Notes and highlights taken while reading this book automatically sync here sorted by page.",
                            fontSize = 13.sp,
                            color = palette.textMuted.copy(alpha = 0.75f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.dockTabActiveBg)
                                .clickable { showAddAnnotationDialog = true }
                                .padding(horizontal = 18.dp, vertical = 9.dp)
                        ) {
                            Text(
                                text = "+ Add Note or Highlight",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(bottom = 115.dp)
                ) {
                    items(sortedAnnotations, key = { it.id }) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp)
                        ) {
                            // Top row: HIGHLIGHT / NOTE on left, PAGE X on right
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (item.type == AnnotationType.HIGHLIGHT) "HIGHLIGHT" else "NOTE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.9.sp,
                                    color = palette.textMuted
                                )
                                Text(
                                    text = "PAGE ${item.pageNumber}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.9.sp,
                                    color = palette.textMuted
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Main text
                            Text(
                                text = item.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 23.sp,
                                color = palette.textPrimary
                            )

                            // Timestamp subtitle for NOTE entries matching Stitch design
                            if (item.type == AnnotationType.NOTE) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = item.timestampLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = palette.textMuted
                                )
                            }
                        }

                        HorizontalDivider(color = palette.borderSubtle, thickness = 1.dp)
                    }
                }
            }
        }

        // Floating Bottom Navigation Dock
        FloatingBottomDock(
            selectedTab = BottomNavTab.NOTES,
            onSelectTab = { tab ->
                when (tab) {
                    BottomNavTab.LIBRARY -> onNavigateToLibrary()
                    BottomNavTab.NOTES -> onNavigateToNotesList()
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        )
    }

    if (showAddAnnotationDialog) {
        var selectedType by remember { mutableStateOf(AnnotationType.NOTE) }
        var pageInput by remember { mutableStateOf(book.currentDisplayPage.toString()) }
        var contentInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddAnnotationDialog = false },
            containerColor = palette.menuSurface,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textPrimary,
            title = {
                Text(
                    text = "Add to ${book.title}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnnotationType.entries.forEach { type ->
                            val isSelected = selectedType == type
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) palette.dockTabActiveBg else Color.Transparent)
                                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                    .clickable { selectedType = type }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = type.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) Color.White else palette.textMuted
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { pageInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Page Number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = contentInput,
                        onValueChange = { contentInput = it },
                        label = {
                            Text(if (selectedType == AnnotationType.HIGHLIGHT) "Highlight Text" else "Note Content")
                        },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pageNum = pageInput.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val trimmed = contentInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onAddAnnotation(selectedType, pageNum, trimmed)
                            showAddAnnotationDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.textPrimary,
                        contentColor = palette.appBackground
                    )
                ) {
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAnnotationDialog = false }) {
                    Text("Cancel", color = palette.textMuted)
                }
            }
        )
    }
}
