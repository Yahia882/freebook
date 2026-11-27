package com.example.freebook.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.freebook.audio.playback.ReorderedAudioEntry
import com.example.freebook.audio.sync.AudiobookSyncController
import com.example.freebook.audio.sync.SyncChapter
import com.example.freebook.model.AlignmentProvider
import com.example.freebook.model.AlignmentStatus
import com.example.freebook.model.AudiobookChapterMapping
import com.example.freebook.model.AudiobookGroup
import com.example.freebook.model.AudiobookMappingResolver
import com.example.freebook.model.AudiobookTrack
import com.example.freebook.model.AudiobookUiState
import com.example.freebook.model.TtsModelDescriptor
import com.example.freebook.model.TtsUiState
import com.example.freebook.model.TtsVoiceInfo
import com.example.freebook.ui.components.CheckmarkIcon
import com.example.freebook.ui.components.ChevronIcon
import com.example.freebook.ui.components.DragHandleIcon
import com.example.freebook.ui.components.MoreDotsIcon
import com.example.freebook.ui.components.PlusIcon
import com.example.freebook.ui.theme.BookFreePalette
import com.example.freebook.ui.theme.LocalBookFreePalette
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
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
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
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
                        .weight(1f, fill = false),
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
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
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

private sealed interface MappingSheetTarget {
    val displayTitle: String
    val groupId: String
    val trackId: String

    data class TrackTarget(
        val group: AudiobookGroup,
        val track: AudiobookTrack,
        val parentCategoryOverride: AudiobookChapterMapping?
    ) : MappingSheetTarget {
        override val displayTitle: String get() = track.title
        override val groupId: String get() = group.id
        override val trackId: String get() = track.id
    }

    data class CategoryTarget(
        val group: AudiobookGroup,
        val trackCount: Int
    ) : MappingSheetTarget {
        override val displayTitle: String get() = group.title
        override val groupId: String get() = group.id
        override val trackId: String get() = ""
    }
}

private sealed interface RenameSheetTarget {
    val currentTitle: String
    val label: String

    data class Category(val group: AudiobookGroup) : RenameSheetTarget {
        override val currentTitle: String get() = group.title
        override val label: String get() = "Rename Category"
    }

    data class Track(val track: AudiobookTrack) : RenameSheetTarget {
        override val currentTitle: String get() = track.title
        override val label: String get() = "Rename Chapter"
    }
}

