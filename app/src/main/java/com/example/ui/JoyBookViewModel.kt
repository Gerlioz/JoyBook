package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayerController
import com.example.audio.AudioWaveformAnalyzer
import com.example.audio.RealtimeAudioLevel
import com.example.audio.RealtimeAudioSpectrumBus
import com.example.audio.SampleAudioGenerator
import com.example.audio.ShakeDetector
import com.example.data.local.AudiobookEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.JoyBookDatabase
import com.example.data.preferences.AccentPalette
import com.example.data.preferences.ControlButtonShape
import com.example.data.preferences.ControlButtonSize
import com.example.data.preferences.CoverFrameStyle
import com.example.data.preferences.LibraryLayoutMode
import com.example.data.preferences.ProgressBarStyle
import com.example.data.preferences.SettingsRepository
import com.example.data.preferences.ShakeSensitivity
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.UserSettings
import com.example.data.repository.AudiobookRepository
import com.example.data.storage.BookTitleCleaner
import com.example.data.storage.ScannedAudioFile
import com.example.data.storage.ScannedBookGroup
import com.example.data.storage.StorageAudioScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class JoyBookTab(val title: String) {
    LIBRARY("Библиотека"),
    PLAYER("Плеер"),
    BOOKMARKS("Закладки"),
    SETTINGS("Настройки")
}

@OptIn(ExperimentalCoroutinesApi::class)
class JoyBookViewModel(application: Application) : AndroidViewModel(application) {

    private val database = JoyBookDatabase.getInstance(application)
    private val repository = AudiobookRepository(application, database.dao())
    private val settingsRepo = SettingsRepository(application)
    private val audioController = AudioPlayerController(application)
    private val shakeDetector = ShakeDetector(application) {
        onPhoneShakenForSleepTimer()
    }

