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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.model.Book
import com.example.freebook.model.CoverStyle
import com.example.freebook.ui.theme.LocalBookFreePalette
import java.io.File

enum class BottomNavTab {
    LIBRARY,
    NOTES
}

@Composable
fun BookCoverView(
    book: Book,
    modifier: Modifier = Modifier,
    isThumbnail: Boolean = false
) {
    val customBitmap = remember(book.coverImagePath) {
        book.coverImagePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } else {
                null
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
                bitmap = customBitmap,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    when (book.coverStyle) {
        CoverStyle.ARCH_LIGHT -> {
            val bg = Color(0xFFEFEFF1)
            val fg = Color(0xFF1A1A1C)
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(bg)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(cornerRadius))
                    .padding(if (isThumbnail) 5.dp else 10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatLinesForEditorialCover(book.title),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isThumbnail) 5.5.sp else 9.sp,
                        lineHeight = if (isThumbnail) 6.5.sp else 11.sp,
                        letterSpacing = 0.6.sp,
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
                                width = if (isThumbnail) 18.dp else 36.dp,
                                height = if (isThumbnail) 22.dp else 44.dp
                            )
                        ) {
                            val path = Path().apply {
                                val r = size.width / 2f
                                addRoundRect(
                                    RoundRect(
                                        rect = Rect(0f, 0f, size.width, size.height),
                                        topLeft = CornerRadius(r, r),
                                        topRight = CornerRadius(r, r),
                                        bottomLeft = CornerRadius.Zero,
                                        bottomRight = CornerRadius.Zero
                                    )
                                )
                            }
                            drawPath(path = path, color = fg)
                        }
                    }

                    Text(
                        text = book.author.ifBlank { "VIRGINIA WOOLF" }.uppercase(),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (isThumbnail) 3.5.sp else 5.5.sp,
                        letterSpacing = 0.8.sp,
                        color = fg.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        CoverStyle.CIRCLE_PEDESTAL -> {
            val bg = Color(0xFFDEDEE1)
            val fg = Color(0xFF222224)
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(bg)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(cornerRadius))
                    .padding(if (isThumbnail) 5.dp else 10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = book.title.uppercase(),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (isThumbnail) 5.sp else 7.5.sp,
                        letterSpacing = 0.8.sp,
                        color = fg,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Canvas(
                        modifier = Modifier.size(
                            width = if (isThumbnail) 20.dp else 38.dp,
                            height = if (isThumbnail) 22.dp else 44.dp
                        )
                    ) {
                        val strokeW = if (isThumbnail) 1.dp.toPx() else 1.4.dp.toPx()
                        val circleRadius = size.width * 0.38f
                        val centerY = size.height * 0.42f
                        drawCircle(
                            color = fg,
                            radius = circleRadius,
                            center = Offset(size.width / 2f, centerY),
                            style = Stroke(width = strokeW)
                        )
                        val line1Y = centerY + circleRadius + (if (isThumbnail) 3.dp.toPx() else 6.dp.toPx())
                        drawLine(
                            color = fg,
                            start = Offset(size.width * 0.05f, line1Y),
                            end = Offset(size.width * 0.95f, line1Y),
                            strokeWidth = strokeW
                        )
                        val line2Y = line1Y + (if (isThumbnail) 2.dp.toPx() else 3.5.dp.toPx())
                        drawLine(
                            color = fg,
                            start = Offset(size.width * 0.16f, line2Y),
                            end = Offset(size.width * 0.84f, line2Y),
                            strokeWidth = strokeW * 0.85f
                        )
                    }

                    Text(
                        text = book.author.ifBlank { "MARCUS AURELIUS" }.uppercase(),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (isThumbnail) 3.5.sp else 5.5.sp,
                        letterSpacing = 0.8.sp,
                        color = fg.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        CoverStyle.OVAL_DARK -> {
            val bg = Color(0xFF343538)
            val fg = Color(0xFFEBEBEE)
            val muted = Color(0xFFCFCFD3)
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(bg)
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(cornerRadius))
                    .padding(if (isThumbnail) 5.dp else 10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatLinesForEditorialCover(book.title),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = if (isThumbnail) 5.5.sp else 8.5.sp,
                        lineHeight = if (isThumbnail) 6.5.sp else 10.5.sp,
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
                                width = if (isThumbnail) 22.dp else 40.dp,
                                height = if (isThumbnail) 10.dp else 16.dp
                            )
                        ) {
                            drawOval(
                                color = muted,
                                topLeft = Offset.Zero,
                                size = Size(size.width, size.height),
                                style = Stroke(width = if (isThumbnail) 0.9.dp.toPx() else 1.2.dp.toPx())
                            )
                        }
                    }

                    Text(
                        text = book.author.ifBlank { "OKAKURA KAKUZO" }.uppercase(),
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Normal,
                        fontSize = if (isThumbnail) 3.5.sp else 5.5.sp,
                        letterSpacing = 0.7.sp,
                        color = muted.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        CoverStyle.DEFAULT_EDITORIAL -> {
            // Default cover when user imports an EPUB without cover or creates a book note without specifying a cover
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
                            // Minimalist geometric double-frame + center circle emblem
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
    }
}

private fun formatLinesForEditorialCover(title: String): String {
    val words = title.trim().split(Regex("\\s+"))
    return when {
        title.equals("A Room Of One’s Own", ignoreCase = true) ||
            title.equals("A Room Of One's Own", ignoreCase = true) -> "A ROOM\nOF ONE'S\nOWN"
        title.equals("The Book Of Tea", ignoreCase = true) -> "THE\nBOOK\nOF TEA"
        words.size == 3 -> words.joinToString("\n") { it.uppercase() }
        else -> title.uppercase()
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
                elevation = 20.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.6f),
                spotColor = Color.Black.copy(alpha = 0.7f)
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
            // Library Tab
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

            // Notes Tab
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

            // Option 1: Theme (Dark or Light)
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

            HorizontalDivider(color = palette.borderSubtle)

            // Option 2: About
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
            // Center spine
            moveTo(w * 0.5f, h * 0.24f)
            lineTo(w * 0.5f, h * 0.82f)
            // Left page
            moveTo(w * 0.5f, h * 0.24f)
            cubicTo(w * 0.38f, h * 0.18f, w * 0.22f, h * 0.18f, w * 0.12f, h * 0.24f)
            lineTo(w * 0.12f, h * 0.78f)
            cubicTo(w * 0.22f, h * 0.72f, w * 0.38f, h * 0.72f, w * 0.5f, h * 0.82f)
            // Right page
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
            // Rounded square note box
            moveTo(w * 0.44f, h * 0.22f)
            lineTo(w * 0.22f, h * 0.22f)
            cubicTo(w * 0.16f, h * 0.22f, w * 0.14f, h * 0.25f, w * 0.14f, h * 0.31f)
            lineTo(w * 0.14f, h * 0.78f)
            cubicTo(w * 0.14f, h * 0.84f, w * 0.17f, h * 0.86f, w * 0.23f, h * 0.86f)
            lineTo(w * 0.70f, h * 0.86f)
            cubicTo(w * 0.76f, h * 0.86f, w * 0.78f, h * 0.83f, w * 0.78f, h * 0.77f)
            lineTo(w * 0.78f, h * 0.56f)

            // Pencil diagonal
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
