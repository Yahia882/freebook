package com.example.freebook.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.Book
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class BottomNavTab {
    LIBRARY,
    NOTES
}

/**
 * Unified book cover component for all books.
 * - Loads `book.coverImagePath` off the main thread (`Dispatchers.IO`) if a cover image file exists.
 * - Otherwise renders a single unified minimalist editorial cover displaying the book's title,
 *   central geometric emblem, and author.
 */
@Composable
fun BookCoverView(
    book: Book,
    modifier: Modifier = Modifier,
    isThumbnail: Boolean = false
) {
    val customBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = book.coverImagePath) {
        value = withContext(Dispatchers.IO) {
            val path = book.coverImagePath
            if (path.isNullOrBlank()) {
                null
            } else {
                val file = File(path)
                if (file.exists()) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
                } else {
                    null
                }
            }
        }
    }

    val cornerRadius = if (isThumbnail) 3.dp else 4.dp

    if (customBitmap != null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(cornerRadius))
        ) {
            Image(
                bitmap = customBitmap!!,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    // Unified Minimalist Editorial Cover for any book without a bitmap file
    val bg = Color(0xFFE5E2DA)
    val fg = Color(0xFF1D1D20)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(bg)
            .border(0.5.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(cornerRadius))
            .padding(if (isThumbnail) 5.dp else 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = book.title.uppercase(),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = if (isThumbnail) 5.sp else 8.sp,
                lineHeight = if (isThumbnail) 6.sp else 10.sp,
                letterSpacing = 0.5.sp,
                color = fg,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(
                        width = if (isThumbnail) 18.dp else 34.dp,
                        height = if (isThumbnail) 18.dp else 34.dp
                    )
                ) {
                    val strokeW = if (isThumbnail) 0.9.dp.toPx() else 1.3.dp.toPx()
                    drawRect(
                        color = fg.copy(alpha = 0.75f),
                        style = Stroke(width = strokeW)
                    )
                    drawCircle(
                        color = fg,
                        radius = size.minDimension * 0.25f,
                        center = Offset(size.width / 2f, size.height / 2f)
                    )
                }
            }

            Text(
                text = book.author.ifBlank { "BOOKFREE EDITION" }.uppercase(),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (isThumbnail) 3.5.sp else 5.5.sp,
                letterSpacing = 0.7.sp,
                color = fg.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FloatingBottomDock(
    selectedTab: BottomNavTab,
    onSelectTab: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current

    Surface(
        modifier = modifier
            .shadow(
                elevation = if (palette.isDark) 20.dp else 8.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.6f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.7f else 0.14f)
            )
            .border(1.dp, palette.dockBorder, CircleShape),
        shape = CircleShape,
        color = palette.dockBackground
    ) {
        Row(
            modifier = Modifier.padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val isLibrarySelected = selectedTab == BottomNavTab.LIBRARY
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(54.dp)
                    .clip(CircleShape)
                    .background(if (isLibrarySelected) palette.dockTabActiveBg else Color.Transparent)
                    .clickable { onSelectTab(BottomNavTab.LIBRARY) },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BookOpenIcon(
                        color = if (isLibrarySelected) palette.dockTabActiveText else palette.dockTabInactiveText,
                        size = 20.dp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Library",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isLibrarySelected) palette.dockTabActiveText else palette.dockTabInactiveText
                    )
                }
            }

            val isNotesSelected = selectedTab == BottomNavTab.NOTES
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(54.dp)
                    .clip(CircleShape)
                    .background(if (isNotesSelected) palette.dockTabActiveBg else Color.Transparent)
                    .clickable { onSelectTab(BottomNavTab.NOTES) },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    NotePencilIcon(
                        color = if (isNotesSelected) palette.dockTabActiveText else palette.dockTabInactiveText,
                        size = 20.dp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Notes",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isNotesSelected) palette.dockTabActiveText else palette.dockTabInactiveText
                    )
                }
            }
        }
    }
}

@Composable
fun TopBarMoreMenuButton(
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    enablePageAnimations: Boolean = true,
    onTogglePageAnimations: ((Boolean) -> Unit)? = null,
    extraMenuItemLabel: String? = null,
    onExtraMenuItemClick: (() -> Unit)? = null
) {
    val palette = LocalBookFreePalette.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            MoreVertDotsIcon(color = palette.textPrimary, size = 20.dp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(palette.menuSurface)
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
        ) {
            if (extraMenuItemLabel != null && onExtraMenuItemClick != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = extraMenuItemLabel,
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    onClick = {
                        expanded = false
                        onExtraMenuItemClick()
                    }
                )
                HorizontalDivider(color = palette.borderSubtle)
            }

            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = "Theme: ${if (isDarkTheme) "Dark" else "Light"}",
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Tap to switch to ${if (isDarkTheme) "Light" else "Dark"} mode",
                            color = palette.textMuted,
                            fontSize = 11.sp
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onToggleTheme(!isDarkTheme)
                }
            )

            if (onTogglePageAnimations != null) {
                HorizontalDivider(color = palette.borderSubtle)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = "Animations: ${if (enablePageAnimations) "On" else "Off"}",
                                color = palette.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Toggle page-turn & HUD transitions",
                                color = palette.textMuted,
                                fontSize = 11.sp
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onTogglePageAnimations(!enablePageAnimations)
                    }
                )
            }

            HorizontalDivider(color = palette.borderSubtle)

            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = "About",
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "How BookFree works",
                            color = palette.textMuted,
                            fontSize = 11.sp
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onOpenAbout()
                }
            )
        }
    }
}