    val settings: StateFlow<UserSettings> = settingsRepo.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings()
    )

    private val _selectedTab = MutableStateFlow(JoyBookTab.LIBRARY)
    val selectedTab: StateFlow<JoyBookTab> = _selectedTab.asStateFlow()

    private val _selectedFormatFilter = MutableStateFlow("ALL") // "ALL", "MP3", "M4A", "M4B", "AMR", "WAV"
    val selectedFormatFilter: StateFlow<String> = _selectedFormatFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeBookId = MutableStateFlow<Long?>(null)
    val activeBookId: StateFlow<Long?> = _activeBookId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _voiceBoostEnabled = MutableStateFlow(false)
    val voiceBoostEnabled: StateFlow<Boolean> = _voiceBoostEnabled.asStateFlow()

    private val _sleepRemainingMs = MutableStateFlow(0L)
    val sleepRemainingMs: StateFlow<Long> = _sleepRemainingMs.asStateFlow()

    private val _stopAtEndOfChapter = MutableStateFlow(false)
    val stopAtEndOfChapter: StateFlow<Boolean> = _stopAtEndOfChapter.asStateFlow()

    private val _smartInactivityEnabled = MutableStateFlow(false)
    val smartInactivityEnabled: StateFlow<Boolean> = _smartInactivityEnabled.asStateFlow()

    private val _inactivityTimeoutMinutes = MutableStateFlow(20)
    val inactivityTimeoutMinutes: StateFlow<Int> = _inactivityTimeoutMinutes.asStateFlow()

    private val _pausedBySleepTimer = MutableStateFlow(false)
    val pausedBySleepTimer: StateFlow<Boolean> = _pausedBySleepTimer.asStateFlow()

    private var lastConfiguredSleepMinutes: Int = 15
    private var lastSleepWasEndOfChapter: Boolean = false
    private val refreshedBookIds = mutableSetOf<Long>()

    private val _isIgnoringBatteryOptimizations = MutableStateFlow(checkBatteryOptimizationIgnored(application))
    val isIgnoringBatteryOptimizations: StateFlow<Boolean> = _isIgnoringBatteryOptimizations.asStateFlow()

    private var lastUserInteractionTimestamp = System.currentTimeMillis()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // Storage Scanner States
    private val _isScanningStorage = MutableStateFlow(false)
    val isScanningStorage: StateFlow<Boolean> = _isScanningStorage.asStateFlow()

    private val _scannedFiles = MutableStateFlow<List<ScannedAudioFile>>(emptyList())
    val scannedFiles: StateFlow<List<ScannedAudioFile>> = _scannedFiles.asStateFlow()

    private val _storageSearchQuery = MutableStateFlow("")
    val storageSearchQuery: StateFlow<String> = _storageSearchQuery.asStateFlow()

    private val _storageFormatFilter = MutableStateFlow("ALL")
    val storageFormatFilter: StateFlow<String> = _storageFormatFilter.asStateFlow()

    private val _storageMinDurationMs = MutableStateFlow(0L)
    val storageMinDurationMs: StateFlow<Long> = _storageMinDurationMs.asStateFlow()

    private val _selectedStorageFolder = MutableStateFlow("ALL")
    val selectedStorageFolder: StateFlow<String> = _selectedStorageFolder.asStateFlow()

    private val _selectedScannedUris = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedScannedUris: StateFlow<Set<Uri>> = _selectedScannedUris.asStateFlow()

    private val _selectedGroupIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedGroupIds: StateFlow<Set<String>> = _selectedGroupIds.asStateFlow()

    private val _selectedFolderUri = MutableStateFlow<Uri?>(null)
    val selectedFolderUri: StateFlow<Uri?> = _selectedFolderUri.asStateFlow()

    private val _selectedFolderName = MutableStateFlow<String?>(null)
    val selectedFolderName: StateFlow<String?> = _selectedFolderName.asStateFlow()

    private val _isImportingScanned = MutableStateFlow(false)
    val isImportingScanned: StateFlow<Boolean> = _isImportingScanned.asStateFlow()

    data class StorageFilterParams(
        val query: String = "",
        val format: String = "ALL",
        val minDurationMs: Long = 0L,
        val folder: String = "ALL"
    )

    val availableStorageFolders: StateFlow<List<String>> = _scannedFiles.map { files: List<ScannedAudioFile> ->
        val folders = files.map { it.folderName }.filter { it.isNotBlank() }.distinct().sorted()
        listOf("ALL") + folders
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("ALL"))

    val allAudiobooks: StateFlow<List<AudiobookEntity>> = repository.allAudiobooks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private val storageFilterParams = combine(
        _storageSearchQuery,
        _storageFormatFilter,
        _storageMinDurationMs,
        _selectedStorageFolder
    ) { query, format, minDur, folder ->
        StorageFilterParams(query, format, minDur, folder)
    }

    val filteredScannedFiles: StateFlow<List<ScannedAudioFile>> = combine(
        _scannedFiles,
        storageFilterParams,
        allAudiobooks
    ) { files, filter, currentBooks ->
        val importedUris = currentBooks.map { it.fileUri }.toSet()
        val importedTitles = currentBooks.flatMap { b ->
            val cleanAuth = BookTitleCleaner.cleanAuthorName(b.author)
            val cleanT = BookTitleCleaner.cleanBookTitle(b.title, cleanAuth)
            listOf(b.title.lowercase().trim(), cleanT.lowercase().trim())
        }.filter { it.isNotBlank() }.toSet()
        val importedFolders = currentBooks.mapNotNull { b ->
            StorageAudioScanner.normalizeCanonicalBookFolder("", "", runCatching { Uri.parse(b.fileUri) }.getOrNull())
                .lowercase().trim('/').takeIf { it.isNotBlank() }
        }.toSet()

        files.map { file ->
            val fileFolder = StorageAudioScanner.normalizeCanonicalBookFolder(file.relativePath, file.folderName, file.uri)
                .lowercase().trim('/')
            val fileCleanTitle = BookTitleCleaner.cleanBookTitle(file.title, file.author).lowercase().trim()
            val isAlready = file.isAlreadyInLibrary ||
                importedUris.contains(file.uri.toString()) ||
                importedTitles.contains(file.title.lowercase().trim()) ||
                (fileCleanTitle.isNotBlank() && importedTitles.contains(fileCleanTitle)) ||
                importedTitles.contains(file.displayName.substringBeforeLast('.').lowercase().trim()) ||
                (fileFolder.isNotBlank() && importedFolders.contains(fileFolder))
            file.copy(isAlreadyInLibrary = isAlready)
        }.filter { file ->
            val matchesQuery = filter.query.isBlank() ||
                file.title.contains(filter.query, ignoreCase = true) ||
                file.author.contains(filter.query, ignoreCase = true) ||
                file.displayName.contains(filter.query, ignoreCase = true) ||
                file.folderName.contains(filter.query, ignoreCase = true)
            val matchesFormat = filter.format == "ALL" || file.format.equals(filter.format, ignoreCase = true)
            val matchesDuration = file.durationMs >= filter.minDurationMs
            val matchesFolder = filter.folder == "ALL" || file.folderName.equals(filter.folder, ignoreCase = true)

            matchesQuery && matchesFormat && matchesDuration && matchesFolder
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredScannedBookGroups: StateFlow<List<ScannedBookGroup>> = combine(
        _scannedFiles,
        storageFilterParams,
        allAudiobooks
    ) { files, filter, currentBooks ->
        val importedUris = currentBooks.map { it.fileUri }.toSet()
        val importedTitles = currentBooks.flatMap { b ->
            val cleanAuth = BookTitleCleaner.cleanAuthorName(b.author)
            val cleanT = BookTitleCleaner.cleanBookTitle(b.title, cleanAuth)
            listOf(b.title.lowercase().trim(), cleanT.lowercase().trim())
        }.filter { it.isNotBlank() }.toSet()
        val importedFolders = currentBooks.mapNotNull { b ->
            StorageAudioScanner.normalizeCanonicalBookFolder("", "", runCatching { Uri.parse(b.fileUri) }.getOrNull())
                .lowercase().trim('/').takeIf { it.isNotBlank() }
        }.toSet()

        val markedFiles = files.map { file ->
            val fileFolder = StorageAudioScanner.normalizeCanonicalBookFolder(file.relativePath, file.folderName, file.uri)
                .lowercase().trim('/')
            val fileCleanTitle = BookTitleCleaner.cleanBookTitle(file.title, file.author).lowercase().trim()
            val isAlready = file.isAlreadyInLibrary ||
                importedUris.contains(file.uri.toString()) ||
                importedTitles.contains(file.title.lowercase().trim()) ||
                (fileCleanTitle.isNotBlank() && importedTitles.contains(fileCleanTitle)) ||
                importedTitles.contains(file.displayName.substringBeforeLast('.').lowercase().trim()) ||
                (fileFolder.isNotBlank() && importedFolders.contains(fileFolder))
            file.copy(isAlreadyInLibrary = isAlready)
        }

        val groups = StorageAudioScanner.groupScannedFiles(markedFiles).map { grp ->
            val grpCleanAuthor = BookTitleCleaner.cleanAuthorName(grp.author)
            val grpCleanTitle = BookTitleCleaner.cleanBookTitle(grp.title, grpCleanAuthor).lowercase().trim()
            val grpFolder = grp.relativePath.ifBlank { grp.folderName }.lowercase().trim('/')
            val alreadyInLib = grp.isAlreadyInLibrary ||
                (grpCleanTitle.isNotBlank() && importedTitles.contains(grpCleanTitle)) ||
                (grpFolder.isNotBlank() && importedFolders.contains(grpFolder))
            grp.copy(isAlreadyInLibrary = alreadyInLib)
        }

        groups.filter { grp ->
            val matchesQuery = filter.query.isBlank() ||
                grp.title.contains(filter.query, ignoreCase = true) ||
                grp.author.contains(filter.query, ignoreCase = true) ||
                grp.folderName.contains(filter.query, ignoreCase = true) ||
                grp.files.any { it.displayName.contains(filter.query, ignoreCase = true) || it.title.contains(filter.query, ignoreCase = true) }

            val matchesFormat = filter.format == "ALL" || grp.formats.any { it.equals(filter.format, ignoreCase = true) }
            val matchesDuration = grp.totalDurationMs >= filter.minDurationMs
            val matchesFolder = filter.folder == "ALL" || grp.folderName.equals(filter.folder, ignoreCase = true) || grp.relativePath.equals(filter.folder, ignoreCase = true)

            matchesQuery && matchesFormat && matchesDuration && matchesFolder
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredAudiobooks: StateFlow<List<AudiobookEntity>> = combine(
        allAudiobooks,
        _selectedFormatFilter,
        _searchQuery
    ) { books, format, query ->
        val cleanQuery = query.trim()
        books.filter { book ->
            val matchesFormat = format == "ALL" || book.audioFormat.equals(format, ignoreCase = true)
            val cleanAuthor = BookTitleCleaner.cleanAuthorName(book.author)
            val cleanTitle = BookTitleCleaner.cleanBookTitle(book.title, cleanAuthor)
            val matchesQuery = cleanQuery.isBlank() ||
                book.title.contains(cleanQuery, ignoreCase = true) ||
                book.author.contains(cleanQuery, ignoreCase = true) ||
                cleanTitle.contains(cleanQuery, ignoreCase = true) ||
                cleanAuthor.contains(cleanQuery, ignoreCase = true)
            matchesFormat && matchesQuery
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val activeBook: StateFlow<AudiobookEntity?> = combine(
        allAudiobooks,
        _activeBookId
    ) { books, activeId ->
        books.find { it.id == activeId } ?: books.firstOrNull()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    val activeBookChapters: StateFlow<List<ChapterEntity>> = activeBook.flatMapLatest { book ->
        if (book != null) repository.getChaptersForBook(book.id) else flowOf(emptyList())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    val activeBookBookmarks: StateFlow<List<BookmarkEntity>> = activeBook.flatMapLatest { book ->
        if (book != null) repository.getBookmarksForBook(book.id) else flowOf(emptyList())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allBookmarks: StateFlow<List<BookmarkEntity>> = repository.allBookmarks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val realtimeAudioLevel: StateFlow<RealtimeAudioLevel> = RealtimeAudioSpectrumBus.level

    private var perSecondLoudnessTable: List<Float> = emptyList()
    private var lastWaveformStep: Long = -1L

    private val _activeBookWaveform = MutableStateFlow<List<Float>>(
        List(AudioWaveformAnalyzer.DEFAULT_PEAK_COUNT) { 0.35f }
    )
    val activeBookWaveform: StateFlow<List<Float>> = _activeBookWaveform.asStateFlow()

    private var playbackTickerJob: Job? = null
    private var lastHandledSleepCompletionMillis: Long = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.purgeSampleAudiobooks()
            repository.refreshLibraryCoversAndTitles()
        }
        viewModelScope.launch {
            allAudiobooks.collect { books ->
                val active = activeBook.value
                if (active != null && !books.any { it.id == active.id }) {
                    _activeBookId.value = books.firstOrNull()?.id
                    _currentPositionMs.value = books.firstOrNull()?.currentPositionMs ?: 0L
                } else if (_activeBookId.value == null && books.isNotEmpty()) {
                    val first = books.first()
                    _activeBookId.value = first.id
                    _currentPositionMs.value = first.currentPositionMs
                }
            }
        }
        viewModelScope.launch {
            activeBook.collect { book ->
                if (book != null) {
                    if (!book.isBuiltIn && refreshedBookIds.add(book.id)) {
                        runCatching { repository.refreshChaptersFromTags(book) }
                    }
                    val sampleFile = if (book.isBuiltIn) {
                        SampleAudioGenerator.ensureSampleAudioFile(getApplication(), book.coverArtStyle)
                    } else null
                    perSecondLoudnessTable = AudioWaveformAnalyzer.extractPerSecondLoudnessTable(
                        context = getApplication(),
                        fileUri = book.fileUri,
                        isBuiltIn = book.isBuiltIn,
                        sampleFile = sampleFile,
                        bookSeed = book.coverArtStyle
                    )
                    lastWaveformStep = -1L
                    updateDynamicWaveformForPosition(book, _currentPositionMs.value, force = true)
                }
            }
        }
        viewModelScope.launch {
            _currentPositionMs.collect { posMs ->
                val book = activeBook.value ?: return@collect
                updateDynamicWaveformForPosition(book, posMs)
            }
        }
        viewModelScope.launch {
            realtimeAudioLevel.collect { level ->
                if (_isPlaying.value) {
                    val book = activeBook.value ?: return@collect
                    val currentStep = (_currentPositionMs.value.coerceAtLeast(0L)) / 75L
                    if (currentStep != lastWaveformStep || level.peakLevel > 0.04f) {
                        lastWaveformStep = currentStep
                        _activeBookWaveform.value = AudioWaveformAnalyzer.computeSmoothWaveform(
                            recordingPeakTable = perSecondLoudnessTable,
                            positionMs = _currentPositionMs.value,
                            totalDurationMs = book.durationMs,
                            liveAudioLevel = level,
                            isPlaying = true,
                            bookSeed = book.coverArtStyle
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            audioController.serviceIsPlaying.collect { playingInService ->
                if (playingInService != _isPlaying.value) {
                    _isPlaying.value = playingInService
                    if (playingInService) {
                        startProgressTicker()
                    } else {
                        playbackTickerJob?.cancel()
                        activeBook.value?.let { book ->
                            repository.updateProgress(book.id, _currentPositionMs.value)
                            updateDynamicWaveformForPosition(book, _currentPositionMs.value)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            audioController.servicePlaybackEnded.collect { ended ->
                if (ended) {
                    playbackTickerJob?.cancel()
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                    activeBook.value?.let { book ->
                        repository.updateProgress(book.id, 0L)
                    }
                }
            }
        }
        viewModelScope.launch {
            audioController.servicePositionChanged.collect { newPos ->
                if (newPos != null) {
                    val book = activeBook.value ?: return@collect
                    val clamped = newPos.coerceIn(0L, book.durationMs.coerceAtLeast(newPos))
                    _currentPositionMs.value = clamped
                    repository.updateProgress(book.id, clamped)
                }
            }
        }
        viewModelScope.launch {
            audioController.sessionSleepTimerState.collect { timerState ->
                _sleepRemainingMs.value = timerState.remainingMs
                if (
                    timerState.lastCompletedAtMillis > 0L &&
                    timerState.lastCompletedAtMillis != lastHandledSleepCompletionMillis
                ) {
                    lastHandledSleepCompletionMillis = timerState.lastCompletedAtMillis
                    _pausedBySleepTimer.value = true
                    val rewindMs = settings.value.autoRewindOnSleepSec * 1000L
                    val nextPos = (_currentPositionMs.value - rewindMs).coerceAtLeast(0L)
                    _currentPositionMs.value = nextPos
                    activeBook.value?.let { book ->
                        repository.updateProgress(book.id, nextPos)
                    }
                    val shakeHint = if (settings.value.shakeToResumeSleepEnabled) {
                        " Потрясите телефон (${settings.value.shakeSensitivity.label.lowercase()}), чтобы продолжить."
                    } else ""
                    _snackbarMessage.value = "Таймер сна завершён. Воспроизведение приостановлено.$shakeHint"
                }
                updateShakeDetectorRegistration()
            }
        }
        viewModelScope.launch {
            settings.collect { currentSettings ->
                shakeDetector.isEnabled = currentSettings.shakeToResumeSleepEnabled
                shakeDetector.sensitivity = currentSettings.shakeSensitivity
                updateShakeDetectorRegistration()
            }
        }
    }

    private fun updateShakeDetectorRegistration() {
        val currentSettings = settings.value
        val shouldListen = currentSettings.shakeToResumeSleepEnabled && (
            _sleepRemainingMs.value > 0L ||
                _stopAtEndOfChapter.value ||
                _smartInactivityEnabled.value ||
                _pausedBySleepTimer.value
            )
        if (shouldListen) {
            shakeDetector.startListening()
        } else {
            shakeDetector.stopListening()
        }
    }

    /**
     * Triggered when the accelerometer detects a phone shake matching the user's configured
     * [ShakeSensitivity] ("Слабый", "Умеренно", "Сильно").
     * Resets the sleep timer and resumes playback if the sleep timer had paused it.
     */
    fun onPhoneShakenForSleepTimer() {
        viewModelScope.launch {
            if (!settings.value.shakeToResumeSleepEnabled) return@launch
            lastUserInteractionTimestamp = System.currentTimeMillis()
            audioController.setVolumeMultiplier(1.0f)

            val wasPaused = _pausedBySleepTimer.value || !_isPlaying.value
            _pausedBySleepTimer.value = false

            if (lastSleepWasEndOfChapter || _stopAtEndOfChapter.value) {
                startSleepAtEndOfChapter()
            } else if (_sleepRemainingMs.value > 0L || wasPaused) {
                val minutes = lastConfiguredSleepMinutes.coerceAtLeast(5)
                val totalMs = minutes * 60_000L
                audioController.startSessionSleepTimer(
                    durationMs = totalMs,
                    fadeOutEnabled = settings.value.sleepTimerFadeOut,
                    autoRewindMs = settings.value.autoRewindOnSleepSec * 1000L
                )
            }

            if (wasPaused) {
                activeBook.value?.let { book ->
                    startPlayback(book)
                }
                _snackbarMessage.value =
                    "Таймер сна сброшен встряхиванием (${settings.value.shakeSensitivity.label}) — воспроизведение продолжено"
            } else {
                _snackbarMessage.value =
                    "Таймер сна сброшен встряхиванием (${settings.value.shakeSensitivity.label})"
            }
            updateShakeDetectorRegistration()
        }
    }

    private fun checkBatteryOptimizationIgnored(context: Application): Boolean {
        return runCatching {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.getOrDefault(false)
    }

    fun refreshBatteryOptimizationState() {
        _isIgnoringBatteryOptimizations.value = checkBatteryOptimizationIgnored(getApplication())
    }

    private fun updateDynamicWaveformForPosition(
        book: AudiobookEntity,
        positionMs: Long,
        force: Boolean = false
    ) {
        val safePos = positionMs.coerceAtLeast(0L)
        val currentStep = safePos / 75L
        if (!force && currentStep == lastWaveformStep) return
        lastWaveformStep = currentStep

        _activeBookWaveform.value = AudioWaveformAnalyzer.computeSmoothWaveform(
            recordingPeakTable = perSecondLoudnessTable,
            positionMs = safePos,
            totalDurationMs = book.durationMs,
            liveAudioLevel = realtimeAudioLevel.value,
            isPlaying = _isPlaying.value,
            bookSeed = book.coverArtStyle
        )
    }

    fun selectTab(tab: JoyBookTab) {
        _selectedTab.value = tab
    }

    fun setFormatFilter(format: String) {
        _selectedFormatFilter.value = format
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun openAuthorBooksInLibrary(authorName: String) {
        recordUserInteraction()
        val cleanTarget = BookTitleCleaner.cleanAuthorName(authorName).trim()
        if (cleanTarget.isBlank() || cleanTarget.equals("Неизвестный автор", ignoreCase = true)) {
            _snackbarMessage.value = "У этой аудиокниги не указан автор"
            return
        }
        val currentBookId = activeBook.value?.id
        val matchingBooks = allAudiobooks.value.filter { book ->
            val bookCleanAuthor = BookTitleCleaner.cleanAuthorName(book.author).trim()
            bookCleanAuthor.equals(cleanTarget, ignoreCase = true) ||
                bookCleanAuthor.contains(cleanTarget, ignoreCase = true) ||
                (cleanTarget.contains(bookCleanAuthor, ignoreCase = true) && bookCleanAuthor.length >= 3)
        }
        val otherBooks = matchingBooks.filter { it.id != currentBookId }
        if (otherBooks.isNotEmpty()) {
            _selectedFormatFilter.value = "ALL"
            _searchQuery.value = cleanTarget
            _selectedTab.value = JoyBookTab.LIBRARY
            _snackbarMessage.value = "Другие аудиокниги автора «$cleanTarget» (${matchingBooks.size})"
        } else {
            _snackbarMessage.value = "Других аудиокниг автора «$cleanTarget» в библиотеке нет"
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun selectAndPlayBook(book: AudiobookEntity, openPlayerScreen: Boolean = true) {
        recordUserInteraction()
        val switchingBook = _activeBookId.value != book.id
        _activeBookId.value = book.id
        if (switchingBook) {
            _currentPositionMs.value = book.currentPositionMs
        }
        if (openPlayerScreen) {
            _selectedTab.value = JoyBookTab.PLAYER
        }
        startPlayback(book)
    }

    fun togglePlayPause() {
        recordUserInteraction()
        val book = activeBook.value ?: return
        if (_isPlaying.value) {
            _pausedBySleepTimer.value = false
            pausePlayback()
        } else {
            _pausedBySleepTimer.value = false
            startPlayback(book)
        }
    }

    private fun startPlayback(book: AudiobookEntity) {
        viewModelScope.launch {
            val sampleFile: File? = if (book.isBuiltIn) {
                SampleAudioGenerator.ensureSampleAudioFile(getApplication(), book.coverArtStyle)
            } else {
                null
            }
            var chapters = repository.getChaptersForBookOnce(book.id)
            if (!book.isBuiltIn && (
                refreshedBookIds.add(book.id) ||
                    chapters.isEmpty() ||
                    chapters.all { it.title.startsWith("Часть ") }
                )
            ) {
                chapters = repository.refreshChaptersFromTags(book)
            }
            val started = audioController.startBookPlayback(
                book = book.copy(currentPositionMs = _currentPositionMs.value),
                chapters = chapters,
                sampleFile = sampleFile,
                onError = { msg -> _snackbarMessage.value = msg }
            )
            if (started) {
                _isPlaying.value = true
                startProgressTicker()
            }
        }
    }

    fun pausePlayback() {
        audioController.pause()
        _isPlaying.value = false
        playbackTickerJob?.cancel()
        val book = activeBook.value ?: return
        updateDynamicWaveformForPosition(book, _currentPositionMs.value)
        viewModelScope.launch {
            repository.updateProgress(book.id, _currentPositionMs.value)
        }
    }

    private fun startProgressTicker() {
        playbackTickerJob?.cancel()
        playbackTickerJob = viewModelScope.launch {
            var tickCounter = 0
            val tickIntervalMs = 80L
            while (isActive && _isPlaying.value) {
                delay(tickIntervalMs)
                val book = activeBook.value ?: continue
                val prevPos = _currentPositionMs.value
                val externalPos = audioController.getExternalPlayerPositionOrNull()
                val delta = (tickIntervalMs * book.playbackSpeed).toLong().coerceAtLeast(1L)
                val nextPos = (externalPos ?: (prevPos + delta)).coerceAtLeast(0L)

                // 1. Smart chapter end detection
                if (_stopAtEndOfChapter.value) {
                    val chapters = activeBookChapters.value
                    val prevChapter = chapters.lastOrNull { prevPos >= it.startTimeMs }
                    val newChapter = chapters.lastOrNull { nextPos >= it.startTimeMs }
                    val currentChapterEnd = prevChapter?.let { it.startTimeMs + it.durationMs } ?: book.durationMs

                    val crossedChapterEnd = prevChapter != null && nextPos >= (currentChapterEnd - 250L)
                    val changedToNextChapter = prevChapter != null && newChapter != null && newChapter.chapterIndex > prevChapter.chapterIndex

                    if (crossedChapterEnd || changedToNextChapter) {
                        val stopPos = newChapter?.startTimeMs ?: currentChapterEnd.coerceAtMost(book.durationMs)
                        _currentPositionMs.value = stopPos
                        _stopAtEndOfChapter.value = false
                        _pausedBySleepTimer.value = true
                        updateShakeDetectorRegistration()
                        pausePlayback()
                        val shakeHint = if (settings.value.shakeToResumeSleepEnabled) {
                            " Потрясите телефон, чтобы продолжить."
                        } else ""
                        _snackbarMessage.value = "Таймер сна: текущая глава завершена, воспроизведение приостановлено.$shakeHint"
                        break
                    }
                }

                // 2. Smart inactivity detection
                if (_smartInactivityEnabled.value) {
                    val idleMs = System.currentTimeMillis() - lastUserInteractionTimestamp
                    val timeoutMs = _inactivityTimeoutMinutes.value * 60_000L

                    // Soft volume attenuation in the final 20 seconds before pause
                    if (idleMs in (timeoutMs - 20_000L)..timeoutMs) {
                        val fadeFactor = ((timeoutMs - idleMs).toFloat() / 20_000f).coerceIn(0.1f, 1.0f)
                        audioController.setVolumeMultiplier(fadeFactor)
                    }

                    if (idleMs >= timeoutMs) {
                        audioController.setVolumeMultiplier(1.0f)
                        lastUserInteractionTimestamp = System.currentTimeMillis()
                        _pausedBySleepTimer.value = true
                        updateShakeDetectorRegistration()
                        pausePlayback()
                        val shakeHint = if (settings.value.shakeToResumeSleepEnabled) {
                            " Потрясите телефон, чтобы продолжить."
                        } else ""
                        _snackbarMessage.value = "Умный таймер: воспроизведение приостановлено из-за отсутствия активности (${_inactivityTimeoutMinutes.value} мин).$shakeHint"
                        break
                    }
                }

                _currentPositionMs.value = nextPos.coerceAtMost(book.durationMs.coerceAtLeast(nextPos))
                tickCounter++
                if (tickCounter % 35 == 0) {
                    repository.updateProgress(book.id, _currentPositionMs.value)
                }
            }
        }
    }

    fun seekTo(targetMs: Long) {
        recordUserInteraction()
        val book = activeBook.value ?: return
        val clamped = targetMs.coerceIn(0L, book.durationMs)
        _currentPositionMs.value = clamped
        audioController.seekTo(clamped)
        viewModelScope.launch {
            repository.updateProgress(book.id, clamped)
        }
    }

    fun rewindByConfiguredStep() {
        recordUserInteraction()
        val stepMs = settings.value.rewindSeconds * 1000L
        seekTo(_currentPositionMs.value - stepMs)
    }

    fun forwardByConfiguredStep() {
        recordUserInteraction()
        val stepMs = settings.value.forwardSeconds * 1000L
        seekTo(_currentPositionMs.value + stepMs)
    }

    fun skipToPreviousChapter() {
        recordUserInteraction()
        val chapters = activeBookChapters.value
        if (chapters.isEmpty()) {
            seekTo(0L)
            return
        }
        val pos = _currentPositionMs.value
        val currentIdx = chapters.indexOfLast { pos >= it.startTimeMs }.coerceAtLeast(0)
        val currentCh = chapters[currentIdx]
        if (pos - currentCh.startTimeMs > 4000L || currentIdx == 0) {
            seekTo(currentCh.startTimeMs)
        } else {
            seekTo(chapters[currentIdx - 1].startTimeMs)
        }
    }

    fun skipToNextChapter() {
        recordUserInteraction()
        val chapters = activeBookChapters.value
        if (chapters.isEmpty()) return
        val pos = _currentPositionMs.value
        val currentIdx = chapters.indexOfLast { pos >= it.startTimeMs }.coerceAtLeast(0)
        if (currentIdx + 1 < chapters.size) {
            seekTo(chapters[currentIdx + 1].startTimeMs)
        } else {
            _snackbarMessage.value = "Это последняя глава книги"
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val book = activeBook.value ?: return
        val clamped = speed.coerceIn(0.5f, 2.0f)
        audioController.applyPlaybackSpeed(clamped)
        viewModelScope.launch {
            repository.updateSpeed(book.id, clamped)
        }
    }

    fun toggleVoiceBoost() {
        val next = !_voiceBoostEnabled.value
        _voiceBoostEnabled.value = next
        audioController.setVoiceBoost(next)
        _snackbarMessage.value = if (next) {
            "Усиление голоса включено (+7.5 дБ)"
        } else {
            "Усиление голоса выключено"
        }
    }

    fun toggleFavorite(book: AudiobookEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(book.id, !book.isFavorite)
        }
    }

    fun addBookmarkForActiveBook(note: String) {
        val book = activeBook.value ?: return
        val pos = _currentPositionMs.value
        val chapterTitle = activeBookChapters.value
            .lastOrNull { pos >= it.startTimeMs }?.title ?: "Основная часть"
        viewModelScope.launch {
            repository.addBookmark(
                audiobookId = book.id,
                audiobookTitle = book.title,
                positionMs = pos,
                chapterTitle = chapterTitle,
                note = note
            )
            _snackbarMessage.value = "Закладка сохранена"
        }
    }

    fun jumpToBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            val book = repository.getAudiobookById(bookmark.audiobookId) ?: return@launch
            _activeBookId.value = book.id
            _currentPositionMs.value = bookmark.positionMs
            repository.updateProgress(book.id, bookmark.positionMs)
            _selectedTab.value = JoyBookTab.PLAYER
            startPlayback(book.copy(currentPositionMs = bookmark.positionMs))
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            repository.deleteBookmark(id)
        }
    }

    // Sleep Timer Management interacting with Media3 ExoPlayer Session
    fun startSleepTimerMinutes(minutes: Int) {
        val validMinutes = minutes.coerceAtLeast(1)
        lastConfiguredSleepMinutes = validMinutes
        lastSleepWasEndOfChapter = false
        _pausedBySleepTimer.value = false
        _stopAtEndOfChapter.value = false
        val totalMs = validMinutes * 60_000L
        audioController.startSessionSleepTimer(
            durationMs = totalMs,
            fadeOutEnabled = settings.value.sleepTimerFadeOut,
            autoRewindMs = settings.value.autoRewindOnSleepSec * 1000L
        )
        updateShakeDetectorRegistration()
        _snackbarMessage.value = "Таймер сна установлен на $validMinutes мин"
    }

    fun startSleepAtEndOfChapter() {
        val chapters = activeBookChapters.value
        val pos = _currentPositionMs.value
        val currentChapter = chapters.lastOrNull { pos >= it.startTimeMs }
        val speed = (activeBook.value?.playbackSpeed ?: 1.0f).coerceAtLeast(0.5f)
        lastSleepWasEndOfChapter = true
        _pausedBySleepTimer.value = false

        if (currentChapter != null) {
            val chapterEndMs = currentChapter.startTimeMs + currentChapter.durationMs
            val remainingInChapterMs = ((chapterEndMs - pos) / speed).toLong().coerceAtLeast(1_000L)
            _stopAtEndOfChapter.value = true
            audioController.startSessionSleepTimer(
                durationMs = remainingInChapterMs,
                fadeOutEnabled = settings.value.sleepTimerFadeOut,
                autoRewindMs = 0L
            )
            updateShakeDetectorRegistration()
            _snackbarMessage.value = "Таймер сна: остановка в конце текущей главы"
        } else {
            audioController.cancelSessionSleepTimer()
            _stopAtEndOfChapter.value = true
            updateShakeDetectorRegistration()
            _snackbarMessage.value = "Таймер сна: остановка в конце текущей главы"
        }
    }

    fun extendSleepTimerFiveMinutes() {
        if (_sleepRemainingMs.value > 0L) {
            audioController.extendSessionSleepTimer(5 * 60_000L)
            updateShakeDetectorRegistration()
            _snackbarMessage.value = "Таймер сна продлён на 5 минут"
        } else {
            startSleepTimerMinutes(5)
        }
    }

    fun cancelSleepTimer() {
        _stopAtEndOfChapter.value = false
        _pausedBySleepTimer.value = false
        lastSleepWasEndOfChapter = false
        audioController.cancelSessionSleepTimer()
        updateShakeDetectorRegistration()
        _snackbarMessage.value = "Таймер сна отключён"
    }

    fun recordUserInteraction() {
        lastUserInteractionTimestamp = System.currentTimeMillis()
    }

    fun setSmartInactivity(enabled: Boolean, minutes: Int = _inactivityTimeoutMinutes.value) {
        _smartInactivityEnabled.value = enabled
        _inactivityTimeoutMinutes.value = minutes.coerceIn(5, 120)
        lastUserInteractionTimestamp = System.currentTimeMillis()
        updateShakeDetectorRegistration()
        if (enabled) {
            _snackbarMessage.value = "Таймер бездействия активен: пауза через $minutes мин покоя"
        } else {
            _snackbarMessage.value = "Таймер бездействия отключён"
        }
    }

    fun toggleStopAtEndOfChapter() {
        if (_stopAtEndOfChapter.value) {
            _stopAtEndOfChapter.value = false
            lastSleepWasEndOfChapter = false
            updateShakeDetectorRegistration()
            _snackbarMessage.value = "Остановка в конце главы отключена"
        } else {
            startSleepAtEndOfChapter()
        }
    }

    // Import Local Audio File (mp3, m4a, m4b, amr, wav) — skips duplicates if already in library
    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val parsed = audioController.extractMetadataFromUri(uri)
            val resultId = repository.importExternalAudiobook(
                title = parsed.title,
                author = parsed.author,
                format = parsed.format,
                uriString = uri.toString(),
                durationMs = parsed.durationMs,
                bitrateKbps = parsed.bitrateKbps
            )
            val cleanTitle = BookTitleCleaner.cleanBookTitle(parsed.title, parsed.author).ifBlank { parsed.title }
            if (resultId > 0L) {
                _activeBookId.value = resultId
                _currentPositionMs.value = 0L
                _snackbarMessage.value = "Добавлена новая аудиокнига «$cleanTitle» (${parsed.format})"
            } else if (resultId < 0L) {
                val existingId = -resultId
                _activeBookId.value = existingId
                _snackbarMessage.value = "Книга «$cleanTitle» уже есть в библиотеке (дубликат пропущен)"
            }
        }
    }

    fun importMultipleAudioUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) {
            importAudioUri(uris.first())
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isImportingScanned.value = true
            val scannedList = uris.mapIndexed { idx, uri ->
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val parsed = audioController.extractMetadataFromUri(uri)
                val decoded = runCatching { Uri.decode(uri.toString()) }.getOrDefault(uri.toString())
                val displayName = decoded.substringAfterLast('/').ifBlank { "track_${idx + 1}.${parsed.format.lowercase()}" }
                val folderPath = StorageAudioScanner.normalizeCanonicalBookFolder("", "", uri)
                val folderName = folderPath.substringAfterLast('/').ifBlank { "Выбранная папка" }
                ScannedAudioFile(
                    uri = uri,
                    title = parsed.title,
                    author = parsed.author,
                    album = "",
                    trackNumber = idx + 1,
                    displayName = displayName,
                    folderName = folderName,
                    relativePath = folderPath,
                    format = parsed.format,
                    durationMs = parsed.durationMs,
                    sizeBytes = 0L,
                    bitrateKbps = parsed.bitrateKbps,
                    dateModifiedMs = System.currentTimeMillis()
                )
            }
            val importedIds = repository.importScannedAudioFiles(scannedList)
            _isImportingScanned.value = false
            if (importedIds.isNotEmpty()) {
                _activeBookId.value = importedIds.first()
                _currentPositionMs.value = 0L
                _snackbarMessage.value = "Добавлено новых аудиокниг: ${importedIds.size}"
            } else {
                _snackbarMessage.value = "Выбранные аудиокниги уже есть в библиотеке (дубликаты пропущены)"
            }
        }
    }

    // Storage Scanner Functions
    fun setStorageSearchQuery(query: String) {
        _storageSearchQuery.value = query
    }

    fun setStorageFormatFilter(format: String) {
        _storageFormatFilter.value = format
    }

    fun setStorageMinDurationMs(minMs: Long) {
        _storageMinDurationMs.value = minMs
    }

    fun setStorageFolderFilter(folder: String) {
        _selectedStorageFolder.value = folder
    }

    fun toggleScannedFileSelection(file: ScannedAudioFile) {
        val current = _selectedScannedUris.value.toMutableSet()
        if (current.contains(file.uri)) {
            current.remove(file.uri)
        } else {
            current.add(file.uri)
        }
        _selectedScannedUris.value = current
    }

    fun selectAllScannedFiles() {
        val selectable = filteredScannedFiles.value.filter { !it.isAlreadyInLibrary }.map { it.uri }.toSet()
        _selectedScannedUris.value = selectable
    }

    fun clearScannedFilesSelection() {
        _selectedScannedUris.value = emptySet()
        _selectedGroupIds.value = emptySet()
    }

    fun toggleScannedGroupSelection(group: ScannedBookGroup) {
        val current = _selectedGroupIds.value.toMutableSet()
        if (current.contains(group.groupId)) {
            current.remove(group.groupId)
        } else {
            current.add(group.groupId)
        }
        _selectedGroupIds.value = current

        val selectedFiles = filteredScannedBookGroups.value
            .filter { current.contains(it.groupId) }
            .flatMap { it.files }
            .map { it.uri }
            .toSet()
        _selectedScannedUris.value = selectedFiles
    }

    fun selectAllScannedGroups() {
        val groups = filteredScannedBookGroups.value.filter { !it.isAlreadyInLibrary }
        _selectedGroupIds.value = groups.map { it.groupId }.toSet()
        _selectedScannedUris.value = groups.flatMap { it.files }.map { it.uri }.toSet()
    }

    fun clearScannedGroupsSelection() {
        _selectedGroupIds.value = emptySet()
        _selectedScannedUris.value = emptySet()
    }

    fun importScannedGroup(group: ScannedBookGroup) {
        if (group.isAlreadyInLibrary) {
            _snackbarMessage.value = "Книга «${group.title}» уже есть в библиотеке"
            return
        }
        viewModelScope.launch {
            _isImportingScanned.value = true
            for (file in group.files) {
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        file.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            val newId = repository.importScannedBookGroup(group)
            _isImportingScanned.value = false
            if (newId > 0L) {
                _activeBookId.value = newId
                _currentPositionMs.value = 0L
                val info = if (group.isMultiFile) " (${group.fileCount} частей)" else ""
                _snackbarMessage.value = "Книга «${group.title}»$info добавлена в библиотеку JoyBook"
            } else {
                _snackbarMessage.value = "Книга «${group.title}» уже есть в библиотеке (дубликат пропущен)"
            }
        }
    }

    fun importSelectedScannedGroups(onFinished: ((Int) -> Unit)? = null) {
        val selectedIds = _selectedGroupIds.value
        val groupsToImport = filteredScannedBookGroups.value.filter { selectedIds.contains(it.groupId) && !it.isAlreadyInLibrary }
        if (groupsToImport.isEmpty()) {
            _snackbarMessage.value = "Выберите новые аудиокниги для импорта"
            return
        }
        viewModelScope.launch {
            _isImportingScanned.value = true
            var count = 0
            for (grp in groupsToImport) {
                for (file in grp.files) {
                    runCatching {
                        getApplication<Application>().contentResolver.takePersistableUriPermission(
                            file.uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                val id = repository.importScannedBookGroup(grp)
                if (id > 0L) count++
            }
            _isImportingScanned.value = false
            clearScannedGroupsSelection()
            if (count > 0) {
                _snackbarMessage.value = "Добавлено новых аудиокниг: $count"
            } else {
                _snackbarMessage.value = "Выбранные книги уже есть в библиотеке"
            }
            onFinished?.invoke(count)
        }
    }

    fun scanDeviceInternalStorage() {
        viewModelScope.launch {
            _isScanningStorage.value = true
            _selectedScannedUris.value = emptySet()
            val existingUris = repository.getAllImportedUrisOnce()
            val scanned = StorageAudioScanner.scanDeviceMediaStore(
                context = getApplication(),
                existingUris = existingUris,
                query = "",
                formatFilter = "ALL",
                minDurationMs = 0L
            )
            _scannedFiles.value = scanned
            _isScanningStorage.value = false
            if (scanned.isEmpty()) {
                _snackbarMessage.value = "Аудиофайлы не найдены в MediaStore. Выберите папку на устройстве вручную."
            } else {
                val notImportedCount = filteredScannedBookGroups.value.count { !it.isAlreadyInLibrary }
                _snackbarMessage.value = "Найдено аудиофайлов: ${scanned.size} (новых книг: $notImportedCount)"
            }
        }
    }

    fun scanFolderTree(treeUri: Uri, customFolderName: String? = null) {
        viewModelScope.launch {
            _isScanningStorage.value = true
            _selectedScannedUris.value = emptySet()
            _selectedFolderUri.value = treeUri
            val resolvedName = customFolderName ?: StorageAudioScanner.getFolderName(getApplication(), treeUri)
            _selectedFolderName.value = resolvedName
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val existingUris = repository.getAllImportedUrisOnce()
            val scanned = StorageAudioScanner.scanDocumentTree(
                context = getApplication(),
                treeUri = treeUri,
                existingUris = existingUris,
                query = "",
                formatFilter = "ALL",
                minDurationMs = 0L
            )
            _scannedFiles.value = scanned
            _isScanningStorage.value = false
            if (scanned.isEmpty()) {
                _snackbarMessage.value = "В папке «$resolvedName» не найдено аудиофайлов (MP3, M4B, M4A, AMR, WAV)"
            } else {
                val notImportedCount = scanned.count { !it.isAlreadyInLibrary }
                _snackbarMessage.value = "Найдено в «$resolvedName»: ${scanned.size} аудиофайлов (новых: $notImportedCount)"
            }
        }
    }

    fun clearSelectedFolder() {
        _selectedFolderUri.value = null
        _selectedFolderName.value = null
        _scannedFiles.value = emptyList()
        _selectedScannedUris.value = emptySet()
    }

    fun importSingleScannedFile(file: ScannedAudioFile) {
        if (file.isAlreadyInLibrary) {
            _snackbarMessage.value = "«${file.title}» уже есть в библиотеке"
            return
        }
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    file.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val newId = repository.importExternalAudiobook(
                title = file.title,
                author = file.author,
                format = file.format,
                uriString = file.uri.toString(),
                durationMs = file.durationMs,
                bitrateKbps = file.bitrateKbps,
                coverImagePath = file.coverImagePath
            )
            if (newId > 0L) {
                _activeBookId.value = newId
                _currentPositionMs.value = 0L
                _snackbarMessage.value = "«${file.title}» добавлена в библиотеку JoyBook"
            } else {
                _snackbarMessage.value = "«${file.title}» уже есть в библиотеке (дубликат пропущен)"
            }
        }
    }

    fun importSelectedScannedFiles(onFinished: ((Int) -> Unit)? = null) {
        val selected = _selectedScannedUris.value
        if (selected.isEmpty()) {
            _snackbarMessage.value = "Выберите файлы для импорта"
            return
        }
        viewModelScope.launch {
            _isImportingScanned.value = true
            val filesToImport = _scannedFiles.value.filter { selected.contains(it.uri) && !it.isAlreadyInLibrary }
            for (file in filesToImport) {
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        file.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            val importedIds = repository.importScannedAudioFiles(filesToImport)
            if (importedIds.isNotEmpty()) {
                _activeBookId.value = importedIds.first()
                _currentPositionMs.value = 0L
                _snackbarMessage.value = "Успешно добавлено новых аудиокниг: ${importedIds.size}"
            } else {
                _snackbarMessage.value = "Выбранные книги уже есть в библиотеке"
            }
            _selectedScannedUris.value = emptySet()
            _isImportingScanned.value = false
            onFinished?.invoke(importedIds.size)
        }
    }

    fun clearLibrary() {
        viewModelScope.launch {
            playbackTickerJob?.cancel()
            _isPlaying.value = false
            _activeBookId.value = null
            _currentPositionMs.value = 0L
            refreshedBookIds.clear()
            audioController.closePlayer()
            repository.clearAllLibrary()
            _scannedFiles.value = _scannedFiles.value.map { it.copy(isAlreadyInLibrary = false) }
            _snackbarMessage.value = "Библиотека очищена (аудиофайлы на устройстве сохранены)"
        }
    }

    // Settings Updaters
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.updateThemeMode(mode) }
    fun setAccentPalette(palette: AccentPalette) = viewModelScope.launch { settingsRepo.updateAccentPalette(palette) }
    fun setRewindSeconds(sec: Int) = viewModelScope.launch { settingsRepo.updateRewindSeconds(sec) }
    fun setForwardSeconds(sec: Int) = viewModelScope.launch { settingsRepo.updateForwardSeconds(sec) }
    fun setButtonSize(size: ControlButtonSize) = viewModelScope.launch { settingsRepo.updateButtonSize(size) }
    fun setButtonShape(shape: ControlButtonShape) = viewModelScope.launch { settingsRepo.updateButtonShape(shape) }
    fun setShowChapterSkip(show: Boolean) = viewModelScope.launch { settingsRepo.updateShowChapterSkip(show) }
    fun setShowSpeedButton(show: Boolean) = viewModelScope.launch { settingsRepo.updateShowSpeedButton(show) }
    fun setShowBookmarkButton(show: Boolean) = viewModelScope.launch { settingsRepo.updateShowBookmarkButton(show) }
    fun setShowSleepButton(show: Boolean) = viewModelScope.launch { settingsRepo.updateShowSleepButton(show) }
    fun setShowVoiceBoostButton(show: Boolean) = viewModelScope.launch { settingsRepo.updateShowVoiceBoostButton(show) }
    fun setSwapSeekButtons(swap: Boolean) = viewModelScope.launch { settingsRepo.updateSwapSeekButtons(swap) }
    fun setCoverFrameStyle(style: CoverFrameStyle) = viewModelScope.launch { settingsRepo.updateCoverFrameStyle(style) }
    fun setCoverBlurGlow(enabled: Boolean) = viewModelScope.launch { settingsRepo.updateCoverBlurGlow(enabled) }
    fun setProgressBarStyle(style: ProgressBarStyle) = viewModelScope.launch { settingsRepo.updateProgressBarStyle(style) }
    fun setLibraryLayoutMode(mode: LibraryLayoutMode) = viewModelScope.launch { settingsRepo.updateLibraryLayoutMode(mode) }
    fun setFontScale(scale: Float) = viewModelScope.launch { settingsRepo.updateFontScale(scale) }
    fun setShowFormatBadges(show: Boolean) = viewModelScope.launch { settingsRepo.updateShowFormatBadges(show) }
    fun setShowChapterRemaining(show: Boolean) = viewModelScope.launch { settingsRepo.updateShowChapterRemaining(show) }
    fun setSleepTimerFadeOut(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateSleepTimerFadeOut(enabled)
        audioController.setSessionSleepFadeOut(enabled)
    }
    fun setShakeToResumeSleep(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.updateShakeToResumeSleep(enabled)
    }
    fun setShakeSensitivity(sensitivity: ShakeSensitivity) = viewModelScope.launch {
        settingsRepo.updateShakeSensitivity(sensitivity)
        _snackbarMessage.value = "Чувствительность тряски для таймера сна: ${sensitivity.label}"
    }
    fun resetSettingsToDefaults() = viewModelScope.launch {
        settingsRepo.resetToDefaults()
        _snackbarMessage.value = "Настройки возвращены к значениям по умолчанию"
    }

    override fun onCleared() {
        super.onCleared()
        shakeDetector.stopListening()
        audioController.release()
    }
}