/**
 * Clean, distraction-free Audiobook Chapters sheet modeled after minimalist editorial chapter lists:
 * - Left-aligned "Chapters" title with "Import Audio" button + overflow menu (Auto-Sync, Add Category,
 *   Sync Anchor, Auto-Match, Word Highlighting Pack sheet).
 * - Flat hairline-separated chapter rows displaying Start/Duration, blue active checkmark, and a
 *   per-chapter options menu (Map to Content with range handles, Align Words, Reorder/Move, Rename, Delete).
 * - Interactive drag-to-reorder across top-level chapters and embedded categories.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AudiobookManagerSheet(
    audiobookState: AudiobookUiState,
    bookChapters: List<SyncChapter>,
    isPdfBook: Boolean,
    totalPdfPages: Int,
    currentBookPageIndex: Int,
    currentBookCharOffset: Int,
    onImportAudioFiles: (targetGroupId: String?) -> Unit,
    onCreateGroup: (title: String) -> Unit,
    onRenameGroup: (groupId: String, newTitle: String) -> Unit,
    onRenameTrack: (trackId: String, newTitle: String) -> Unit,
    onToggleGroupCollapsed: (groupId: String, collapsed: Boolean) -> Unit,
    onApplyReorderedStructure: (List<ReorderedAudioEntry>) -> Unit,
    onDeleteGroup: (groupId: String) -> Unit,
    onPlayTrack: (trackId: String) -> Unit,
    onDeleteTrack: (track: AudiobookTrack) -> Unit,
    onAutoMatchChapters: () -> Unit,
    onSaveMapping: (
        groupId: String,
        trackId: String,
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
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var isReorderMode by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showWordPackSheet by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var newGroupTitle by remember { mutableStateOf("") }
    var mappingTarget by remember { mutableStateOf<MappingSheetTarget?>(null) }
    var renameTarget by remember { mutableStateOf<RenameSheetTarget?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteConfirmCategory by remember { mutableStateOf<Pair<AudiobookGroup, Int>?>(null) }

    val latestIsReorderMode by rememberUpdatedState(isReorderMode)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { targetValue ->
            if (latestIsReorderMode && targetValue == SheetValue.Hidden) false else true
        }
    )

    val groupMappingsByGroupId = remember(audiobookState.mappings) {
        audiobookState.mappings.filter { it.isCategoryLevel }.associateBy { it.groupId }
    }
    val trackMappingsByTrackId = remember(audiobookState.mappings) {
        audiobookState.mappings.filter { it.isTrackLevel }.associateBy { it.trackId }
    }
    val tracksByGroup = remember(audiobookState.tracks) {
        audiobookState.tracks.groupBy { it.groupId }
    }
    val collapsedCategoryTracks = remember(audiobookState.groups, tracksByGroup) {
        audiobookState.groups
            .filter { it.isCollapsed }
            .associate { it.id to tracksByGroup[it.id].orEmpty() }
    }

    val computedRows = remember(audiobookState.groups, audiobookState.tracks, isReorderMode) {
        buildAudiobookListRows(
            groups = audiobookState.groups,
            tracks = audiobookState.tracks,
            isReorderMode = isReorderMode
        )
    }

    var reorderRows by remember { mutableStateOf(computedRows) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    // Keep live working list synchronized whenever not actively dragging a finger
    LaunchedEffect(computedRows, draggingKey) {
        if (draggingKey == null) {
            reorderRows = computedRows
        }
    }

    val displayedRows = if (isReorderMode) reorderRows else computedRows
    val listState = rememberLazyListState()
    val categoryDragStepPx = with(density) { 52.dp.toPx() }
    val autoScrollEdgePx = with(density) { 48.dp.toPx() }
    val autoScrollStepPx = with(density) { 12.dp.toPx() }

    val latestCollapsedTracks by rememberUpdatedState(collapsedCategoryTracks)
    val latestOnApplyReorder by rememberUpdatedState(onApplyReorderedStructure)
    val latestComputedRows by rememberUpdatedState(computedRows)

    ModalBottomSheet(
        onDismissRequest = {
            if (isReorderMode) {
                isReorderMode = false
                draggingKey = null
                dragOffsetY = 0f
            } else {
                onDismiss()
            }
        },
        sheetState = sheetState,
        containerColor = palette.menuSurface,
        contentColor = palette.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // =================================================================
            // CLEAN LEFT-ALIGNED HEADER (no exit button)
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (isReorderMode) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Reorder Chapters",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Hold and drag ≡ anywhere, including into categories",
                            fontSize = 12.sp,
                            color = palette.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(palette.audioAccent)
                            .clickable {
                                isReorderMode = false
                                draggingKey = null
                                dragOffsetY = 0f
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Done",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Chapters",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            maxLines = 1
                        )
                        val count = audiobookState.tracks.size
                        val subtitle = if (audiobookState.hasAudio) {
                            val syncTag = if (!audiobookState.followSyncEnabled) " · Sync Off" else ""
                            "$count ${if (count == 1) "chapter" else "chapters"} · ${formatChapterTimestamp(audiobookState.totalDurationMs)}$syncTag"
                        } else {
                            "Import audio files to start listening"
                        }
                        Text(
                            text = subtitle,
                            fontSize = 12.sp,
                            color = palette.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFeatureSettings = "tnum")
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Primary "Import Audio" pill on far right before More Options
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(palette.audioAccent.copy(alpha = 0.14f))
                                .border(
                                    width = 1.dp,
                                    color = palette.audioAccent.copy(alpha = 0.35f),
                                    shape = CircleShape
                                )
                                .clickable { onImportAudioFiles(null) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            PlusIcon(color = palette.audioAccent, size = 12.dp)
                            Text(
                                text = "Import Audio",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.audioAccent,
                                maxLines = 1
                            )
                        }

                        // Header More Options icon button (Auto-Sync, Add Category, Anchor, Auto-Match, Word Pack)
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(palette.surfaceColor)
                                    .border(1.dp, palette.borderSubtle, CircleShape)
                                    .clickable { showHeaderMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                MoreDotsIcon(
                                    color = palette.textPrimary,
                                    vertical = true,
                                    size = 17.dp
                                )
                            }

                            DropdownMenu(
                                expanded = showHeaderMenu,
                                onDismissRequest = { showHeaderMenu = false },
                                containerColor = palette.menuSurface
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Auto-Sync",
                                                fontSize = 14.sp,
                                                color = palette.textPrimary
                                            )
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text(
                                                text = if (audiobookState.followSyncEnabled) "On" else "Off",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (audiobookState.followSyncEnabled) palette.audioAccent else palette.textMuted
                                            )
                                        }
                                    },
                                    onClick = {
                                        onToggleFollowSync(!audiobookState.followSyncEnabled)
                                        showHeaderMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Add Category",
                                            fontSize = 14.sp,
                                            color = palette.textPrimary
                                        )
                                    },
                                    onClick = {
                                        showHeaderMenu = false
                                        val catCount = audiobookState.groups.count { it.isCategory }
                                        newGroupTitle = "Part ${catCount + 1}"
                                        showCreateGroupDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    enabled = audiobookState.hasAudio,
                                    text = {
                                        Column {
                                            Text(
                                                text = "Sync Anchor",
                                                fontSize = 14.sp,
                                                color = if (audiobookState.hasAudio) palette.textPrimary else palette.textMuted
                                            )
                                            Text(
                                                text = "Pin current audio to this page",
                                                fontSize = 11.5.sp,
                                                color = palette.textMuted
                                            )
                                        }
                                    },
                                    onClick = {
                                        showHeaderMenu = false
                                        onCalibrateSyncHere()
                                    }
                                )

                                DropdownMenuItem(
                                    enabled = audiobookState.hasAudio && bookChapters.isNotEmpty(),
                                    text = {
                                        Column {
                                            Text(
                                                text = "Auto-Match",
                                                fontSize = 14.sp,
                                                color = if (audiobookState.hasAudio && bookChapters.isNotEmpty()) {
                                                    palette.textPrimary
                                                } else {
                                                    palette.textMuted
                                                }
                                            )
                                            Text(
                                                text = "Match audio to book contents",
                                                fontSize = 11.5.sp,
                                                color = palette.textMuted
                                            )
                                        }
                                    },
                                    onClick = {
                                        showHeaderMenu = false
                                        onAutoMatchChapters()
                                    }
                                )

                                HorizontalDivider(color = palette.borderSubtle.copy(alpha = 0.65f))

                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Word Highlighting Pack",
                                                fontSize = 14.sp,
                                                color = palette.textPrimary
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            val dl = audiobookState.localPackDownloadProgress
                                            val badge = when {
                                                dl != null -> "$dl%"
                                                audiobookState.isLocalPackInstalled -> "Installed"
                                                else -> "Optional"
                                            }
                                            val badgeColor = when {
                                                dl != null -> palette.audioAccent
                                                audiobookState.isLocalPackInstalled -> Color(0xFF22C55E)
                                                else -> palette.textMuted
                                            }
                                            Text(
                                                text = badge,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = badgeColor
                                            )
                                        }
                                    },
                                    onClick = {
                                        showHeaderMenu = false
                                        showWordPackSheet = true
                                    }
                                )

                                if (audiobookState.groups.isNotEmpty()) {
                                    HorizontalDivider(color = palette.borderSubtle.copy(alpha = 0.65f))
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Reorder Chapters",
                                                fontSize = 14.sp,
                                                color = palette.textPrimary
                                            )
                                        },
                                        onClick = {
                                            showHeaderMenu = false
                                            isReorderMode = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = palette.borderSubtle.copy(alpha = 0.7f))

            // =================================================================
            // CHAPTERS LIST (Reference aesthetic: clean flat rows + dividers)
            // =================================================================
            if (displayedRows.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 42.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "No audio chapters yet",
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Tap \"Import Audio\" above to add MP3, M4B, or M4A files for this book.",
                        fontSize = 13.sp,
                        color = palette.textMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    itemsIndexed(
                        items = displayedRows,
                        key = { _, row -> row.stableKey },
                        contentType = { _, row ->
                            when (row) {
                                is AudiobookListRow.StandaloneTrack,
                                is AudiobookListRow.CategoryTrack -> "audio_track"
                                is AudiobookListRow.CategoryHeader -> "category_header"
                                is AudiobookListRow.EmptyCategoryPlaceholder -> "empty_category"
                                is AudiobookListRow.CategoryEndMarker -> "category_end"
                            }
                        }
                    ) { _, row ->
                        val isDraggingThis = draggingKey == row.stableKey

                        fun buildDragModifier(rowKey: String, isCategoryRow: Boolean): Modifier {
                            if (!isReorderMode) return Modifier
                            return Modifier.pointerInput(rowKey, isReorderMode) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingKey = rowKey
                                        dragOffsetY = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = {
                                        val finalRows = reorderRows
                                        draggingKey = null
                                        dragOffsetY = 0f
                                        val entries = extractReorderedEntries(finalRows, latestCollapsedTracks)
                                        latestOnApplyReorder(entries)
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        dragOffsetY = 0f
                                        reorderRows = latestComputedRows
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (draggingKey != rowKey) return@detectDragGestures
                                        dragOffsetY += dragAmount.y

                                        val currentIdx = reorderRows.indexOfFirst { it.stableKey == rowKey }
                                        if (currentIdx < 0) return@detectDragGestures

                                        val visibleItems = listState.layoutInfo.visibleItemsInfo
                                        val myInfo = visibleItems.firstOrNull { it.key == rowKey }

                                        if (isCategoryRow) {
                                            if (abs(dragOffsetY) >= categoryDragStepPx) {
                                                val movingDown = dragOffsetY > 0f
                                                val targetIdx = if (movingDown) {
                                                    (currentIdx + 1).coerceAtMost(reorderRows.lastIndex)
                                                } else {
                                                    (currentIdx - 1).coerceAtLeast(0)
                                                }
                                                val (nextRows, newIdx) = moveRowInReorderList(
                                                    rows = reorderRows,
                                                    fromIndex = currentIdx,
                                                    targetIndex = targetIdx,
                                                    collapsedCategoryTracks = latestCollapsedTracks
                                                )
                                                if (newIdx != currentIdx) {
                                                    reorderRows = nextRows
                                                    dragOffsetY = 0f
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        } else if (myInfo != null) {
                                            val draggedCenter = myInfo.offset + myInfo.size / 2f + dragOffsetY
                                            val viewportStart = listState.layoutInfo.viewportStartOffset
                                            val viewportEnd = listState.layoutInfo.viewportEndOffset
                                            if (draggedCenter < viewportStart + autoScrollEdgePx) {
                                                coroutineScope.launch { listState.scrollBy(-autoScrollStepPx) }
                                            } else if (draggedCenter > viewportEnd - autoScrollEdgePx) {
                                                coroutineScope.launch { listState.scrollBy(autoScrollStepPx) }
                                            }

                                            val targetInfo = visibleItems.firstOrNull { candidate ->
                                                candidate.key != rowKey &&
                                                    draggedCenter in candidate.offset.toFloat()..(candidate.offset + candidate.size).toFloat()
                                            }
                                            if (targetInfo != null) {
                                                val targetIdx = targetInfo.index.coerceIn(0, reorderRows.lastIndex)
                                                val movingDown = targetIdx > currentIdx && draggedCenter > targetInfo.offset + targetInfo.size * 0.45f
                                                val movingUp = targetIdx < currentIdx && draggedCenter < targetInfo.offset + targetInfo.size * 0.55f
                                                if (movingDown || movingUp) {
                                                    val oldOffset = myInfo.offset
                                                    val (nextRows, newIdx) = moveRowInReorderList(
                                                        rows = reorderRows,
                                                        fromIndex = currentIdx,
                                                        targetIndex = targetIdx,
                                                        collapsedCategoryTracks = latestCollapsedTracks
                                                    )
                                                    if (newIdx != currentIdx) {
                                                        reorderRows = nextRows
                                                        dragOffsetY -= (targetInfo.offset - oldOffset).toFloat()
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        val liftModifier = Modifier
                            .zIndex(if (isDraggingThis) 2f else 0f)
                            .graphicsLayer {
                                if (isDraggingThis) {
                                    translationY = dragOffsetY
                                    scaleX = 1.015f
                                    scaleY = 1.015f
                                    shadowElevation = 18f
                                }
                            }
                            .background(
                                if (isDraggingThis) palette.surfaceColor else Color.Transparent
                            )

                        when (row) {
                            is AudiobookListRow.StandaloneTrack,
                            is AudiobookListRow.CategoryTrack -> {
                                val isEmbedded = row is AudiobookListRow.CategoryTrack
                                val track = when (row) {
                                    is AudiobookListRow.StandaloneTrack -> row.track
                                    is AudiobookListRow.CategoryTrack -> row.track
                                }
                                val group = when (row) {
                                    is AudiobookListRow.StandaloneTrack -> row.group
                                    is AudiobookListRow.CategoryTrack -> row.group
                                }
                                val startOffsetMs = when (row) {
                                    is AudiobookListRow.StandaloneTrack -> row.startOffsetMs
                                    is AudiobookListRow.CategoryTrack -> row.startOffsetMs
                                }
                                val chapterNumber = when (row) {
                                    is AudiobookListRow.StandaloneTrack -> row.chapterNumber
                                    is AudiobookListRow.CategoryTrack -> row.chapterNumber
                                }

                                val parentCatMapping = if (isEmbedded) groupMappingsByGroupId[group.id] else null
                                val ownTrackMapping = if (isEmbedded) {
                                    trackMappingsByTrackId[track.id]
                                } else {
                                    AudiobookMappingResolver.resolveForTrack(track, audiobookState.mappings)
                                }
                                val effectiveMapping = parentCatMapping ?: ownTrackMapping
                                val alignment = if (parentCatMapping == null) {
                                    ownTrackMapping?.let { audiobookState.alignments[it.id] }
                                } else {
                                    null
                                }
                                val isActive = track.id == audiobookState.activeTrackId

                                CleanChapterTrackRow(
                                    displayTitle = formatChapterDisplayTitle(chapterNumber, track.title),
                                    startOffsetMs = startOffsetMs,
                                    durationMs = track.effectiveDurationMs,
                                    mappingLabel = if (parentCatMapping == null) {
                                        ownTrackMapping?.let { buildMappingStatusLabel(it, isPdfBook) }
                                    } else {
                                        null
                                    },
                                    overriddenByCategoryTitle = if (parentCatMapping != null) group.title else null,
                                    alignmentStatus = alignment?.status ?: AlignmentStatus.NONE,
                                    alignmentProvider = alignment?.provider ?: AlignmentProvider.NONE,
                                    alignmentProgress = alignment?.progressPercent ?: 0,
                                    isActive = isActive,
                                    isEmbeddedInCategory = isEmbedded,
                                    isReorderMode = isReorderMode,
                                    isLocalPackInstalled = audiobookState.isLocalPackInstalled,
                                    hasOwnMapping = parentCatMapping == null && ownTrackMapping != null,
                                    rowModifier = liftModifier,
                                    dragHandleModifier = buildDragModifier(row.stableKey, isCategoryRow = false),
                                    onPlay = { onPlayTrack(track.id) },
                                    onLongPressEnterReorder = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isReorderMode = true
                                    },
                                    onMap = {
                                        mappingTarget = MappingSheetTarget.TrackTarget(
                                            group = group,
                                            track = track,
                                            parentCategoryOverride = parentCatMapping
                                        )
                                    },
                                    onAlignWords = {
                                        if (parentCatMapping == null && effectiveMapping != null) {
                                            if (audiobookState.isLocalPackInstalled) {
                                                onAlignChapterLocal(effectiveMapping.id)
                                            } else {
                                                showWordPackSheet = true
                                            }
                                        }
                                    },
                                    onReorder = { isReorderMode = true },
                                    onRename = {
                                        renameTarget = RenameSheetTarget.Track(track)
                                        renameText = track.title
                                    },
                                    onDelete = { onDeleteTrack(track) }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(
                                        start = if (isEmbedded) 36.dp else 20.dp,
                                        end = 20.dp
                                    ),
                                    color = palette.borderSubtle.copy(alpha = if (isEmbedded) 0.4f else 0.55f)
                                )
                            }

                            is AudiobookListRow.CategoryHeader -> {
                                val catMapping = groupMappingsByGroupId[row.group.id]
                                val catAlignment = catMapping?.let { audiobookState.alignments[it.id] }
                                val containsActiveTrack = audiobookState.activeGroupId == row.group.id

                                CleanCategoryHeaderRow(
                                    group = row.group,
                                    trackCount = row.trackCount,
                                    totalDurationMs = row.totalDurationMs,
                                    categoryMappingLabel = catMapping?.let { buildMappingStatusLabel(it, isPdfBook) },
                                    alignmentStatus = catAlignment?.status ?: AlignmentStatus.NONE,
                                    alignmentProgress = catAlignment?.progressPercent ?: 0,
                                    showActiveCheckmark = row.group.isCollapsed && containsActiveTrack,
                                    isReorderMode = isReorderMode,
                                    isLocalPackInstalled = audiobookState.isLocalPackInstalled,
                                    hasCategoryMapping = catMapping != null,
                                    rowModifier = liftModifier,
                                    dragHandleModifier = buildDragModifier(row.stableKey, isCategoryRow = true),
                                    onToggleCollapse = {
                                        onToggleGroupCollapsed(row.group.id, !row.group.isCollapsed)
                                    },
                                    onMapCategory = {
                                        mappingTarget = MappingSheetTarget.CategoryTarget(
                                            group = row.group,
                                            trackCount = row.trackCount
                                        )
                                    },
                                    onAlignCategory = {
                                        if (catMapping != null) {
                                            if (audiobookState.isLocalPackInstalled) {
                                                onAlignChapterLocal(catMapping.id)
                                            } else {
                                                showWordPackSheet = true
                                            }
                                        }
                                    },
                                    onImportIntoCategory = { onImportAudioFiles(row.group.id) },
                                    onReorder = { isReorderMode = true },
                                    onRename = {
                                        renameTarget = RenameSheetTarget.Category(row.group)
                                        renameText = row.group.title
                                    },
                                    onDelete = {
                                        if (row.trackCount > 0) {
                                            deleteConfirmCategory = row.group to row.trackCount
                                        } else {
                                            onDeleteGroup(row.group.id)
                                        }
                                    }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = palette.borderSubtle.copy(alpha = 0.55f)
                                )
                            }

                            is AudiobookListRow.EmptyCategoryPlaceholder -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isReorderMode) {
                                            onImportAudioFiles(row.group.id)
                                        }
                                        .padding(start = 36.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isReorderMode) {
                                            "Drop chapters here to place inside \"${row.group.title}\""
                                        } else {
                                            "No chapters in this category · Tap to import audio"
                                        },
                                        fontSize = 12.5.sp,
                                        color = if (isReorderMode) palette.audioAccent else palette.textMuted
                                    )
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = palette.borderSubtle.copy(alpha = 0.45f)
                                )
                            }

                            is AudiobookListRow.CategoryEndMarker -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = palette.audioAccent.copy(alpha = 0.28f)
                                    )
                                    Text(
                                        text = "End of ${row.group.title}",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = palette.textMuted
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = palette.audioAccent.copy(alpha = 0.28f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // SECONDARY SHEET: Word Highlighting Pack (Download Pack / Import .onnx)
    // =========================================================================
    if (showWordPackSheet) {
        WordHighlightingPackSheet(
            isInstalled = audiobookState.isLocalPackInstalled,
            downloadProgress = audiobookState.localPackDownloadProgress,
            statusNotice = audiobookState.statusNotice,
            onDownloadPack = onDownloadLocalPack,
            onImportOnnx = onImportLocalPackFile,
            onDeletePack = onDeleteLocalPack,
            onDismiss = { showWordPackSheet = false }
        )
    }

    // =========================================================================
    // SECONDARY SHEET: Range-Handle Book Content Mapper (Start & End handles)
    // =========================================================================
    mappingTarget?.let { target ->
        val existingMapping = when (target) {
            is MappingSheetTarget.CategoryTarget -> groupMappingsByGroupId[target.groupId]
            is MappingSheetTarget.TrackTarget -> trackMappingsByTrackId[target.trackId]
                ?: if (!target.group.isCategory) groupMappingsByGroupId[target.groupId] else null
        }
        val parentOverrideCategoryTitle = if (target is MappingSheetTarget.TrackTarget && target.parentCategoryOverride != null) {
            target.group.title
        } else {
            null
        }
        AudiobookRangeMapSheet(
            targetTitle = target.displayTitle,
            isCategoryTarget = target is MappingSheetTarget.CategoryTarget,
            parentOverrideCategoryTitle = parentOverrideCategoryTitle,
            existingMapping = existingMapping,
            bookChapters = bookChapters,
            isPdfBook = isPdfBook,
            totalPdfPages = totalPdfPages,
            currentBookPageIndex = currentBookPageIndex,
            currentBookCharOffset = currentBookCharOffset,
            onSave = { title, startP, endP, startC, endC ->
                onSaveMapping(target.groupId, target.trackId, title, startP, endP, startC, endC)
                mappingTarget = null
            },
            onRemoveMapping = {
                if (existingMapping != null) onRemoveMapping(existingMapping.id)
                mappingTarget = null
            },
            onClearAnchors = {
                if (existingMapping != null) onClearAnchors(existingMapping.id)
            },
            onDismiss = { mappingTarget = null }
        )
    }

    // Create Category Dialog
    if (showCreateGroupDialog) {
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            containerColor = palette.menuSurface,
            title = { Text("Add Category", fontWeight = FontWeight.SemiBold) },
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
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGroupDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename Dialog (Chapter or Category)
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = palette.menuSurface,
            title = { Text(target.label, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    when (target) {
                        is RenameSheetTarget.Category -> onRenameGroup(target.group.id, renameText)
                        is RenameSheetTarget.Track -> onRenameTrack(target.track.id, renameText)
                    }
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Delete Non-Empty Category Confirmation
    deleteConfirmCategory?.let { (grp, count) ->
        AlertDialog(
            onDismissRequest = { deleteConfirmCategory = null },
            containerColor = palette.menuSurface,
            title = { Text("Delete \"${grp.title}\"?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    text = "This will remove the category and its $count embedded audio chapter${if (count == 1) "" else "s"} from this book.",
                    fontSize = 13.5.sp,
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(grp.id)
                        deleteConfirmCategory = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmCategory = null }) { Text("Cancel") }
            }
        )
    }
}

private fun buildMappingStatusLabel(mapping: AudiobookChapterMapping, isPdfBook: Boolean): String {
    val anchorTag = if (mapping.anchors.isNotEmpty()) " · ${mapping.anchors.size} anchor" else ""
    return if (isPdfBook) {
        val range = "p.${mapping.bookStartPageIndex + 1}–${mapping.bookEndPageIndex + 1}"
        val ch = mapping.bookChapterTitle.takeIf { it.isNotBlank() && it != "Pages ${mapping.bookStartPageIndex + 1}–${mapping.bookEndPageIndex + 1}" }
        (if (ch != null) "$ch ($range)" else range) + anchorTag
    } else {
        mapping.bookChapterTitle.ifBlank { "Mapped range" } + anchorTag
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CleanChapterTrackRow(
    displayTitle: String,
    startOffsetMs: Long,
    durationMs: Long,
    mappingLabel: String?,
    overriddenByCategoryTitle: String?,
    alignmentStatus: AlignmentStatus,
    alignmentProvider: AlignmentProvider,
    alignmentProgress: Int,
    isActive: Boolean,
    isEmbeddedInCategory: Boolean,
    isReorderMode: Boolean,
    isLocalPackInstalled: Boolean,
    hasOwnMapping: Boolean,
    rowModifier: Modifier,
    dragHandleModifier: Modifier,
    onPlay: () -> Unit,
    onLongPressEnterReorder: () -> Unit,
    onMap: () -> Unit,
    onAlignWords: () -> Unit,
    onReorder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    var showRowMenu by remember { mutableStateOf(false) }

    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .then(
                if (!isReorderMode) {
                    Modifier.combinedClickable(
                        onClick = onPlay,
                        onLongClick = onLongPressEnterReorder
                    )
                } else {
                    Modifier
                }
            )
            .padding(
                start = if (isEmbeddedInCategory) 28.dp else 20.dp,
                end = 12.dp,
                top = 13.dp,
                bottom = 13.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isEmbeddedInCategory) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) palette.audioAccent else palette.borderSubtle.copy(alpha = 0.85f)
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = displayTitle,
                fontSize = 15.5.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "Start: ${formatChapterTimestamp(startOffsetMs)} - Duration: ${formatChapterTimestamp(durationMs)}",
                fontSize = 12.5.sp,
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFeatureSettings = "tnum")
            )

            when {
                overriddenByCategoryTitle != null -> {
                    Text(
                        text = "Synced via \"$overriddenByCategoryTitle\"",
                        fontSize = 11.5.sp,
                        color = palette.textMuted.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                mappingLabel != null -> {
                    val statusSuffix = when (alignmentStatus) {
                        AlignmentStatus.RUNNING, AlignmentStatus.QUEUED -> " · Aligning $alignmentProgress%"
                        AlignmentStatus.READY -> if (alignmentProvider == AlignmentProvider.CLOUD_PRO) " · Cloud Synced" else " · Word Synced"
                        AlignmentStatus.NEEDS_CLOUD_RECOMMENDED -> " · Edition Mismatch"
                        else -> ""
                    }
                    val labelColor = when (alignmentStatus) {
                        AlignmentStatus.READY -> Color(0xFF22C55E)
                        AlignmentStatus.NEEDS_CLOUD_RECOMMENDED -> Color(0xFFF59E0B)
                        else -> palette.audioAccent
                    }
                    Text(
                        text = "$mappingLabel$statusSuffix",
                        fontSize = 11.5.sp,
                        color = labelColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Active checkmark (reference screenshot style)
        if (isActive && !isReorderMode) {
            Box(
                modifier = Modifier.padding(end = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                CheckmarkIcon(color = palette.audioAccent, size = 18.dp)
            }
        }

        if (isReorderMode) {
            Box(
                modifier = dragHandleModifier
                    .size(42.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                DragHandleIcon(color = palette.textSecondary, size = 18.dp)
            }
        } else {
            Box {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { showRowMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    MoreDotsIcon(
                        color = palette.textMuted,
                        vertical = true,
                        size = 16.dp
                    )
                }

                DropdownMenu(
                    expanded = showRowMenu,
                    onDismissRequest = { showRowMenu = false },
                    containerColor = palette.menuSurface
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (hasOwnMapping) "Edit Mapping" else "Map to Content",
                                fontSize = 14.sp,
                                color = palette.textPrimary
                            )
                        },
                        onClick = {
                            showRowMenu = false
                            onMap()
                        }
                    )

                    if (hasOwnMapping && overriddenByCategoryTitle == null &&
                        alignmentStatus != AlignmentStatus.RUNNING &&
                        alignmentStatus != AlignmentStatus.QUEUED
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isLocalPackInstalled) {
                                        if (alignmentStatus == AlignmentStatus.READY) "Re-align Words" else "Align Words"
                                    } else {
                                        "Get Word Sync Pack"
                                    },
                                    fontSize = 14.sp,
                                    color = palette.audioAccent
                                )
                            },
                            onClick = {
                                showRowMenu = false
                                onAlignWords()
                            }
                        )
                    }

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Reorder / Move",
                                fontSize = 14.sp,
                                color = palette.textPrimary
                            )
                        },
                        onClick = {
                            showRowMenu = false
                            onReorder()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Rename",
                                fontSize = 14.sp,
                                color = palette.textPrimary
                            )
                        },
                        onClick = {
                            showRowMenu = false
                            onRename()
                        }
                    )

                    HorizontalDivider(color = palette.borderSubtle.copy(alpha = 0.6f))

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Delete",
                                fontSize = 14.sp,
                                color = Color(0xFFEF4444)
                            )
                        },
                        onClick = {
                            showRowMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanCategoryHeaderRow(
    group: AudiobookGroup,
    trackCount: Int,
    totalDurationMs: Long,
    categoryMappingLabel: String?,
    alignmentStatus: AlignmentStatus,
    alignmentProgress: Int,
    showActiveCheckmark: Boolean,
    isReorderMode: Boolean,
    isLocalPackInstalled: Boolean,
    hasCategoryMapping: Boolean,
    rowModifier: Modifier,
    dragHandleModifier: Modifier,
    onToggleCollapse: () -> Unit,
    onMapCategory: () -> Unit,
    onAlignCategory: () -> Unit,
    onImportIntoCategory: () -> Unit,
    onReorder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = LocalBookFreePalette.current
    var showCategoryMenu by remember { mutableStateOf(false) }

    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .background(palette.surfaceColor.copy(alpha = 0.48f))
            .clickable(onClick = onToggleCollapse)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ChevronIcon(
                color = palette.textSecondary,
                expanded = !group.isCollapsed,
                size = 15.dp
            )
        }
        Spacer(modifier = Modifier.width(6.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = group.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$trackCount ${if (trackCount == 1) "chapter" else "chapters"} · Duration: ${formatChapterTimestamp(totalDurationMs)}",
                fontSize = 12.sp,
                color = palette.textMuted,
                maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            if (categoryMappingLabel != null) {
                val alignSuffix = when (alignmentStatus) {
                    AlignmentStatus.RUNNING, AlignmentStatus.QUEUED -> " · Aligning $alignmentProgress%"
                    AlignmentStatus.READY -> " · Word Synced"
                    AlignmentStatus.NEEDS_CLOUD_RECOMMENDED -> " · Edition Mismatch"
                    else -> " · Overrides tracks"
                }
                Text(
                    text = "$categoryMappingLabel$alignSuffix",
                    fontSize = 11.5.sp,
                    color = if (alignmentStatus == AlignmentStatus.READY) Color(0xFF22C55E) else palette.audioAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showActiveCheckmark && !isReorderMode) {
            Box(modifier = Modifier.padding(end = 4.dp)) {
                CheckmarkIcon(color = palette.audioAccent, size = 18.dp)
            }
        }

        if (isReorderMode) {
            Box(
                modifier = dragHandleModifier
                    .size(42.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                DragHandleIcon(color = palette.textPrimary, size = 18.dp)
            }
        } else {
            Box {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { showCategoryMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    MoreDotsIcon(
                        color = palette.textSecondary,
                        vertical = true,
                        size = 16.dp
                    )
                }

                DropdownMenu(
                    expanded = showCategoryMenu,
                    onDismissRequest = { showCategoryMenu = false },
                    containerColor = palette.menuSurface
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = if (hasCategoryMapping) "Edit Category Mapping" else "Map Category to Content",
                                    fontSize = 14.sp,
                                    color = palette.textPrimary
                                )
                                Text(
                                    text = "Overrides individual files inside",
                                    fontSize = 11.sp,
                                    color = palette.textMuted
                                )
                            }
                        },
                        onClick = {
                            showCategoryMenu = false
                            onMapCategory()
                        }
                    )

                    if (hasCategoryMapping &&
                        alignmentStatus != AlignmentStatus.RUNNING &&
                        alignmentStatus != AlignmentStatus.QUEUED
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isLocalPackInstalled) "Align Category Words" else "Get Word Sync Pack",
                                    fontSize = 14.sp,
                                    color = palette.audioAccent
                                )
                            },
                            onClick = {
                                showCategoryMenu = false
                                onAlignCategory()
                            }
                        )
                    }

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Import Audio Here",
                                fontSize = 14.sp,
                                color = palette.textPrimary
                            )
                        },
                        onClick = {
                            showCategoryMenu = false
                            onImportIntoCategory()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Reorder / Move",
                                fontSize = 14.sp,
                                color = palette.textPrimary
                            )
                        },
                        onClick = {
                            showCategoryMenu = false
                            onReorder()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Rename Category",
                                fontSize = 14.sp,
                                color = palette.textPrimary
                            )
                        },
                        onClick = {
                            showCategoryMenu = false
                            onRename()
                        }
                    )

                    HorizontalDivider(color = palette.borderSubtle.copy(alpha = 0.6f))

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Delete Category",
                                fontSize = 14.sp,
                                color = Color(0xFFEF4444)
                            )
                        },
                        onClick = {
                            showCategoryMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Dedicated secondary sheet for the optional Local MMS-300M-FA ONNX Word Highlighting Pack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordHighlightingPackSheet(
    isInstalled: Boolean,
    downloadProgress: Int?,
    statusNotice: String?,
    onDownloadPack: () -> Unit,
    onImportOnnx: () -> Unit,
    onDeletePack: () -> Unit,
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
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Word Highlighting Pack",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Free · Offline Local ONNX (MMS-300M-FA)",
                        fontSize = 12.5.sp,
                        color = palette.textMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isInstalled) Color(0xFF22C55E).copy(alpha = 0.16f)
                            else palette.surfaceColor
                        )
                        .border(
                            1.dp,
                            if (isInstalled) Color(0xFF22C55E).copy(alpha = 0.45f) else palette.borderSubtle,
                            CircleShape
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = when {
                            downloadProgress != null -> "$downloadProgress%"
                            isInstalled -> "Installed"
                            else -> "Optional"
                        },
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            downloadProgress != null -> palette.audioAccent
                            isInstalled -> Color(0xFF22C55E)
                            else -> palette.textSecondary
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surfaceColor)
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (downloadProgress != null) {
                        LinearProgressIndicator(
                            progress = { (downloadProgress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = palette.audioAccent
                        )
                        Text(
                            text = "Downloading Meta MMS-300M-FA pack ($downloadProgress%)...",
                            fontSize = 12.5.sp,
                            color = palette.textSecondary
                        )
                    } else if (isInstalled) {
                        Text(
                            text = "Ready for offline word-level highlighting across Arabic, English, and 1,000+ languages.",
                            fontSize = 13.sp,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Mapped chapters align automatically in the background when played, or manually via each chapter's menu.",
                            fontSize = 12.sp,
                            color = palette.textMuted
                        )
                    } else {
                        Text(
                            text = "Instant page sync already works right now with 0 MB download.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Download Meta MMS-300M-FA (~95 MB) on demand—or import a local .onnx model file—to enable live word-by-word highlighting during audiobook playback.",
                            fontSize = 12.5.sp,
                            color = palette.textSecondary
                        )
                    }

                    if (!statusNotice.isNullOrBlank() && downloadProgress == null) {
                        Text(
                            text = statusNotice,
                            fontSize = 11.5.sp,
                            color = palette.textMuted
                        )
                    }
                }
            }

            if (downloadProgress == null) {
                if (isInstalled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onImportOnnx,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Replace .onnx", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = onDeletePack,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFEF4444)
                            )
                        ) {
                            Text("Remove Pack", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onDownloadPack,
                            modifier = Modifier.weight(1.2f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.audioAccent,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Download Pack", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onImportOnnx,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Import .onnx", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

private enum class RangeHandleFocus {
    AUTO,
    START,
    END
}

/**
 * Interactive Table-of-Contents Range Mapper Sheet with draggable Start & End handles.
 * Allows mapping a single audio file or a Category across one or multiple contiguous book chapters
 * (e.g., "Chapters 1 – 4"), with manual page-range fallback for PDFs or TOC-less books.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudiobookRangeMapSheet(
    targetTitle: String,
    isCategoryTarget: Boolean,
    parentOverrideCategoryTitle: String?,
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
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val hasToc = bookChapters.isNotEmpty()
    var useManualPages by remember(hasToc, isPdfBook) { mutableStateOf(!hasToc && isPdfBook) }

    val initialPair = remember(bookChapters, existingMapping, isPdfBook, currentBookPageIndex, currentBookCharOffset) {
        initialRangeForMapping(
            chapters = bookChapters,
            existingMapping = existingMapping,
            isPdfBook = isPdfBook,
            currentBookPageIndex = currentBookPageIndex,
            currentBookCharOffset = currentBookCharOffset
        )
    }
    var startIdx by remember(initialPair) { mutableIntStateOf(initialPair.first) }
    var endIdx by remember(initialPair) { mutableIntStateOf(initialPair.second) }
    var handleFocus by remember { mutableStateOf(RangeHandleFocus.AUTO) }
    var isDraggingHandle by remember { mutableStateOf(false) }

    var startPageText by remember(existingMapping, currentBookPageIndex) {
        mutableStateOf(((existingMapping?.bookStartPageIndex ?: currentBookPageIndex) + 1).toString())
    }
    var endPageText by remember(existingMapping, currentBookPageIndex, totalPdfPages) {
        mutableStateOf(
            ((existingMapping?.bookEndPageIndex
                ?: (currentBookPageIndex + 10).coerceAtMost((totalPdfPages - 1).coerceAtLeast(0))) + 1).toString()
        )
    }

    val latestIsDraggingHandle by rememberUpdatedState(isDraggingHandle)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (latestIsDraggingHandle && target == SheetValue.Hidden) false else true
        }
    )

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (initialPair.first - 1).coerceAtLeast(0)
    )
    val defaultStepPx = with(density) { 48.dp.toPx() }

    val resolvedRange = remember(bookChapters, startIdx, endIdx) {
        if (bookChapters.isNotEmpty()) resolveChapterRange(bookChapters, startIdx, endIdx) else null
    }
    val totalBookChars = remember(bookChapters) {
        bookChapters.maxOfOrNull { it.endCharOffset }?.coerceAtLeast(1) ?: 1
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
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isCategoryTarget) "Map Category to Content" else "Map to Content",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary
                    )
                    Text(
                        text = targetTitle,
                        fontSize = 12.5.sp,
                        color = palette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (existingMapping != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (existingMapping.anchors.isNotEmpty()) {
                            TextButton(
                                onClick = onClearAnchors,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Clear Anchors", fontSize = 12.sp)
                            }
                        }
                        TextButton(
                            onClick = onRemoveMapping,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Unmap", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFEF4444))
                        }
                    }
                }
            }

            // Precedence context notice (Category overrides individual tracks)
            if (isCategoryTarget) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.audioAccent.copy(alpha = 0.10f))
                        .border(1.dp, palette.audioAccent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Mapping this category applies across all audio files inside it and overrides their individual mappings.",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                }
            } else if (parentOverrideCategoryTitle != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.12f))
                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Category \"$parentOverrideCategoryTitle\" is currently mapped and overrides this track. Your individual mapping here will take effect if you unmap the category.",
                        fontSize = 12.sp,
                        color = palette.textSecondary
                    )
                }
            }

            // Optional PDF toggle: Contents vs Manual Pages
            if (isPdfBook && hasToc) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.surfaceColor)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(false to "Book Contents", true to "Manual Pages").forEach { (manual, label) ->
                        val active = useManualPages == manual
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) palette.menuSurface else Color.Transparent)
                                .clickable { useManualPages = manual }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.5.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) palette.textPrimary else palette.textMuted
                            )
                        }
                    }
                }
            }

            if (useManualPages && isPdfBook) {
                // Manual PDF page range
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Page Range (1–$totalPdfPages)",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    TextButton(
                        onClick = { startPageText = (currentBookPageIndex + 1).toString() },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Set Start to Current Page (p.${currentBookPageIndex + 1})",
                            fontSize = 12.5.sp,
                            color = palette.audioAccent
                        )
                    }
                    Button(
                        onClick = {
                            val sp = ((startPageText.toIntOrNull() ?: 1) - 1)
                                .coerceIn(0, (totalPdfPages - 1).coerceAtLeast(0))
                            val ep = ((endPageText.toIntOrNull() ?: (sp + 1)) - 1)
                                .coerceIn(sp, (totalPdfPages - 1).coerceAtLeast(0))
                            onSave("Pages ${sp + 1}–${ep + 1}", sp, ep, 0, 0)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.audioAccent,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Save Page Range", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (!hasToc) {
                // EPUB without TOC fallback
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "This book has no Table of Contents entries. You can map this audio starting from your current reading position.",
                        fontSize = 13.sp,
                        color = palette.textSecondary
                    )
                    Button(
                        onClick = {
                            onSave("From current position", 0, 0, currentBookCharOffset, 0)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.audioAccent,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Map from Current Position", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                // Interactive Start/End pills + Draggable Range-Handle TOC list
                val startChapterTitle = bookChapters[startIdx.coerceIn(0, bookChapters.lastIndex)].title
                val endChapterTitle = bookChapters[endIdx.coerceIn(0, bookChapters.lastIndex)].title

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RangeEndpointPill(
                        label = "START",
                        value = startChapterTitle,
                        isFocused = handleFocus == RangeHandleFocus.START,
                        onClick = {
                            handleFocus = if (handleFocus == RangeHandleFocus.START) {
                                RangeHandleFocus.AUTO
                            } else {
                                RangeHandleFocus.START
                            }
                            coroutineScope.launch {
                                listState.animateScrollToItem((startIdx - 1).coerceAtLeast(0))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "→",
                        fontSize = 14.sp,
                        color = palette.textMuted
                    )
                    RangeEndpointPill(
                        label = "END",
                        value = endChapterTitle,
                        isFocused = handleFocus == RangeHandleFocus.END,
                        onClick = {
                            handleFocus = if (handleFocus == RangeHandleFocus.END) {
                                RangeHandleFocus.AUTO
                            } else {
                                RangeHandleFocus.END
                            }
                            coroutineScope.launch {
                                listState.animateScrollToItem((endIdx - 1).coerceAtLeast(0))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = when (handleFocus) {
                        RangeHandleFocus.START -> "Tap a chapter below to set Start, or drag the handles"
                        RangeHandleFocus.END -> "Tap a chapter below to set End, or drag the handles"
                        RangeHandleFocus.AUTO -> "Drag Start / End handles or tap chapters to set range"
                    },
                    fontSize = 11.5.sp,
                    color = palette.textMuted
                )

                // Outer Box hosts the scrollable TOC + persistent floating START & END drag handles
                // so dragging a handle continuously across many chapters NEVER cancels pointerInput!
                var isDraggingStart by remember { mutableStateOf(false) }
                var isDraggingEnd by remember { mutableStateOf(false) }
                val handlePillHeightPx = with(density) { 24.dp.toPx() }
                val listContentTopPadPx = with(density) { 14.dp.toPx() }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceColor.copy(alpha = 0.45f))
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        itemsIndexed(
                            items = bookChapters,
                            key = { idx, ch -> "${ch.id}_$idx" },
                            contentType = { _, _ -> "range_toc_row" }
                        ) { idx, chapter ->
                            val isInRange = idx in startIdx..endIdx
                            val isStartRow = idx == startIdx
                            val isEndRow = idx == endIdx
                            val indentDp = (chapter.depth.coerceIn(0, 4) * 14).dp
                            val trailingMeta = if (isPdfBook) {
                                "p.${chapter.startPageIndex + 1}"
                            } else {
                                val pct = ((chapter.startCharOffset.toFloat() / totalBookChars.toFloat()) * 100f)
                                    .roundToInt()
                                    .coerceIn(0, 100)
                                "$pct%"
                            }

                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (isStartRow) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(palette.audioAccent)
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isInRange) palette.audioAccent.copy(alpha = 0.13f) else Color.Transparent
                                        )
                                        .clickable {
                                            when (handleFocus) {
                                                RangeHandleFocus.START -> {
                                                    startIdx = idx
                                                    if (endIdx < idx) endIdx = idx
                                                    handleFocus = RangeHandleFocus.AUTO
                                                }

                                                RangeHandleFocus.END -> {
                                                    endIdx = idx
                                                    if (startIdx > idx) startIdx = idx
                                                    handleFocus = RangeHandleFocus.AUTO
                                                }

                                                RangeHandleFocus.AUTO -> {
                                                    when {
                                                        idx < startIdx -> startIdx = idx
                                                        idx > endIdx -> endIdx = idx
                                                        startIdx == endIdx -> Unit
                                                        else -> {
                                                            val distToStart = idx - startIdx
                                                            val distToEnd = endIdx - idx
                                                            if (distToStart <= distToEnd) {
                                                                startIdx = idx
                                                            } else {
                                                                endIdx = idx
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        .padding(
                                            top = if (isStartRow) 10.dp else 0.dp,
                                            bottom = if (isEndRow) 10.dp else 0.dp,
                                            end = 14.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Continuous left accent rail spanning startIdx..endIdx
                                    Box(
                                        modifier = Modifier
                                            .width(3.5.dp)
                                            .height(44.dp)
                                            .background(if (isInRange) palette.audioAccent else Color.Transparent)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp + indentDp))
                                    Text(
                                        text = chapter.title,
                                        fontSize = if (chapter.depth == 0) 14.sp else 13.sp,
                                        fontWeight = if (isInRange) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isInRange) palette.textPrimary else palette.textSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = trailingMeta,
                                        fontSize = 11.5.sp,
                                        color = if (isInRange) palette.audioAccent else palette.textMuted,
                                        style = TextStyle(fontFeatureSettings = "tnum")
                                    )
                                }

                                if (isEndRow) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .background(palette.audioAccent)
                                    )
                                } else if (idx < bookChapters.lastIndex && !isInRange) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 14.dp, end = 14.dp),
                                        color = palette.borderSubtle.copy(alpha = 0.35f)
                                    )
                                }
                            }
                        }
                    }

                    // Persistent floating START & END drag handles overlaid on the exact selection edges
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val viewportHeightPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                        .coerceAtLeast(1)
                    val maxHandleY = (viewportHeightPx - handlePillHeightPx).coerceAtLeast(0f)

                    val startItemInfo = visibleItems.firstOrNull { it.index == startIdx }
                    val showStartHandle = startItemInfo != null || isDraggingStart
                    val startHandleY = when {
                        startItemInfo != null ->
                            (listContentTopPadPx + startItemInfo.offset - handlePillHeightPx * 0.5f)
                                .coerceIn(0f, maxHandleY)
                        isDraggingStart -> 0f
                        else -> 0f
                    }

                    val endItemInfo = visibleItems.firstOrNull { it.index == endIdx }
                    val showEndHandle = endItemInfo != null || isDraggingEnd
                    val endHandleY = when {
                        endItemInfo != null ->
                            (listContentTopPadPx + endItemInfo.offset + endItemInfo.size - handlePillHeightPx * 0.5f)
                                .coerceIn(0f, maxHandleY)
                        isDraggingEnd -> maxHandleY
                        else -> maxHandleY
                    }

                    // Persistent START handle (left-aligned on top border of startIdx)
                    FloatingRangeHandlePill(
                        label = "START",
                        yOffsetPx = startHandleY,
                        visible = showStartHandle,
                        isDragging = isDraggingStart,
                        modifier = Modifier.align(Alignment.TopStart),
                        onDragActiveChanged = { active ->
                            isDraggingStart = active
                            isDraggingHandle = active || isDraggingEnd
                        },
                        onStepDelta = { stepDelta ->
                            val next = (startIdx + stepDelta).coerceIn(0, endIdx)
                            if (next != startIdx) {
                                startIdx = next
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val firstVis = listState.firstVisibleItemIndex
                                val lastVis = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                if (next <= firstVis) {
                                    coroutineScope.launch { listState.animateScrollToItem((next - 1).coerceAtLeast(0)) }
                                } else if (next >= lastVis) {
                                    coroutineScope.launch { listState.animateScrollToItem((next - 2).coerceAtLeast(0)) }
                                }
                            }
                        },
                        stepPxProvider = {
                            val infos = listState.layoutInfo.visibleItemsInfo
                            if (infos.isNotEmpty()) {
                                (infos.sumOf { it.size } / infos.size).toFloat().coerceAtLeast(defaultStepPx * 0.7f)
                            } else {
                                defaultStepPx
                            }
                        }
                    )

                    // Persistent END handle (right-aligned on bottom border of endIdx)
                    FloatingRangeHandlePill(
                        label = "END",
                        yOffsetPx = endHandleY,
                        visible = showEndHandle,
                        isDragging = isDraggingEnd,
                        modifier = Modifier.align(Alignment.TopEnd),
                        onDragActiveChanged = { active ->
                            isDraggingEnd = active
                            isDraggingHandle = active || isDraggingStart
                        },
                        onStepDelta = { stepDelta ->
                            val next = (endIdx + stepDelta).coerceIn(startIdx, bookChapters.lastIndex)
                            if (next != endIdx) {
                                endIdx = next
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val firstVis = listState.firstVisibleItemIndex
                                val lastVis = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                if (next >= lastVis) {
                                    coroutineScope.launch { listState.animateScrollToItem((next - 3).coerceAtLeast(0)) }
                                } else if (next <= firstVis) {
                                    coroutineScope.launch { listState.animateScrollToItem((next - 1).coerceAtLeast(0)) }
                                }
                            }
                        },
                        stepPxProvider = {
                            val infos = listState.layoutInfo.visibleItemsInfo
                            if (infos.isNotEmpty()) {
                                (infos.sumOf { it.size } / infos.size).toFloat().coerceAtLeast(defaultStepPx * 0.7f)
                            } else {
                                defaultStepPx
                            }
                        }
                    )
                }

                // Pinned live range summary + Save button
                if (resolvedRange != null) {
                    val rangeSub = if (isPdfBook) {
                        "Pages ${resolvedRange.startPageIndex + 1}–${resolvedRange.endPageIndex + 1} · ${resolvedRange.sectionCount} ${if (resolvedRange.sectionCount == 1) "section" else "sections"}"
                    } else {
                        val sPct = ((resolvedRange.startCharOffset.toFloat() / totalBookChars.toFloat()) * 100f)
                            .roundToInt().coerceIn(0, 100)
                        val ePct = ((resolvedRange.endCharOffset.toFloat() / totalBookChars.toFloat()) * 100f)
                            .roundToInt().coerceIn(sPct, 100)
                        "$sPct% – $ePct% of book · ${resolvedRange.sectionCount} ${if (resolvedRange.sectionCount == 1) "section" else "sections"}"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = resolvedRange.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = rangeSub,
                                fontSize = 11.5.sp,
                                color = palette.textMuted,
                                style = TextStyle(fontFeatureSettings = "tnum")
                            )
                        }

                        Button(
                            onClick = {
                                onSave(
                                    resolvedRange.title,
                                    resolvedRange.startPageIndex,
                                    resolvedRange.endPageIndex,
                                    resolvedRange.startCharOffset,
                                    resolvedRange.endCharOffset
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.audioAccent,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text("Save Mapping", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeEndpointPill(
    label: String,
    value: String,
    isFocused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBookFreePalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isFocused) palette.audioAccent.copy(alpha = 0.16f) else palette.surfaceColor
            )
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) palette.audioAccent else palette.borderSubtle,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            color = palette.audioAccent
        )
        Text(
            text = value,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FloatingRangeHandlePill(
    label: String,
    yOffsetPx: Float,
    visible: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    onDragActiveChanged: (Boolean) -> Unit,
    onStepDelta: (Int) -> Unit,
    stepPxProvider: () -> Float
) {
    val palette = LocalBookFreePalette.current
    val latestOnStepDelta by rememberUpdatedState(onStepDelta)
    val latestStepPxProvider by rememberUpdatedState(stepPxProvider)
    val latestOnDragActiveChanged by rememberUpdatedState(onDragActiveChanged)

    var accumPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .zIndex(if (isDragging) 3f else 2f)
            .graphicsLayer {
                translationY = yOffsetPx
                alpha = if (visible) 1f else 0f
                val s = if (isDragging) 1.08f else 1f
                scaleX = s
                scaleY = s
            }
            .then(
                if (visible) {
                    Modifier.pointerInput(label) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                accumPx = 0f
                                latestOnDragActiveChanged(true)
                            },
                            onDragEnd = {
                                accumPx = 0f
                                latestOnDragActiveChanged(false)
                            },
                            onDragCancel = {
                                accumPx = 0f
                                latestOnDragActiveChanged(false)
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                accumPx += dragAmount
                                val step = latestStepPxProvider().coerceAtLeast(36f)
                                while (accumPx >= step) {
                                    accumPx -= step
                                    latestOnStepDelta(1)
                                }
                                while (accumPx <= -step) {
                                    accumPx += step
                                    latestOnStepDelta(-1)
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
            // Generous hit area around the visible handle pill for comfortable thumb dragging
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(palette.audioAccent)
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .padding(horizontal = 11.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "⇅ $label",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = Color.White
            )
        }
    }
}