@Composable
fun SearchVectorIcon(color: Color, size: Dp = 21.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 2.dp.toPx()
        val r = this.size.minDimension * 0.30f
        val cx = this.size.width * 0.44f
        val cy = this.size.height * 0.44f
        drawCircle(
            color = color,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = color,
            start = Offset(cx + r * 0.72f, cy + r * 0.72f),
            end = Offset(this.size.width * 0.86f, this.size.height * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun PlusVectorIcon(color: Color, size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 2.dp.toPx()
        val pad = this.size.minDimension * 0.21f
        val midX = this.size.width / 2f
        val midY = this.size.height / 2f
        drawLine(
            color = color,
            start = Offset(midX, pad),
            end = Offset(midX, this.size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(pad, midY),
            end = Offset(this.size.width - pad, midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun MoreVertDotsIcon(color: Color, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val r = 1.6.dp.toPx()
        val cx = this.size.width / 2f
        drawCircle(color = color, radius = r, center = Offset(cx, this.size.height * 0.22f))
        drawCircle(color = color, radius = r, center = Offset(cx, this.size.height * 0.50f))
        drawCircle(color = color, radius = r, center = Offset(cx, this.size.height * 0.78f))
    }
}

@Composable
fun BookOpenIcon(color: Color, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.8.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.24f)
            lineTo(w * 0.5f, h * 0.82f)
            moveTo(w * 0.5f, h * 0.24f)
            cubicTo(w * 0.38f, h * 0.18f, w * 0.22f, h * 0.18f, w * 0.12f, h * 0.24f)
            lineTo(w * 0.12f, h * 0.78f)
            cubicTo(w * 0.22f, h * 0.72f, w * 0.38f, h * 0.72f, w * 0.5f, h * 0.82f)
            moveTo(w * 0.5f, h * 0.24f)
            cubicTo(w * 0.62f, h * 0.18f, w * 0.78f, h * 0.18f, w * 0.88f, h * 0.24f)
            lineTo(w * 0.88f, h * 0.78f)
            cubicTo(w * 0.78f, h * 0.72f, w * 0.62f, h * 0.72f, w * 0.5f, h * 0.82f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun NotePencilIcon(color: Color, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.8.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.44f, h * 0.22f)
            lineTo(w * 0.22f, h * 0.22f)
            cubicTo(w * 0.16f, h * 0.22f, w * 0.14f, h * 0.25f, w * 0.14f, h * 0.31f)
            lineTo(w * 0.14f, h * 0.78f)
            cubicTo(w * 0.14f, h * 0.84f, w * 0.17f, h * 0.86f, w * 0.23f, h * 0.86f)
            lineTo(w * 0.70f, h * 0.86f)
            cubicTo(w * 0.76f, h * 0.86f, w * 0.78f, h * 0.83f, w * 0.78f, h * 0.77f)
            lineTo(w * 0.78f, h * 0.56f)

            moveTo(w * 0.72f, h * 0.16f)
            lineTo(w * 0.86f, h * 0.30f)
            lineTo(w * 0.48f, h * 0.68f)
            lineTo(w * 0.32f, h * 0.72f)
            lineTo(w * 0.36f, h * 0.56f)
            close()
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun BackArrowIcon(color: Color, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 2.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = Offset(w * 0.82f, h * 0.5f),
            end = Offset(w * 0.18f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        val chevron = Path().apply {
            moveTo(w * 0.48f, h * 0.22f)
            lineTo(w * 0.18f, h * 0.50f)
            lineTo(w * 0.48f, h * 0.78f)
        }
        drawPath(
            path = chevron,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun CloseCrossIcon(color: Color, size: Dp = 14.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 2.2.dp.toPx()
        val pad = this.size.minDimension * 0.22f
        drawLine(
            color = color,
            start = Offset(pad, pad),
            end = Offset(this.size.width - pad, this.size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(this.size.width - pad, pad),
            end = Offset(pad, this.size.height - pad),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun BookmarkRibbonIcon(color: Color, filled: Boolean = false, size: Dp = 20.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = 1.8.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.24f, h * 0.16f)
            lineTo(w * 0.76f, h * 0.16f)
            lineTo(w * 0.76f, h * 0.86f)
            lineTo(w * 0.50f, h * 0.68f)
            lineTo(w * 0.24f, h * 0.86f)
            close()
        }
        if (filled) {
            drawPath(path = path, color = color)
        } else {
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
