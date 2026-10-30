package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.AudiobookEntity
import com.example.data.preferences.LibraryLayoutMode
import com.example.data.preferences.UserSettings
import com.example.ui.JoyBookTab
import com.example.ui.JoyBookViewModel
import com.example.ui.components.AudiobookCoverArt
import com.example.ui.components.SleepTimerModalSheet
import com.example.ui.components.StorageSearchModalSheet
import com.example.ui.screens.BookmarksScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.JoyBookTheme
import com.example.ui.theme.LocalIsAmoledTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JoyBookApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoyBookApp(viewModel: JoyBookViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val books by viewModel.filteredAudiobooks.collectAsStateWithLifecycle()
    val activeBook by viewModel.activeBook.collectAsStateWithLifecycle()
    val chapters by viewModel.activeBookChapters.collectAsStateWithLifecycle()
    val activeBookBookmarks by viewModel.activeBookBookmarks.collectAsStateWithLifecycle()
    val allBookmarks by viewModel.allBookmarks.collectAsStateWithLifecycle()
    val activeBookWaveform by viewModel.activeBookWaveform.collectAsStateWithLifecycle()
    val realtimeAudioLevel by viewModel.realtimeAudioLevel.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.currentPositionMs.collectAsStateWithLifecycle()
    val voiceBoostEnabled by viewModel.voiceBoostEnabled.collectAsStateWithLifecycle()
    val selectedFormat by viewModel.selectedFormatFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sleepRemainingMs by viewModel.sleepRemainingMs.collectAsStateWithLifecycle()
    val stopAtEndOfChapter by viewModel.stopAtEndOfChapter.collectAsStateWithLifecycle()
    val smartInactivityEnabled by viewModel.smartInactivityEnabled.collectAsStateWithLifecycle()
    val inactivityTimeoutMinutes by viewModel.inactivityTimeoutMinutes.collectAsStateWithLifecycle()
    val isIgnoringBatteryOptimizations by viewModel.isIgnoringBatteryOptimizations.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    // Storage Scanner State
    val isScanningStorage by viewModel.isScanningStorage.collectAsStateWithLifecycle()
    val isImportingScanned by viewModel.isImportingScanned.collectAsStateWithLifecycle()
    val scannedFiles by viewModel.filteredScannedFiles.collectAsStateWithLifecycle()
    val storageSearchQuery by viewModel.storageSearchQuery.collectAsStateWithLifecycle()
    val storageFormatFilter by viewModel.storageFormatFilter.collectAsStateWithLifecycle()
    val storageMinDurationMs by viewModel.storageMinDurationMs.collectAsStateWithLifecycle()
    val selectedStorageFolder by viewModel.selectedStorageFolder.collectAsStateWithLifecycle()
    val availableStorageFolders by viewModel.availableStorageFolders.collectAsStateWithLifecycle()
    val selectedScannedUris by viewModel.selectedScannedUris.collectAsStateWithLifecycle()
    val selectedGroupIds by viewModel.selectedGroupIds.collectAsStateWithLifecycle()
    val scannedBookGroups by viewModel.filteredScannedBookGroups.collectAsStateWithLifecycle()
    val selectedFolderName by viewModel.selectedFolderName.collectAsStateWithLifecycle()
    val selectedFolderUri by viewModel.selectedFolderUri.collectAsStateWithLifecycle()

    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showStorageSearchSheet by remember { mutableStateOf(false) }
    val storageSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    val context = androidx.compose.ui.platform.LocalContext.current

    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshBatteryOptimizationState()
    }

    val requestIgnoreBatteryOptimizations: () -> Unit = {
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            batteryOptimizationLauncher.launch(intent)
        }.onFailure {
            runCatching {
                val fallbackIntent = android.content.Intent(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                )
                batteryOptimizationLauncher.launch(fallbackIntent)
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* MediaSessionService handles notification gracefully whether granted or denied */ }

    val ensureNotificationPermissionAndRun: (() -> Unit) -> Unit = { action ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        action()
    }

    // Storage scanning permission & launchers
    val audioStoragePermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_AUDIO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val requestStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.scanDeviceInternalStorage()
        }
    }

    val ensureStoragePermissionAndScan: () -> Unit = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            audioStoragePermission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.scanDeviceInternalStorage()
        } else {
            requestStoragePermissionLauncher.launch(audioStoragePermission)
        }
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.scanFolderTree(treeUri)
        }
    }

    val openAudioDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importAudioUri(uri)
        }
    }

    val openMultipleAudioDocumentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            viewModel.importMultipleAudioUris(uris)
        }
    }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    JoyBookTheme(
        themeMode = settings.themeMode,
        accentPalette = settings.accentPalette,
        fontScale = settings.fontScale
    ) {
        val isAmoled = LocalIsAmoledTheme.current

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    // Mini Player Bar visible when not on Player tab
                    AnimatedVisibility(visible = selectedTab != JoyBookTab.PLAYER && activeBook != null) {
                        activeBook?.let { current ->
                            MiniPlayerBar(
                                book = current,
                                isPlaying = isPlaying,
                                currentPositionMs = currentPositionMs,
                                settings = settings,
                                isAmoled = isAmoled,
                                onOpenPlayer = { viewModel.selectTab(JoyBookTab.PLAYER) },
                                onTogglePlay = { viewModel.togglePlayPause() },
                                onRewind = { viewModel.rewindByConfiguredStep() },
                                onForward = { viewModel.forwardByConfiguredStep() }
                            )
                        }
                    }

                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.testTag("bottom_navigation_bar")
                    ) {
                        JoyBookTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                icon = {
                                    val icon = when (tab) {
                                        JoyBookTab.LIBRARY -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
                                        JoyBookTab.PLAYER -> if (selected) Icons.Filled.Headphones else Icons.Outlined.Headphones
                                        JoyBookTab.BOOKMARKS -> if (selected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
                                        JoyBookTab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
                                    }
                                    Icon(icon, contentDescription = tab.title)
                                },
                                label = { Text(tab.title) },
                                modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    JoyBookTab.LIBRARY -> {
                        LibraryScreen(
                            books = books,
                            activeBook = activeBook,
                            isPlaying = isPlaying,
                            currentPositionMs = currentPositionMs,
                            selectedFormat = selectedFormat,
                            searchQuery = searchQuery,
                            sleepRemainingMs = sleepRemainingMs,
                            stopAtEndOfChapter = stopAtEndOfChapter,
                            settings = settings,
                            onSelectFormat = viewModel::setFormatFilter,
                            onSearchQueryChange = viewModel::setSearchQuery,
                            onCycleLayoutMode = {
                                val nextMode = when (settings.libraryLayoutMode) {
                                    LibraryLayoutMode.DETAILED_LIST -> LibraryLayoutMode.GRID
                                    LibraryLayoutMode.GRID -> LibraryLayoutMode.COMPACT_LIST
                                    LibraryLayoutMode.COMPACT_LIST -> LibraryLayoutMode.DETAILED_LIST
                                }
                                viewModel.setLibraryLayoutMode(nextMode)
                            },
                            onSelectBook = { book ->
                                ensureNotificationPermissionAndRun {
                                    viewModel.selectAndPlayBook(book, openPlayerScreen = true)
                                }
                            },
                            onTogglePlayActive = {
                                ensureNotificationPermissionAndRun {
                                    viewModel.togglePlayPause()
                                }
                            },
                            onToggleFavorite = viewModel::toggleFavorite,
                            onOpenSleepTimer = { showSleepTimerSheet = true },
                            onOpenStorageSearch = {
                                showStorageSearchSheet = true
                            },
                            onClearLibrary = viewModel::clearLibrary,
                            onImportAudioClick = {
                                openAudioDocumentLauncher.launch(
                                    arrayOf(
                                        "audio/mpeg",
                                        "audio/mp4",
                                        "audio/x-m4a",
                                        "audio/m4b",
                                        "audio/amr",
                                        "audio/AMR",
                                        "audio/wav",
                                        "audio/x-wav",
                                        "audio/*"
                                    )
                                )
                            }
                        )
                    }

                    JoyBookTab.PLAYER -> {
                        PlayerScreen(
                            book = activeBook,
                            chapters = chapters,
                            bookmarks = activeBookBookmarks,
                            waveformAmplitudes = activeBookWaveform,
                            realtimeAudioLevel = realtimeAudioLevel,
                            isPlaying = isPlaying,
                            currentPositionMs = currentPositionMs,
                            voiceBoostEnabled = voiceBoostEnabled,
                            sleepRemainingMs = sleepRemainingMs,
                            stopAtEndOfChapter = stopAtEndOfChapter,
                            settings = settings,
                            onBackToLibrary = { viewModel.selectTab(JoyBookTab.LIBRARY) },
                            onPlayPause = {
                                ensureNotificationPermissionAndRun {
                                    viewModel.togglePlayPause()
                                }
                            },
                            onSeekTo = viewModel::seekTo,
                            onRewind = viewModel::rewindByConfiguredStep,
                            onForward = viewModel::forwardByConfiguredStep,
                            onPreviousChapter = viewModel::skipToPreviousChapter,
                            onNextChapter = viewModel::skipToNextChapter,
                            onSpeedChange = viewModel::setPlaybackSpeed,
                            onToggleVoiceBoost = viewModel::toggleVoiceBoost,
                            onAddBookmark = viewModel::addBookmarkForActiveBook,
                            onOpenSleepSheet = { showSleepTimerSheet = true },
                            onOpenSettings = { viewModel.selectTab(JoyBookTab.SETTINGS) },
                            onAuthorClick = viewModel::openAuthorBooksInLibrary,
                            onUserInteraction = viewModel::recordUserInteraction
                        )
                    }

                    JoyBookTab.BOOKMARKS -> {
                        BookmarksScreen(
                            bookmarks = allBookmarks,
                            onBackToLibrary = { viewModel.selectTab(JoyBookTab.LIBRARY) },
                            onJumpToBookmark = viewModel::jumpToBookmark,
                            onDeleteBookmark = viewModel::deleteBookmark
                        )
                    }

                    JoyBookTab.SETTINGS -> {
                        SettingsScreen(
                            settings = settings,
                            libraryBookCount = books.size,
                            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                            onRequestIgnoreBatteryOptimizations = requestIgnoreBatteryOptimizations,
                            onBackToLibrary = { viewModel.selectTab(JoyBookTab.LIBRARY) },
                            onThemeModeChange = viewModel::setThemeMode,
                            onAccentPaletteChange = viewModel::setAccentPalette,
                            onRewindSecondsChange = viewModel::setRewindSeconds,
                            onForwardSecondsChange = viewModel::setForwardSeconds,
                            onButtonSizeChange = viewModel::setButtonSize,
                            onButtonShapeChange = viewModel::setButtonShape,
                            onShowChapterSkipChange = viewModel::setShowChapterSkip,
                            onSwapSeekButtonsChange = viewModel::setSwapSeekButtons,
                            onShowSpeedButtonChange = viewModel::setShowSpeedButton,
                            onShowSleepButtonChange = viewModel::setShowSleepButton,
                            onShowBookmarkButtonChange = viewModel::setShowBookmarkButton,
                            onShowVoiceBoostButtonChange = viewModel::setShowVoiceBoostButton,
                            onCoverFrameStyleChange = viewModel::setCoverFrameStyle,
                            onCoverBlurGlowChange = viewModel::setCoverBlurGlow,
                            onProgressBarStyleChange = viewModel::setProgressBarStyle,
                            onLibraryLayoutModeChange = viewModel::setLibraryLayoutMode,
                            onFontScaleChange = viewModel::setFontScale,
                            onShowFormatBadgesChange = viewModel::setShowFormatBadges,
                            onShowChapterRemainingChange = viewModel::setShowChapterRemaining,
                            onShakeToResumeSleepChange = viewModel::setShakeToResumeSleep,
                            onShakeSensitivityChange = viewModel::setShakeSensitivity,
                            onClearLibrary = viewModel::clearLibrary,
                            onResetDefaults = viewModel::resetSettingsToDefaults
                        )
                    }
                }
            }
        }

        if (showSleepTimerSheet) {
            SleepTimerModalSheet(
                remainingSleepMs = sleepRemainingMs,
                stopAtEndOfChapter = stopAtEndOfChapter,
                smartInactivityEnabled = smartInactivityEnabled,
                inactivityTimeoutMinutes = inactivityTimeoutMinutes,
                sleepFadeOutEnabled = settings.sleepTimerFadeOut,
                shakeToResumeEnabled = settings.shakeToResumeSleepEnabled,
                shakeSensitivity = settings.shakeSensitivity,
                onStartTimerMinutes = viewModel::startSleepTimerMinutes,
                onStartEndOfChapter = viewModel::startSleepAtEndOfChapter,
                onToggleSmartInactivity = viewModel::setSmartInactivity,
                onToggleStopAtEndOfChapter = viewModel::toggleStopAtEndOfChapter,
                onExtendFiveMinutes = viewModel::extendSleepTimerFiveMinutes,
                onCancelTimer = viewModel::cancelSleepTimer,
                onToggleFadeOut = viewModel::setSleepTimerFadeOut,
                onToggleShakeToResume = viewModel::setShakeToResumeSleep,
                onShakeSensitivityChange = viewModel::setShakeSensitivity,
                onDismiss = { showSleepTimerSheet = false }
            )
        }

        if (showStorageSearchSheet) {
            StorageSearchModalSheet(
                sheetState = storageSheetState,
                isScanning = isScanningStorage,
                isImporting = isImportingScanned,
                scannedBookGroups = scannedBookGroups,
                selectedGroupIds = selectedGroupIds,
                selectedFolderName = selectedFolderName,
                searchQuery = storageSearchQuery,
                selectedFormat = storageFormatFilter,
                selectedMinDurationMs = storageMinDurationMs,
                selectedFolder = selectedStorageFolder,
                availableFolders = availableStorageFolders,
                onDismissRequest = { showStorageSearchSheet = false },
                onSearchQueryChange = viewModel::setStorageSearchQuery,
                onFormatChange = viewModel::setStorageFormatFilter,
                onMinDurationChange = viewModel::setStorageMinDurationMs,
                onFolderChange = viewModel::setStorageFolderFilter,
                onToggleSelectGroup = viewModel::toggleScannedGroupSelection,
                onSelectAllGroups = viewModel::selectAllScannedGroups,
                onClearGroupSelection = viewModel::clearScannedGroupsSelection,
                onPickFolderClick = { pickFolderLauncher.launch(null) },
                onClearFolder = viewModel::clearSelectedFolder,
                onRescanFolder = { selectedFolderUri?.let { viewModel.scanFolderTree(it) } },
                onPickFilesClick = {
                    openMultipleAudioDocumentsLauncher.launch(
                        arrayOf(
                            "audio/mpeg",
                            "audio/mp4",
                            "audio/x-m4a",
                            "audio/m4b",
                            "audio/amr",
                            "audio/wav",
                            "audio/*"
                        )
                    )
                },
                onImportGroup = viewModel::importScannedGroup,
                onImportSelectedGroups = {
                    viewModel.importSelectedScannedGroups { count ->
                        if (count > 0) {
                            showStorageSearchSheet = false
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun MiniPlayerBar(
    book: AudiobookEntity,
    isPlaying: Boolean,
    currentPositionMs: Long,
    settings: UserSettings,
    isAmoled: Boolean,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit
) {
    val progress = if (book.durationMs > 0) {
        (currentPositionMs.toFloat() / book.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        onClick = onOpenPlayer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mini_player_bar")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val miniCoverWidth = if (settings.coverFrameStyle == com.example.data.preferences.CoverFrameStyle.CD_CASE) 50.dp else 44.dp
                AudiobookCoverArt(
                    coverArtStyle = book.coverArtStyle,
                    audioFormat = "",
                    frameStyle = settings.coverFrameStyle,
                    isPlaying = isPlaying,
                    showFormatBadge = false,
                    coverImagePath = book.coverImagePath,
                    modifier = Modifier.size(width = miniCoverWidth, height = 44.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val displayAuthor = com.example.data.storage.BookTitleCleaner.cleanAuthorName(book.author)
                    val displayTitle = com.example.data.storage.BookTitleCleaner.cleanBookTitle(book.title, displayAuthor).ifBlank { book.title }
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$displayAuthor • ${book.audioFormat}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(onClick = onRewind) {
                    Icon(
                        Icons.Default.FastRewind,
                        contentDescription = "Назад ${settings.rewindSeconds} сек"
                    )
                }

                FilledIconButton(
                    onClick = onTogglePlay,
                    shape = CircleShape,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Играть"
                    )
                }

                IconButton(onClick = onForward) {
                    Icon(
                        Icons.Default.FastForward,
                        contentDescription = "Вперёд ${settings.forwardSeconds} сек"
                    )
                }
            }
        }
    }
}
