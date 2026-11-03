package com.example.freebook.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.freebook.ui.components.BackArrowIcon
import com.example.freebook.ui.theme.LocalBookFreePalette

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.appBackground)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Top Navigation Bar
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
                text = "About BookFree",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )

            Spacer(modifier = Modifier.size(36.dp))
        }

        HorizontalDivider(color = palette.borderSubtle, thickness = 1.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Minimalist EPUB Reader & Page-Synced Notes",
                fontFamily = FontFamily.Serif,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = palette.textPrimary,
                lineHeight = 29.sp
            )

            Text(
                text = "BookFree is designed for distraction-free reading and effortless knowledge capture. Every highlight and note you take while reading is automatically organized by book and sorted by page number.",
                fontSize = 14.5.sp,
                lineHeight = 22.sp,
                color = palette.textSecondary
            )

            AboutFeatureCard(
                stepNumber = "01",
                title = "Library & EPUB Import",
                description = "Tap the '+' button on the Library screen to import any .epub book from your device. Use the search icon to find books by title or author, and filter your shelf by All, Continue, or Finished."
            )

            AboutFeatureCard(
                stepNumber = "02",
                title = "Book Opened & Active Reader View",
                description = "Opening a book launches the distraction-free Book Opened view. Tap anywhere on the page to reveal the Active Book Reader HUD with page progress, reading percentage, 'Add Note', quick highlight/bookmark, and in-book search. Swipe left or right to turn pages."
            )

            AboutFeatureCard(
                stepNumber = "03",
                title = "Automatic Page-Sorted Notes Sync",
                description = "Every imported book automatically gets a corresponding entry in the Notes tab. Any note or highlight you save while reading is synced with its book and sorted in ascending order based on book pages."
            )

            AboutFeatureCard(
                stepNumber = "04",
                title = "Custom Book Note Entries",
                description = "On the Notes screen, tap '+' to create a new book note entry with a customized title and cover image—or let BookFree apply a clean default editorial book cover automatically."
            )

            AboutFeatureCard(
                stepNumber = "05",
                title = "Dark & Light Themes",
                description = "Use the top-right menu (⋮) on either the Library or Notes screen to switch between Dark Mode and Light Mode at any time."
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutFeatureCard(
    stepNumber: String,
    title: String,
    description: String
) {
    val palette = LocalBookFreePalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceColor)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stepNumber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = palette.textMuted
            )
            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )
        }
        Text(
            text = description,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = palette.textSecondary
        )
    }
}
