package com.example.freebook.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.example.freebook.audio.sync.AudiobookSyncController
import com.example.freebook.audio.sync.SyncChapter
import com.example.freebook.model.AlignmentProvider
import com.example.freebook.model.AlignmentStatus
import com.example.freebook.model.AudiobookChapterMapping
import com.example.freebook.model.AudiobookGroup
import com.example.freebook.model.AudiobookTrack
import com.example.freebook.model.AudiobookUiState
import com.example.freebook.model.TtsModelDescriptor
import com.example.freebook.model.TtsUiState
import com.example.freebook.model.TtsVoiceInfo
import com.example.freebook.ui.theme.BookFreePalette
import com.example.freebook.ui.theme.LocalBookFreePalette
import java.util.Locale

private val BookFreePalette.audioAccent: Color
    get() = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsModelPickerSheet(
    ttsState: TtsUiState,
    onSelectModel: (String) -> Unit,
    onInstallLanguagePack: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.menuSurface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "TTS Engine (Model)",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )
            Text(
                text = "Choose Off to stop TTS and clear all word highlighting, or select an engine.",
                fontSize = 13.sp,
                color = palette.textSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            for (model in ttsState.availableModels) {
                val isSelected = model.id == ttsState.selectedModelId
                TtsModelOptionRow(
                    model = model,
                    isSelected = isSelected,
                    onClick = {
                        onSelectModel(model.id)
                        onDismiss()
                    }
                )
            }

            if (ttsState.missingLanguageTag != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.audioAccent.copy(alpha = 0.14f))
                        .border(1.dp, palette.audioAccent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Voice pack missing for \"${ttsState.missingLanguageTag}\"",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Install the offline system voice data so Arabic and other languages speak smoothly.",
                            fontSize = 12.sp,
                            color = palette.textSecondary
                        )
                        TextButton(onClick = onInstallLanguagePack) {
                            Text("Install System Voice Data", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Text(
                text = "Future 3rd-party TTS engines (Kokoro, Piper, ONNX) will appear in this list automatically.",
                fontSize = 11.5.sp,
                color = palette.textMuted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TtsModelOptionRow(
    model: TtsModelDescriptor,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val bg = if (isSelected) palette.textPrimary.copy(alpha = 0.10f) else palette.surfaceColor
    val border = if (isSelected) palette.textPrimary.copy(alpha = 0.45f) else palette.borderSubtle

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = model.name,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = palette.textPrimary
            )
            Text(
                text = model.description,
                fontSize = 12.sp,
                color = palette.textMuted
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(palette.textPrimary)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Active",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.appBackground
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsVoicePickerSheet(
    ttsState: TtsUiState,
    bookLanguageTag: String,
    onSelectVoice: (String) -> Unit,
    onEnableDefaultModel: () -> Unit,
    onInstallLanguagePack: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recommended = remember(ttsState.availableVoices) {
        ttsState.availableVoices.filter { it.matchesBookLanguage }
    }
    val others = remember(ttsState.availableVoices) {
        ttsState.availableVoices.filterNot { it.matchesBookLanguage }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.menuSurface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voice (${ttsState.selectedModelName})",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Book language: ${bookLanguageTag.uppercase(Locale.ROOT)}",
                        fontSize = 12.5.sp,
                        color = palette.textMuted
                    )
                }
                TextButton(onClick = onInstallLanguagePack) {
                    Text("Get Voices", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (ttsState.isOff) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceColor)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "TTS is currently Off.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Enable the Built-in TTS engine to browse and choose voices for this book.",
                        fontSize = 12.5.sp,
                        color = palette.textSecondary
                    )
                    Button(
                        onClick = onEnableDefaultModel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.textPrimary,
                            contentColor = palette.appBackground
                        )
                    ) {
                        Text("Enable Built-in TTS", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (ttsState.availableVoices.isEmpty()) {
                Text(
                    text = "No voices reported yet. Tap \"Get Voices\" above to install language voice data on your device.",
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    if (recommended.isNotEmpty()) {
                        item(key = "hdr_rec", contentType = "header") {
                            Text(
                                text = "Recommended for this book",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.audioAccent,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(
                            items = recommended,
                            key = { "rec_${it.id}" },
                            contentType = { "voice" }
                        ) { voice ->
                            VoiceRowItem(
                                voice = voice,
                                isSelected = voice.id == ttsState.selectedVoice?.id,
                                onClick = {
                                    onSelectVoice(voice.id)
                                    onDismiss()
                                }
                            )
                        }
                    }

                    if (others.isNotEmpty()) {
                        item(key = "hdr_other", contentType = "header") {
                            Text(
                                text = "All Installed Voices",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textMuted,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(
                            items = others.take(60),
                            key = { "oth_${it.id}" },
                            contentType = { "voice" }
                        ) { voice ->
                            VoiceRowItem(
                                voice = voice,
                                isSelected = voice.id == ttsState.selectedVoice?.id,
                                onClick = {
                                    onSelectVoice(voice.id)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRowItem(
    voice: TtsVoiceInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) palette.textPrimary.copy(alpha = 0.10f) else palette.surfaceColor)
            .border(
                width = 1.dp,
                color = if (isSelected) palette.textPrimary.copy(alpha = 0.45f) else palette.borderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = voice.displayName,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = voice.localeTag,
                fontSize = 11.5.sp,
                color = palette.textMuted
            )
        }
        if (isSelected) {
            Text(
                text = "Selected",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.audioAccent
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedSheet(
    title: String,
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val speeds = remember { listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.menuSurface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (speed in speeds.take(4)) {
                    SpeedChip(
                        speed = speed,
                        selected = kotlin.math.abs(currentSpeed - speed) < 0.05f,
                        onClick = {
                            onSelectSpeed(speed)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (speed in speeds.drop(4)) {
                    SpeedChip(
                        speed = speed,
                        selected = kotlin.math.abs(currentSpeed - speed) < 0.05f,
                        onClick = {
                            onSelectSpeed(speed)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpeedChip(
    speed: Float,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    val label = if (speed % 1f == 0f) "${speed.toInt()}.0x" else "${speed}x"
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) palette.textPrimary else palette.surfaceColor)
            .border(1.dp, if (selected) palette.textPrimary else palette.borderSubtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) palette.appBackground else palette.textPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    remainingMs: Long?,
    onSelectMinutes: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val presets = remember { listOf<Int?>(null, 5, 10, 15, 30, 45, 60) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.menuSurface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Sleep Timer",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary
            )
            if (remainingMs != null && remainingMs > 0L) {
                Text(
                    text = "Stopping in ${AudiobookSyncController.formatMs(remainingMs)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.audioAccent
                )
            }
            for (mins in presets) {
                val label = if (mins == null) "Off" else "$mins minutes"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surfaceColor)
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp))
                        .clickable {
                            onSelectMinutes(mins)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, fontSize = 14.5.sp, color = palette.textPrimary)
                    if (mins == null && remainingMs == null) {
                        Text("Active", fontSize = 12.sp, color = palette.textMuted)
                    }
                }
            }
        }
    }
}

/**
 * Full Audiobook Manager Sheet opened from the Audiobook Dock's "Audiobook" / "Book" button.
 * Supports multi-file audio import, chapter/category creation & grouping, manual + 1-tap Auto-Match
 * mapping to EPUB or PDF book content, 1-tap Sync Here anchor calibration, and Free Tier
 * on-demand Local MMS-FA Word Highlighting Pack download & lazy per-chapter alignment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookManagerSheet(
    audiobookState: AudiobookUiState,
    bookChapters: List<SyncChapter>,
    isPdfBook: Boolean,
    totalPdfPages: Int,
    currentBookPageIndex: Int,
    currentBookCharOffset: Int,
    onImportAudioFiles: () -> Unit,
    onCreateGroup: (title: String) -> Unit,
    onRenameGroup: (groupId: String, newTitle: String) -> Unit,
    onMoveGroupOrder: (groupId: String, moveUp: Boolean) -> Unit,
    onDeleteGroup: (groupId: String) -> Unit,
    onPlayGroup: (groupId: String) -> Unit,
    onPlayTrack: (trackId: String) -> Unit,
    onMoveTrackToGroup: (trackId: String, targetGroupId: String) -> Unit,
    onDeleteTrack: (track: AudiobookTrack) -> Unit,
    onAutoMatchChapters: () -> Unit,
    onSaveMapping: (
        groupId: String,
        chapterTitle: String,
        startPage: Int,
        endPage: Int,
        startChar: Int,
        endChar: Int
    ) -> Unit,
    onRemoveMapping: (mappingId: String) -> Unit,
    onCalibrateSyncHere: () -> Unit,
    onClearAnchors: (mappingId: String) -> Unit,
    onDownloadLocalPack: () -> Unit,
    onImportLocalPackFile: () -> Unit,
    onDeleteLocalPack: () -> Unit,
    onAlignChapterLocal: (mappingId: String) -> Unit,
    onToggleFollowSync: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var newGroupTitle by remember { mutableStateOf("") }
    var mappingTargetGroup by remember { mutableStateOf<AudiobookGroup?>(null) }
    var moveTrackTarget by remember { mutableStateOf<AudiobookTrack?>(null) }
    var renameTargetGroup by remember { mutableStateOf<AudiobookGroup?>(null) }
    var renameText by remember { mutableStateOf("") }

    val tracksByGroup = remember(audiobookState.tracks) {
        audiobookState.tracks.groupBy { it.groupId }
    }
    val mappingsByGroup = remember(audiobookState.mappings) {
        audiobookState.mappings.associateBy { it.groupId }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.menuSurface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Audiobook & Sync",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = if (audiobookState.hasAudio) {
                            "${audiobookState.tracks.size} file(s) · ${AudiobookSyncController.formatMs(audiobookState.totalDurationMs)}"
                        } else {
                            "Import audio files and map chapters to sync"
                        },
                        fontSize = 12.sp,
                        color = palette.textMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (audiobookState.followSyncEnabled) palette.audioAccent.copy(alpha = 0.18f)
                            else palette.surfaceColor
                        )
                        .border(
                            1.dp,
                            if (audiobookState.followSyncEnabled) palette.audioAccent else palette.borderSubtle,
                            CircleShape
                        )
                        .clickable { onToggleFollowSync(!audiobookState.followSyncEnabled) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (audiobookState.followSyncEnabled) "Auto-Sync: On" else "Auto-Sync: Off",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (audiobookState.followSyncEnabled) palette.audioAccent else palette.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Primary action pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportAudioFiles,
                    modifier = Modifier.weight(1.2f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.textPrimary,
                        contentColor = palette.appBackground
                    )
                ) {
                    Text("+ Import Audio", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        newGroupTitle = "Chapter ${audiobookState.groups.size + 1}"
                        showCreateGroupDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text("+ Category", fontSize = 12.sp, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onAutoMatchChapters,
                    enabled = audiobookState.groups.isNotEmpty() && bookChapters.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text("Auto-Match", fontSize = 12.sp, maxLines = 1)
                }
            }

            if (audiobookState.hasAudio) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCalibrateSyncHere,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("1-Tap Sync Here (Anchor)", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Free Tier Local Word Highlighting Pack (On-Demand Download) + Phase 3 Cloud note
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surfaceColor)
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Word Highlighting Pack (Free · Local ONNX)",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = if (audiobookState.isLocalPackInstalled) "Installed" else "Optional",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (audiobookState.isLocalPackInstalled) Color(0xFF22C55E) else palette.textMuted
                        )
                    }

                    val dlPct = audiobookState.localPackDownloadProgress
                    if (dlPct != null) {
                        LinearProgressIndicator(
                            progress = { (dlPct / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Downloading Meta MMS-300M-FA pack ($dlPct%)...",
                            fontSize = 11.5.sp,
                            color = palette.textSecondary
                        )
                    } else if (audiobookState.isLocalPackInstalled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Ready for offline word sync (Arabic, English & 1,000+ languages).",
                                fontSize = 11.5.sp,
                                color = palette.textSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDeleteLocalPack) {
                                Text("Remove", fontSize = 11.5.sp, color = Color(0xFFEF4444))
                            }
                        }
                    } else {
                        Text(
                            text = "Instant Sync works right now with 0 MB download. Download MMS-300M-FA (~95 MB) on demand for offline word-level highlighting.",
                            fontSize = 11.5.sp,
                            color = palette.textSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onDownloadLocalPack) {
                                Text("Download Pack", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(onClick = onImportLocalPackFile) {
                                Text("Import .onnx", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (audiobookState.groups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No audiobook files imported for this book yet.\nTap \"+ Import Audio\" to select one or multiple MP3 / M4B / M4A files.",
                        fontSize = 13.sp,
                        color = palette.textMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = audiobookState.groups,
                        key = { it.id },
                        contentType = { "group" }
                    ) { group ->
                        val groupTracks = tracksByGroup[group.id].orEmpty()
                        val mapping = mappingsByGroup[group.id]
                        val alignment = mapping?.let { audiobookState.alignments[it.id] }
                        val isPlayingGroup = audiobookState.activeGroupId == group.id

                        AudiobookGroupCard(
                            group = group,
                            tracks = groupTracks,
                            mapping = mapping,
                            alignmentStatus = alignment?.status ?: AlignmentStatus.NONE,
                            alignmentProvider = alignment?.provider ?: AlignmentProvider.NONE,
                            alignmentProgress = alignment?.progressPercent ?: 0,
                            alignmentError = alignment?.errorMessage.orEmpty(),
                            isPdfBook = isPdfBook,
                            isActiveGroup = isPlayingGroup,
                            activeTrackId = audiobookState.activeTrackId,
                            isLocalPackInstalled = audiobookState.isLocalPackInstalled,
                            onPlayGroup = { onPlayGroup(group.id) },
                            onMapGroup = { mappingTargetGroup = group },
                            onRenameGroup = {
                                renameTargetGroup = group
                                renameText = group.title
                            },
                            onMoveUp = { onMoveGroupOrder(group.id, true) },
                            onMoveDown = { onMoveGroupOrder(group.id, false) },
                            onDeleteGroup = { onDeleteGroup(group.id) },
                            onPlayTrack = onPlayTrack,
                            onMoveTrack = { moveTrackTarget = it },
                            onDeleteTrack = onDeleteTrack,
                            onAlignLocal = {
                                if (mapping != null) onAlignChapterLocal(mapping.id)
                            },
                            onDownloadPack = onDownloadLocalPack
                        )
                    }
                }
            }
        }
    }

    // Create Category Dialog
    if (showCreateGroupDialog) {
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            containerColor = palette.menuSurface,
            title = { Text("New Chapter / Category", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newGroupTitle,
                    onValueChange = { newGroupTitle = it },
                    label = { Text("Category Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onCreateGroup(newGroupTitle)
                    showCreateGroupDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename Category Dialog
    renameTargetGroup?.let { grp ->
        AlertDialog(
            onDismissRequest = { renameTargetGroup = null },
            containerColor = palette.menuSurface,
            title = { Text("Rename Category", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Category Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameGroup(grp.id, renameText)
                    renameTargetGroup = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetGroup = null }) { Text("Cancel") }
            }
        )
    }

    // Move Track to Category Dialog
    moveTrackTarget?.let { track ->
        AlertDialog(
            onDismissRequest = { moveTrackTarget = null },
            containerColor = palette.menuSurface,
            title = { Text("Move \"${track.title}\"", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (grp in audiobookState.groups) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.surfaceColor)
                                .clickable {
                                    onMoveTrackToGroup(track.id, grp.id)
                                    moveTrackTarget = null
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(grp.title, fontSize = 14.sp, color = palette.textPrimary)
                            if (grp.id == track.groupId) {
                                Text("Current", fontSize = 11.5.sp, color = palette.textMuted)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moveTrackTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Manual Book Chapter / Page Range Mapping Dialog
    mappingTargetGroup?.let { grp ->
        val existingMapping = mappingsByGroup[grp.id]
        AudiobookChapterMappingDialog(
            group = grp,
            existingMapping = existingMapping,
            bookChapters = bookChapters,
            isPdfBook = isPdfBook,
            totalPdfPages = totalPdfPages,
            currentBookPageIndex = currentBookPageIndex,
            currentBookCharOffset = currentBookCharOffset,
            onSave = { title, startP, endP, startC, endC ->
                onSaveMapping(grp.id, title, startP, endP, startC, endC)
                mappingTargetGroup = null
            },
            onRemoveMapping = {
                if (existingMapping != null) onRemoveMapping(existingMapping.id)
                mappingTargetGroup = null
            },
            onClearAnchors = {
                if (existingMapping != null) onClearAnchors(existingMapping.id)
            },
            onDismiss = { mappingTargetGroup = null }
        )
    }
}

@Composable
private fun AudiobookGroupCard(
    group: AudiobookGroup,
    tracks: List<AudiobookTrack>,
    mapping: AudiobookChapterMapping?,
    alignmentStatus: AlignmentStatus,
    alignmentProvider: AlignmentProvider,
    alignmentProgress: Int,
    alignmentError: String,
    isPdfBook: Boolean,
    isActiveGroup: Boolean,
    activeTrackId: String?,
    isLocalPackInstalled: Boolean,
    onPlayGroup: () -> Unit,
    onMapGroup: () -> Unit,
    onRenameGroup: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDeleteGroup: () -> Unit,
    onPlayTrack: (String) -> Unit,
    onMoveTrack: (AudiobookTrack) -> Unit,
    onDeleteTrack: (AudiobookTrack) -> Unit,
    onAlignLocal: () -> Unit,
    onDownloadPack: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    val totalDurMs = remember(tracks) { tracks.sumOf { it.effectiveDurationMs } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActiveGroup) palette.audioAccent.copy(alpha = 0.08f) else palette.surfaceColor)
            .border(
                1.dp,
                if (isActiveGroup) palette.audioAccent.copy(alpha = 0.5f) else palette.borderSubtle,
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onPlayGroup)
            ) {
                Text(
                    text = group.title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${tracks.size} track(s) · ${AudiobookSyncController.formatMs(totalDurMs)}",
                    fontSize = 11.5.sp,
                    color = palette.textMuted
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniTextAction("Play", onPlayGroup, accent = true)
                MiniTextAction("Map", onMapGroup)
                MiniTextAction("↑", onMoveUp)
                MiniTextAction("↓", onMoveDown)
                MiniTextAction("Rename", onRenameGroup)
                MiniTextAction("×", onDeleteGroup, danger = true)
            }
        }

        // Mapping & Sync Status Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val mapLabel = when {
                mapping == null -> "Not synced to book yet"
                isPdfBook -> {
                    val range = "Pages ${mapping.bookStartPageIndex + 1}–${mapping.bookEndPageIndex + 1}"
                    val ch = mapping.bookChapterTitle.takeIf { it.isNotBlank() }
                    val anchorTag = if (mapping.anchors.isNotEmpty()) " · ${mapping.anchors.size} anchor(s)" else ""
                    (if (ch != null) "$ch ($range)" else range) + anchorTag
                }
                else -> {
                    val ch = mapping.bookChapterTitle.ifBlank { "Mapped range" }
                    val anchorTag = if (mapping.anchors.isNotEmpty()) " · ${mapping.anchors.size} anchor(s)" else ""
                    "$ch$anchorTag"
                }
            }

            Text(
                text = mapLabel,
                fontSize = 12.sp,
                color = if (mapping != null) palette.audioAccent else palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (mapping != null) {
                when (alignmentStatus) {
                    AlignmentStatus.RUNNING, AlignmentStatus.QUEUED -> {
                        Text(
                            text = "Aligning $alignmentProgress%",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.audioAccent
                        )
                    }
                    AlignmentStatus.READY -> {
                        val provTag = if (alignmentProvider == AlignmentProvider.CLOUD_PRO) "Cloud Pro" else "Word Synced"
                        Text(
                            text = provTag,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF22C55E)
                        )
                    }
                    AlignmentStatus.NEEDS_CLOUD_RECOMMENDED -> {
                        Text(
                            text = "Edition Mismatch",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF59E0B)
                        )
                    }
                    else -> {
                        MiniTextAction(
                            label = if (isLocalPackInstalled) "Align Words" else "Get Word Sync",
                            onClick = if (isLocalPackInstalled) onAlignLocal else onDownloadPack,
                            accent = true
                        )
                    }
                }
            }
        }

        if (alignmentError.isNotBlank() && (alignmentStatus == AlignmentStatus.FAILED || alignmentStatus == AlignmentStatus.NEEDS_CLOUD_RECOMMENDED)) {
            Text(
                text = alignmentError,
                fontSize = 11.sp,
                color = Color(0xFFF59E0B)
            )
        }

        if (tracks.isNotEmpty()) {
            HorizontalDivider(color = palette.borderSubtle)
            for (track in tracks) {
                val isCurrent = track.id == activeTrackId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPlayTrack(track.id) }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = (if (isCurrent) "▶ " else "") + track.title,
                        fontSize = 12.5.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCurrent) palette.audioAccent else palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = AudiobookSyncController.formatMs(track.effectiveDurationMs),
                        fontSize = 11.5.sp,
                        color = palette.textMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MiniTextAction("Move", { onMoveTrack(track) })
                    MiniTextAction("×", { onDeleteTrack(track) }, danger = true)
                }
            }
        }
    }
}

@Composable
private fun MiniTextAction(
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    danger: Boolean = false
) {
    val palette = LocalBookFreePalette.current
    val textColor = when {
        danger -> Color(0xFFEF4444)
        accent -> palette.audioAccent
        else -> palette.textSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.textPrimary.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1
        )
    }
}

@Composable
private fun AudiobookChapterMappingDialog(
    group: AudiobookGroup,
    existingMapping: AudiobookChapterMapping?,
    bookChapters: List<SyncChapter>,
    isPdfBook: Boolean,
    totalPdfPages: Int,
    currentBookPageIndex: Int,
    currentBookCharOffset: Int,
    onSave: (title: String, startPage: Int, endPage: Int, startChar: Int, endChar: Int) -> Unit,
    onRemoveMapping: () -> Unit,
    onClearAnchors: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    var startPageText by remember {
        mutableStateOf(((existingMapping?.bookStartPageIndex ?: currentBookPageIndex) + 1).toString())
    }
    var endPageText by remember {
        mutableStateOf(
            ((existingMapping?.bookEndPageIndex ?: (currentBookPageIndex + 10).coerceAtMost((totalPdfPages - 1).coerceAtLeast(0))) + 1).toString()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.menuSurface,
        title = {
            Text(
                text = "Map \"${group.title}\" to Book",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isPdfBook) {
                    Text(
                        text = "Manual Page Range (1–$totalPdfPages)",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = startPageText,
                            onValueChange = { startPageText = it.filter(Char::isDigit) },
                            label = { Text("Start Page") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endPageText,
                            onValueChange = { endPageText = it.filter(Char::isDigit) },
                            label = { Text("End Page") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { startPageText = (currentBookPageIndex + 1).toString() }) {
                            Text("Start = Current (p.${currentBookPageIndex + 1})", fontSize = 12.sp)
                        }
                        Button(onClick = {
                            val sp = ((startPageText.toIntOrNull() ?: 1) - 1).coerceIn(0, (totalPdfPages - 1).coerceAtLeast(0))
                            val ep = ((endPageText.toIntOrNull() ?: (sp + 1)) - 1).coerceIn(sp, (totalPdfPages - 1).coerceAtLeast(0))
                            onSave("Pages ${sp + 1}–${ep + 1}", sp, ep, 0, 0)
                        }) {
                            Text("Save Range", fontSize = 12.sp)
                        }
                    }
                } else {
                    TextButton(onClick = {
                        onSave("From current position", 0, 0, currentBookCharOffset, 0)
                    }) {
                        Text("Map from Current Reading Position", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (bookChapters.isNotEmpty()) {
                    HorizontalDivider(color = palette.borderSubtle)
                    Text(
                        text = "Or pick a Book Chapter:",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = bookChapters,
                            key = { it.id },
                            contentType = { "chapter" }
                        ) { chapter ->
                            val sub = if (isPdfBook) {
                                "Pages ${chapter.startPageIndex + 1}–${chapter.endPageIndex + 1}"
                            } else {
                                "EPUB Section"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surfaceColor)
                                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                                    .clickable {
                                        onSave(
                                            chapter.title,
                                            chapter.startPageIndex,
                                            chapter.endPageIndex,
                                            chapter.startCharOffset,
                                            chapter.endCharOffset
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chapter.title,
                                    fontSize = 13.sp,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(sub, fontSize = 11.sp, color = palette.textMuted)
                            }
                        }
                    }
                }

                if (existingMapping != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (existingMapping.anchors.isNotEmpty()) {
                            TextButton(onClick = onClearAnchors) {
                                Text("Clear Anchors (${existingMapping.anchors.size})", fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = onRemoveMapping) {
                            Text("Unmap", fontSize = 12.sp, color = Color(0xFFEF4444))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
