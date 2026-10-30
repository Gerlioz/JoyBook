package com.example.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.audio.M4bChapterParser
import com.example.audio.SampleAudioGenerator
import com.example.data.local.AudiobookEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.JoyBookDao
import com.example.data.storage.BookTitleCleaner
import com.example.data.storage.ScannedAudioFile
import com.example.data.storage.ScannedBookGroup
import com.example.data.storage.StorageAudioScanner
import kotlinx.coroutines.flow.Flow
import java.io.File

class AudiobookRepository(
    private val context: Context,
    private val dao: JoyBookDao
) {
    val allAudiobooks: Flow<List<AudiobookEntity>> = dao.getAllAudiobooks()
    val allBookmarks: Flow<List<BookmarkEntity>> = dao.getAllBookmarks()

    fun getChaptersForBook(audiobookId: Long): Flow<List<ChapterEntity>> =
        dao.getChaptersForBook(audiobookId)

    suspend fun getChaptersForBookOnce(audiobookId: Long): List<ChapterEntity> =
        dao.getChaptersForBookOnce(audiobookId)

    fun getBookmarksForBook(audiobookId: Long): Flow<List<BookmarkEntity>> =
        dao.getBookmarksForBook(audiobookId)

    suspend fun getAudiobookById(id: Long): AudiobookEntity? = dao.getAudiobookById(id)

    suspend fun updateProgress(id: Long, positionMs: Long) {
        dao.updateProgress(id, positionMs, System.currentTimeMillis())
    }

    suspend fun updateSpeed(id: Long, speed: Float) {
        dao.updatePlaybackSpeed(id, speed)
    }

    suspend fun toggleFavorite(id: Long, isFavorite: Boolean) {
        dao.updateFavorite(id, isFavorite)
    }

    suspend fun addBookmark(
        audiobookId: Long,
        audiobookTitle: String,
        positionMs: Long,
        chapterTitle: String,
        note: String
    ) {
        val cleanBookTitle = BookTitleCleaner.cleanBookTitle(audiobookTitle)
        dao.insertBookmark(
            BookmarkEntity(
                audiobookId = audiobookId,
                audiobookTitle = cleanBookTitle,
                positionMs = positionMs,
                chapterTitle = chapterTitle,
                note = note.ifBlank { "Заметка на ${formatDurationShort(positionMs)}" }
            )
        )
    }

    suspend fun deleteBookmark(id: Long) {
        dao.deleteBookmark(id)
    }

    suspend fun deleteAudiobook(id: Long) {
        dao.deleteBookmarksForBook(id)
        dao.deleteChaptersForBook(id)
        dao.deleteAudiobook(id)
    }

    suspend fun clearAllLibrary() {
        dao.deleteAllBookmarks()
        dao.deleteAllChapters()
        dao.deleteAllAudiobooks()
        runCatching {
            val coversDir = File(context.filesDir, "covers")
            if (coversDir.exists() && coversDir.isDirectory) {
                coversDir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    suspend fun getAllImportedUrisOnce(): Set<String> {
        val bookUris = runCatching { dao.getAllAudiobooksOnce().map { it.fileUri } }.getOrDefault(emptyList())
        val chapterUris = runCatching { dao.getAllChapterFileUrisOnce() }.getOrDefault(emptyList())
        return (bookUris + chapterUris).filter { it.isNotBlank() }.toSet()
    }

    private fun normalizeUriSignature(uriStr: String): String {
        if (uriStr.isBlank()) return ""
        val decoded = runCatching { Uri.decode(uriStr) }.getOrDefault(uriStr)
            .replace('\\', '/')
            .trim()
        val afterColon = decoded.substringAfterLast(':', decoded).trimStart('/')
        return afterColon.lowercase()
    }

    suspend fun findExistingDuplicateBook(
        candidateUris: Collection<String>,
        cleanedTitle: String,
        cleanedAuthor: String,
        durationMs: Long = 0L,
        canonicalFolderHint: String = ""
    ): AudiobookEntity? {
        val existingBooks = runCatching { dao.getAllAudiobooksOnce() }.getOrDefault(emptyList())
        if (existingBooks.isEmpty()) return null

        val candidateUriSet = candidateUris.filter { it.isNotBlank() }.toSet()
        val candidateSignatures = candidateUriSet.map { normalizeUriSignature(it) }.filter { it.isNotBlank() }.toSet()

        val candidateFolders = buildSet {
            if (canonicalFolderHint.isNotBlank()) add(canonicalFolderHint.lowercase().trim('/'))
            for (u in candidateUriSet) {
                val folder = StorageAudioScanner.normalizeCanonicalBookFolder("", "", runCatching { Uri.parse(u) }.getOrNull())
                if (folder.isNotBlank()) add(folder.lowercase().trim('/'))
            }
        }

        val normTitle = cleanedTitle.lowercase().trim()
        val normAuthor = cleanedAuthor.lowercase().trim()

        for (book in existingBooks) {
            if (book.isBuiltIn) continue
            // 1. Direct fileUri or normalized path match
            if (book.fileUri in candidateUriSet) return book
            val bookSig = normalizeUriSignature(book.fileUri)
            if (bookSig.isNotBlank() && bookSig in candidateSignatures) return book

            // 2. Check chapter file URIs of this book
            val bookChapters = runCatching { dao.getChaptersForBookOnce(book.id) }.getOrDefault(emptyList())
            for (ch in bookChapters) {
                val chUri = ch.fileUri ?: continue
                if (chUri in candidateUriSet) return book
                val chSig = normalizeUriSignature(chUri)
                if (chSig.isNotBlank() && chSig in candidateSignatures) return book
            }

            // 3. Canonical folder match (if non-generic folder)
            val bookFolder = StorageAudioScanner.normalizeCanonicalBookFolder(
                "",
                "",
                runCatching { Uri.parse(book.fileUri) }.getOrNull()
            ).lowercase().trim('/')
            if (bookFolder.isNotBlank() && bookFolder in candidateFolders) {
                return book
            }

            // 4. Cleaned Title + Author match
            val bookCleanAuthor = BookTitleCleaner.cleanAuthorName(book.author).lowercase().trim()
            val bookCleanTitle = BookTitleCleaner.cleanBookTitle(book.title, bookCleanAuthor).ifBlank { book.title }.lowercase().trim()
            if (normTitle.isNotBlank() && normTitle == bookCleanTitle) {
                val sameAuthor = normAuthor == bookCleanAuthor ||
                    normAuthor.isBlank() ||
                    bookCleanAuthor.isBlank() ||
                    normAuthor == "неизвестный автор" ||
                    bookCleanAuthor == "неизвестный автор"
                val similarDuration = durationMs <= 0L || book.durationMs <= 0L ||
                    kotlin.math.abs(durationMs - book.durationMs) <= maxOf(durationMs, book.durationMs) * 0.08
                if (sameAuthor && similarDuration) {
                    return book
                }
            }
        }
        return null
    }

    private fun extractCoverForUri(uri: Uri, keySeed: String): String? {
        // 1. Try MediaMetadataRetriever first
        runCatching {
            val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            try {
                MediaMetadataRetriever().use { retriever ->
                    if (pfd != null) {
                        retriever.setDataSource(pfd.fileDescriptor)
                    } else {
                        retriever.setDataSource(context, uri)
                    }
                    val picBytes = retriever.embeddedPicture
                    if (picBytes != null && picBytes.isNotEmpty() && picBytes.size <= 12 * 1024 * 1024) {
                        saveCoverBytes(picBytes, keySeed)?.let { return it }
                    }
                }
            } finally {
                pfd?.close()
            }
        }

        // 2. Try direct MP4/M4B/M4A covr + ID3v2 APIC/PIC parser
        runCatching {
            val mp4Meta = M4bChapterParser.extractMp4Metadata(context, uri, includeCover = true)
            if (mp4Meta.coverBytes != null && mp4Meta.coverBytes.isNotEmpty()) {
                saveCoverBytes(mp4Meta.coverBytes, keySeed)?.let { return it }
            }
        }

        // 3. If file:// URI, check sibling image files in the same folder
        if (uri.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                runCatching {
                    val parentDir = File(path).parentFile
                    if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                        val imgExts = setOf("jpg", "jpeg", "png", "webp")
                        val imgFiles = parentDir.listFiles { f ->
                            f.isFile && f.extension.lowercase() in imgExts && f.length() in 512L..(15L * 1024L * 1024L)
                        }
                        if (!imgFiles.isNullOrEmpty()) {
                            val priorityNames = listOf("cover", "folder", "front", "album", "обложка", "art")
                            val best = imgFiles.sortedWith(
                                compareByDescending<File> { f ->
                                    val n = f.nameWithoutExtension.lowercase()
                                    if (priorityNames.any { n.contains(it) }) 1 else 0
                                }.thenByDescending { it.length() }
                            ).first()
                            saveCoverBytes(best.readBytes(), keySeed)?.let { return it }
                        }
                    }
                }
            }
        }

        return null
    }

    private fun saveCoverBytes(bytes: ByteArray, keySeed: String): String? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val ext = when {
                bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
                bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() -> "webp"
                else -> "jpg"
            }
            val safeFile = File(coversDir, "cover_${keySeed.hashCode().and(0x7FFFFFFF)}.$ext")
            safeFile.writeBytes(bytes)
            safeFile.absolutePath
        }.getOrNull()
    }

    suspend fun importExternalAudiobook(
        title: String,
        author: String,
        format: String,
        uriString: String,
        durationMs: Long,
        bitrateKbps: Int,
        coverImagePath: String? = null
    ): Long {
        var resolvedTitle = title
        var resolvedAuthor = author
        var resolvedCoverPath = coverImagePath
        val uri = Uri.parse(uriString)

        // Supplement with direct MP4/M4B/M4A ilst & ID3v2 metadata parser
        val mp4Meta = M4bChapterParser.extractMp4Metadata(context, uri, includeCover = resolvedCoverPath == null)
        if (!mp4Meta.title.isNullOrBlank() && (resolvedTitle.isBlank() || resolvedTitle.startsWith("Аудиокнига"))) {
            resolvedTitle = mp4Meta.title
        }
        if (!mp4Meta.author.isNullOrBlank() && (resolvedAuthor.isBlank() || resolvedAuthor.contains("Локальный файл") || resolvedAuthor.startsWith("Папка:") || resolvedAuthor == "Неизвестный автор")) {
            resolvedAuthor = mp4Meta.author
        }
        if (resolvedCoverPath == null && mp4Meta.coverBytes != null && mp4Meta.coverBytes.isNotEmpty()) {
            resolvedCoverPath = saveCoverBytes(mp4Meta.coverBytes, "${uriString}_$resolvedTitle")
        }
        if (resolvedCoverPath == null) {
            resolvedCoverPath = extractCoverForUri(uri, "${uriString}_$resolvedTitle")
        }

        val cleanedAuthor = BookTitleCleaner.cleanAuthorName(resolvedAuthor)
        val cleanedTitle = BookTitleCleaner.cleanBookTitle(resolvedTitle, cleanedAuthor).ifBlank { resolvedTitle }

        // Prevent duplicate book insertion if already present in library
        val duplicate = findExistingDuplicateBook(
            candidateUris = listOf(uriString),
            cleanedTitle = cleanedTitle,
            cleanedAuthor = cleanedAuthor,
            durationMs = durationMs
        )
        if (duplicate != null) {
            return -duplicate.id
        }

        val coverStyle = (cleanedTitle.hashCode().and(0x7FFFFFFF)) % 5
        val bookId = dao.insertAudiobook(
            AudiobookEntity(
                title = cleanedTitle,
                author = cleanedAuthor,
                narrator = "",
                description = "Импортированная пользователем аудиокнига в формате $format ($bitrateKbps кбит/с).",
                audioFormat = format,
                fileUri = uriString,
                isBuiltIn = false,
                coverArtStyle = coverStyle,
                coverImagePath = resolvedCoverPath,
                durationMs = durationMs,
                currentPositionMs = 0L,
                playbackSpeed = 1.0f,
                isFavorite = false,
                lastPlayedTimestamp = System.currentTimeMillis(),
                bitrateKbps = bitrateKbps,
                sampleRateHz = 44100
            )
        )

        val chapters = M4bChapterParser.parseChapters(
            context = context,
            audiobookId = bookId,
            uri = Uri.parse(uriString),
            totalDurationMs = durationMs,
            audioFormat = format
        ).map { it.copy(fileUri = uriString) }
        dao.insertChapters(chapters)
        return bookId
    }

    suspend fun importScannedBookGroup(group: ScannedBookGroup): Long {
        if (group.files.isEmpty()) return -1L

        var resolvedCoverPath = group.coverImagePath
        if (resolvedCoverPath == null) {
            val firstWithCover = group.files.firstOrNull { !it.coverImagePath.isNullOrBlank() }
            resolvedCoverPath = firstWithCover?.coverImagePath
        }
        if (resolvedCoverPath == null) {
            for (file in group.files.take(6)) {
                val extracted = extractCoverForUri(file.uri, "${group.groupId}_${file.uri}")
                if (!extracted.isNullOrBlank()) {
                    resolvedCoverPath = extracted
                    break
                }
            }
        }

        val cleanedAuthor = BookTitleCleaner.cleanAuthorName(group.author)
        val cleanedTitle = BookTitleCleaner.cleanBookTitle(group.title, cleanedAuthor).ifBlank { group.title }

        // Prevent duplicate book group insertion if already present in library
        val duplicate = findExistingDuplicateBook(
            candidateUris = group.files.map { it.uri.toString() },
            cleanedTitle = cleanedTitle,
            cleanedAuthor = cleanedAuthor,
            durationMs = group.totalDurationMs,
            canonicalFolderHint = group.relativePath.ifBlank { group.folderName }
        )
        if (duplicate != null) {
            return -duplicate.id
        }

        val primaryFormat = group.formats.firstOrNull() ?: "MP3"
        val averageBitrate = (group.files.map { it.bitrateKbps }.average()).toInt().coerceIn(64, 320)
        val coverStyle = (cleanedTitle.hashCode().and(0x7FFFFFFF)) % 5
        val firstFile = group.files.first()

        val description = if (group.isMultiFile) {
            "Аудиокнига из ${group.fileCount} частей/файлов в формате $primaryFormat (${formatDurationVerbose(group.totalDurationMs)})."
        } else {
            "Импортированная аудиокнига в формате $primaryFormat ($averageBitrate кбит/с)."
        }

        val bookId = dao.insertAudiobook(
            AudiobookEntity(
                title = cleanedTitle,
                author = cleanedAuthor,
                narrator = "",
                description = description,
                audioFormat = primaryFormat,
                fileUri = firstFile.uri.toString(),
                isBuiltIn = false,
                coverArtStyle = coverStyle,
                coverImagePath = resolvedCoverPath,
                durationMs = group.totalDurationMs,
                currentPositionMs = 0L,
                playbackSpeed = 1.0f,
                isFavorite = false,
                lastPlayedTimestamp = System.currentTimeMillis(),
                bitrateKbps = averageBitrate,
                sampleRateHz = 44100
            )
        )

        if (group.isMultiFile) {
            var cumulativeOffset = 0L
            var chapterIndex = 0
            val allChapters = mutableListOf<ChapterEntity>()

            for ((fileIndex, file) in group.files.withIndex()) {
                val embeddedChapters = M4bChapterParser.extractChaptersFromFile(
                    context = context,
                    uri = file.uri,
                    fileDurationMs = file.durationMs,
                    audioFormat = file.format
                )

                val fileNameNoExt = file.displayName.substringBeforeLast('.')
                val cleanedFileName = fileNameNoExt.replace('_', ' ').replace('-', ' ').trim()
                val isTitleStillFilename = file.title.isBlank() ||
                    file.title.equals(fileNameNoExt, ignoreCase = true) ||
                    file.title.equals(cleanedFileName, ignoreCase = true)

                val mp4Meta = if (isTitleStillFilename || embeddedChapters.size == 1) {
                    M4bChapterParser.extractMp4Metadata(context, file.uri, includeCover = false)
                } else null

                val bestTagTitle = mp4Meta?.title?.takeIf { it.isNotBlank() } ?: file.title.trim()
                val bestTrackNumber = mp4Meta?.trackNumber?.takeIf { it > 0 } ?: file.trackNumber

                if (embeddedChapters.size > 1 ||
                    (embeddedChapters.size == 1 && !embeddedChapters[0].title.matches(Regex("""^Глава\s+\d+$""")))
                ) {
                    for (ch in embeddedChapters) {
                        allChapters.add(
                            ChapterEntity(
                                audiobookId = bookId,
                                chapterIndex = chapterIndex++,
                                title = ch.title,
                                startTimeMs = cumulativeOffset + ch.startTimeMs,
                                durationMs = ch.durationMs,
                                fileUri = file.uri.toString()
                            )
                        )
                    }
                } else {
                    val resolvedTitle = resolveChapterTitleFromTags(
                        fileTagTitle = bestTagTitle,
                        albumOrBookTitle = group.title,
                        trackNumber = bestTrackNumber,
                        displayName = file.displayName,
                        fileIndex = fileIndex
                    )

                    allChapters.add(
                        ChapterEntity(
                            audiobookId = bookId,
                            chapterIndex = chapterIndex++,
                            title = resolvedTitle,
                            startTimeMs = cumulativeOffset,
                            durationMs = file.durationMs,
                            fileUri = file.uri.toString()
                        )
                    )
                }
                cumulativeOffset += file.durationMs
            }
            dao.insertChapters(allChapters)
        } else {
            val chapters = M4bChapterParser.parseChapters(
                context = context,
                audiobookId = bookId,
                uri = firstFile.uri,
                totalDurationMs = group.totalDurationMs,
                audioFormat = primaryFormat
            ).map { it.copy(fileUri = firstFile.uri.toString()) }
            dao.insertChapters(chapters)
        }

        return bookId
    }

    private fun resolveChapterTitleFromTags(
        fileTagTitle: String,
        albumOrBookTitle: String,
        trackNumber: Int,
        displayName: String,
        fileIndex: Int
    ): String {
        val fileNameNoExt = displayName.substringBeforeLast('.')
        val cleanedFileName = fileNameNoExt.replace('_', ' ').replace('-', ' ').trim()

        // Prefer real ID3 / MP4 tag title whenever it is not just the raw filename or the book title
        if (fileTagTitle.isNotBlank() &&
            !fileTagTitle.equals(albumOrBookTitle, ignoreCase = true) &&
            !fileTagTitle.equals(fileNameNoExt, ignoreCase = true) &&
            !fileTagTitle.equals(displayName, ignoreCase = true)
        ) {
            return cleanChapterTitle(fileTagTitle, fileIndex + 1)
        }

        if (cleanedFileName.isNotBlank() && !cleanedFileName.equals(albumOrBookTitle, ignoreCase = true)) {
            return cleanChapterTitle(cleanedFileName, fileIndex + 1)
        }

        if (trackNumber > 0) {
            return "Глава $trackNumber"
        }

        return "Глава ${fileIndex + 1}"
    }

    private fun cleanChapterTitle(raw: String, fallbackIndex: Int): String {
        val s = raw.trim()
        val leadingTrackPattern = Regex("""^(\d{1,3})\s*[-–._]\s*(.*)$""")
        val match = leadingTrackPattern.find(s)
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull() ?: fallbackIndex
            val remainder = match.groupValues[2].trim()
            return if (remainder.isNotBlank()) {
                if (remainder.startsWith("Глава", ignoreCase = true) || remainder.startsWith("Chapter", ignoreCase = true)) {
                    remainder
                } else {
                    "Глава $num. $remainder"
                }
            } else {
                "Глава $num"
            }
        }
        return s.ifBlank { "Глава $fallbackIndex" }
    }

    suspend fun refreshChaptersFromTags(book: AudiobookEntity): List<ChapterEntity> {
        if (book.isBuiltIn || book.fileUri.isBlank()) {
            return getChaptersForBookOnce(book.id)
        }
        val currentChapters = getChaptersForBookOnce(book.id)
        val distinctUris = currentChapters.mapNotNull { it.fileUri }.distinct()

        val newChapters = mutableListOf<ChapterEntity>()
        if (distinctUris.size > 1) {
            var cumulativeOffset = 0L
            var chapterIndex = 0
            for ((fileIndex, uriStr) in distinctUris.withIndex()) {
                val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: continue
                val fileChapters = currentChapters.filter { it.fileUri == uriStr }
                val fileDuration = fileChapters.sumOf { it.durationMs }.takeIf { it > 0 } ?: (book.durationMs / distinctUris.size)

                val embedded = M4bChapterParser.extractChaptersFromFile(
                    context = context,
                    uri = uri,
                    fileDurationMs = fileDuration,
                    audioFormat = book.audioFormat
                )

                if (embedded.size > 1 ||
                    (embedded.size == 1 && !embedded[0].title.matches(Regex("""^Глава\s+\d+$""")))
                ) {
                    for (ch in embedded) {
                        newChapters.add(
                            ChapterEntity(
                                audiobookId = book.id,
                                chapterIndex = chapterIndex++,
                                title = ch.title,
                                startTimeMs = cumulativeOffset + ch.startTimeMs,
                                durationMs = ch.durationMs,
                                fileUri = uriStr
                            )
                        )
                    }
                } else {
                    var tagTitle: String? = null
                    var trackNum = fileIndex + 1

                    // 1. Check custom M4B/MP4/ID3v2 parser first
                    runCatching {
                        val mp4Meta = M4bChapterParser.extractMp4Metadata(context, uri, includeCover = false)
                        if (!mp4Meta.title.isNullOrBlank()) {
                            tagTitle = mp4Meta.title.trim()
                        }
                        if (mp4Meta.trackNumber != null && mp4Meta.trackNumber > 0) {
                            trackNum = mp4Meta.trackNumber
                        }
                    }

                    // 2. Fallback to MediaMetadataRetriever if custom parser didn't find a title
                    if (tagTitle.isNullOrBlank()) {
                        runCatching {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, uri)
                                tagTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                                    ?.substringBefore('/')?.toIntOrNull()?.let { trackNum = it }
                            } finally {
                                retriever.release()
                            }
                        }
                    }

                    val displayName = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/')
                    val resolvedTitle = resolveChapterTitleFromTags(
                        fileTagTitle = tagTitle.orEmpty(),
                        albumOrBookTitle = book.title,
                        trackNumber = trackNum,
                        displayName = displayName,
                        fileIndex = fileIndex
                    )

                    newChapters.add(
                        ChapterEntity(
                            audiobookId = book.id,
                            chapterIndex = chapterIndex++,
                            title = resolvedTitle,
                            startTimeMs = cumulativeOffset,
                            durationMs = fileDuration,
                            fileUri = uriStr
                        )
                    )
                }
                cumulativeOffset += fileDuration
            }
        } else {
            val uri = Uri.parse(book.fileUri)
            val parsed = M4bChapterParser.parseChapters(
                context = context,
                audiobookId = book.id,
                uri = uri,
                totalDurationMs = book.durationMs,
                audioFormat = book.audioFormat
            ).map { it.copy(fileUri = book.fileUri) }
            newChapters.addAll(parsed)
        }

        if (newChapters.isNotEmpty()) {
            dao.deleteChaptersForBook(book.id)
            dao.insertChapters(newChapters)
            return newChapters
        }
        return currentChapters
    }

    suspend fun importScannedAudioFiles(files: List<ScannedAudioFile>): List<Long> {
        val groups = StorageAudioScanner.groupScannedFiles(files)
        val importedIds = mutableListOf<Long>()
        for (grp in groups) {
            val id = importScannedBookGroup(grp)
            if (id > 0L) {
                importedIds.add(id)
            }
        }
        return importedIds
    }

    suspend fun seedInitialLibraryIfNeeded() {
        // Automatically purge any previously seeded test audiobooks from database per user request
        dao.deleteBuiltInAudiobooks()
    }

    suspend fun purgeSampleAudiobooks() {
        dao.deleteBuiltInAudiobooks()
    }

    suspend fun refreshLibraryCoversAndTitles() {
        runCatching { consolidateSplitFolderBooksInLibrary() }

        val books = runCatching { dao.getAllAudiobooksOnce() }.getOrDefault(emptyList())
        for (book in books) {
            if (book.isBuiltIn) continue

            val uriObj = runCatching { Uri.parse(book.fileUri) }.getOrNull()
            val folderKey = StorageAudioScanner.normalizeCanonicalBookFolder("", "", uriObj)
            val (folderAuthor, folderTitle) = if (folderKey.isNotBlank()) {
                StorageAudioScanner.extractFolderAuthorAndTitle(folderKey)
            } else {
                null to null
            }

            val rawAuthor = if (
                (book.author.isBlank() || book.author == "Неизвестный автор" || book.author.startsWith("Папка:") || book.author.startsWith("Аудиофайл")) &&
                !folderAuthor.isNullOrBlank()
            ) {
                folderAuthor
            } else {
                book.author
            }
            val cleanedAuthor = BookTitleCleaner.cleanAuthorName(rawAuthor)

            val rawTitle = if (!folderTitle.isNullOrBlank() && (
                book.title.matches(Regex("""^\d{1,4}.*""")) ||
                    book.title.startsWith("Глава", ignoreCase = true) ||
                    book.title.startsWith("Часть", ignoreCase = true) ||
                    book.title.startsWith("Track", ignoreCase = true)
                )
            ) {
                folderTitle
            } else {
                book.title
            }
            val cleanedTitle = BookTitleCleaner.cleanBookTitle(rawTitle, cleanedAuthor).ifBlank { book.title }

            var coverPath = book.coverImagePath
            val coverExists = !coverPath.isNullOrBlank() && runCatching { File(coverPath).exists() && File(coverPath).length() > 128L }.getOrDefault(false)
            if (!coverExists) {
                coverPath = null
                if (book.fileUri.isNotBlank()) {
                    coverPath = extractCoverForUri(Uri.parse(book.fileUri), "book_${book.id}_${book.fileUri}")
                }
                if (coverPath == null) {
                    val chapters = runCatching { dao.getChaptersForBookOnce(book.id) }.getOrDefault(emptyList())
                    val distinctUris = chapters.mapNotNull { it.fileUri }.distinct().take(6)
                    for (uStr in distinctUris) {
                        val extracted = extractCoverForUri(Uri.parse(uStr), "book_${book.id}_$uStr")
                        if (!extracted.isNullOrBlank()) {
                            coverPath = extracted
                            break
                        }
                    }
                }
            }

            if (cleanedTitle != book.title ||
                cleanedAuthor != book.author ||
                book.narrator.isNotEmpty() ||
                coverPath != book.coverImagePath
            ) {
                dao.updateBookMetadataAndCover(
                    id = book.id,
                    title = cleanedTitle,
                    author = cleanedAuthor,
                    coverPath = coverPath
                )
            }
        }

        runCatching { deduplicateLibraryBooks() }
    }

    /**
     * Removes duplicate AudiobookEntity entries in the database that point to the same audio file,
     * canonical folder, or identical cleaned title + author, keeping the entry with the highest progress/activity.
     */
    private suspend fun deduplicateLibraryBooks() {
        val books = dao.getAllAudiobooksOnce().filter { !it.isBuiltIn }
        if (books.size < 2) return

        val seenKeys = mutableMapOf<String, AudiobookEntity>()
        for (book in books) {
            val cleanAuthor = BookTitleCleaner.cleanAuthorName(book.author).lowercase().trim()
            val cleanTitle = BookTitleCleaner.cleanBookTitle(book.title, cleanAuthor).ifBlank { book.title }.lowercase().trim()
            val uriSig = normalizeUriSignature(book.fileUri)
            val key = if (cleanTitle.isNotBlank()) {
                "title:${cleanTitle}__author:$cleanAuthor"
            } else {
                "uri:$uriSig"
            }

            val existing = seenKeys[key]
            if (existing == null) {
                seenKeys[key] = book
            } else {
                // Keep the book that has more listening progress or is marked favorite
                val keepBook = if (book.currentPositionMs > existing.currentPositionMs || (book.isFavorite && !existing.isFavorite)) {
                    book
                } else {
                    existing
                }
                val removeBook = if (keepBook.id == book.id) existing else book
                seenKeys[key] = keepBook
                dao.deleteBookmarksForBook(removeBook.id)
                dao.deleteChaptersForBook(removeBook.id)
                dao.deleteAudiobook(removeBook.id)
            }
        }
    }

    /**
     * Merges multiple AudiobookEntity rows in the database that belong to the same canonical book folder
     * (e.g. untagged MP3 files from the same folder that were previously imported as separate books).
     */
    private suspend fun consolidateSplitFolderBooksInLibrary() {
        val allBooks = dao.getAllAudiobooksOnce().filter { !it.isBuiltIn && it.fileUri.isNotBlank() }
        if (allBooks.size < 2) return

        val byFolder = allBooks.mapNotNull { book ->
            val authorFolderHint = when {
                book.author.startsWith("Папка:") -> book.author.removePrefix("Папка:").trim()
                book.author.startsWith("Аудиофайл (") -> book.author.removePrefix("Аудиофайл (").removeSuffix(")").trim()
                else -> ""
            }
            val folderKey = StorageAudioScanner.normalizeCanonicalBookFolder(
                rawRelativePath = authorFolderHint,
                fallbackFolderName = "",
                fileUri = runCatching { Uri.parse(book.fileUri) }.getOrNull()
            )
            if (folderKey.isNotBlank()) folderKey to book else null
        }.groupBy({ it.first.lowercase() }, { it.first to it.second })

        for ((_, pairs) in byFolder) {
            if (pairs.size <= 1) continue
            val canonicalFolder = pairs.first().first
            val sortedBooks = pairs.map { it.second }.sortedBy { b ->
                val decoded = runCatching { Uri.decode(b.fileUri) }.getOrDefault(b.fileUri)
                StorageAudioScanner.naturalOrderKey(decoded)
            }

            val primary = sortedBooks.first()
            val (folderAuthor, folderBookTitle) = StorageAudioScanner.extractFolderAuthorAndTitle(canonicalFolder)

            val bestAuthor = BookTitleCleaner.cleanAuthorName(
                folderAuthor?.takeIf { it.isNotBlank() && it != "Неизвестный автор" }
                    ?: sortedBooks.firstNotNullOfOrNull { b ->
                        b.author.takeIf {
                            it.isNotBlank() &&
                                it != "Неизвестный автор" &&
                                !it.startsWith("Папка:") &&
                                !it.startsWith("Аудиофайл (")
                        }
                    }
                    ?: primary.author
            )
            val rawMergedTitle = folderBookTitle?.takeIf { it.isNotBlank() } ?: primary.title
            val bestTitle = BookTitleCleaner.cleanBookTitle(rawMergedTitle, bestAuthor).ifBlank { rawMergedTitle }

            var cumulativeMs = 0L
            var chapterIdx = 0
            val mergedChapters = mutableListOf<ChapterEntity>()

            for ((bookIdx, b) in sortedBooks.withIndex()) {
                val existingChapters = dao.getChaptersForBookOnce(b.id)
                if (existingChapters.size > 1) {
                    for (ch in existingChapters) {
                        val dur = ch.durationMs.coerceAtLeast(1000L)
                        mergedChapters.add(
                            ChapterEntity(
                                audiobookId = primary.id,
                                chapterIndex = chapterIdx++,
                                title = ch.title,
                                startTimeMs = cumulativeMs,
                                durationMs = dur,
                                fileUri = ch.fileUri ?: b.fileUri
                            )
                        )
                        cumulativeMs += dur
                    }
                } else {
                    val dur = (existingChapters.firstOrNull()?.durationMs ?: b.durationMs).coerceAtLeast(1000L)
                    val decodedName = runCatching { Uri.decode(b.fileUri) }.getOrDefault(b.fileUri)
                        .substringAfterLast('/')
                        .substringBeforeLast('.')
                    val chTitle = resolveChapterTitleFromTags(
                        fileTagTitle = b.title,
                        albumOrBookTitle = bestTitle,
                        trackNumber = bookIdx + 1,
                        displayName = decodedName,
                        fileIndex = bookIdx
                    )
                    mergedChapters.add(
                        ChapterEntity(
                            audiobookId = primary.id,
                            chapterIndex = chapterIdx++,
                            title = chTitle,
                            startTimeMs = cumulativeMs,
                            durationMs = dur,
                            fileUri = b.fileUri
                        )
                    )
                    cumulativeMs += dur
                }
            }

            val bestCover = sortedBooks.firstNotNullOfOrNull { b ->
                b.coverImagePath?.takeIf { runCatching { File(it).exists() }.getOrDefault(false) }
            } ?: primary.coverImagePath

            val updatedPrimary = primary.copy(
                title = bestTitle,
                author = bestAuthor,
                narrator = "",
                coverImagePath = bestCover,
                durationMs = cumulativeMs.coerceAtLeast(primary.durationMs),
                lastPlayedTimestamp = sortedBooks.maxOf { it.lastPlayedTimestamp },
                isFavorite = sortedBooks.any { it.isFavorite }
            )

            dao.updateAudiobook(updatedPrimary)
            dao.deleteChaptersForBook(primary.id)
            dao.insertChapters(mergedChapters)

            for (other in sortedBooks.drop(1)) {
                dao.deleteChaptersForBook(other.id)
                dao.deleteAudiobook(other.id)
            }
        }
    }
}

fun formatDurationShort(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

fun formatDurationVerbose(ms: Long): String {
    val totalMinutes = (ms.coerceAtLeast(0L)) / 60_000L
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return if (hours > 0) {
        "${hours} ч ${mins} мин"
    } else {
        "${mins.coerceAtLeast(1)} мин"
    }
}
