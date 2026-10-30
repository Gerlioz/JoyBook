package com.example.data.storage

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.audio.M4bChapterParser

data class ScannedAudioFile(
    val uri: Uri,
    val title: String,
    val author: String,
    val album: String = "",
    val trackNumber: Int = 0,
    val displayName: String,
    val folderName: String,
    val relativePath: String,
    val format: String, // MP3, M4A, M4B, AMR, WAV
    val durationMs: Long,
    val sizeBytes: Long,
    val bitrateKbps: Int,
    val dateModifiedMs: Long,
    val isAlreadyInLibrary: Boolean = false,
    val coverImagePath: String? = null
)

data class ScannedBookGroup(
    val groupId: String,
    val title: String,
    val author: String,
    val folderName: String,
    val relativePath: String,
    val files: List<ScannedAudioFile>,
    val totalDurationMs: Long,
    val totalSizeBytes: Long,
    val formats: List<String>,
    val coverImagePath: String?,
    val isAlreadyInLibrary: Boolean = false
) {
    val fileCount: Int get() = files.size
    val isMultiFile: Boolean get() = files.size > 1
}

object StorageAudioScanner {

    private val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "m4b", "amr", "wav")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    private data class SafChildEntry(
        val docId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val lastModified: Long
    )

    /**
     * Scans the device's internal and external MediaStore audio storage for audiobooks and audio files.
     */
    suspend fun scanDeviceMediaStore(
        context: Context,
        existingUris: Set<String>,
        query: String = "",
        formatFilter: String = "ALL",
        minDurationMs: Long = 0L
    ): List<ScannedAudioFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScannedAudioFile>()
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (hasRelativePath) {
            projection.add(MediaStore.Audio.Media.RELATIVE_PATH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
        }
        @Suppress("DEPRECATION")
        projection.add(MediaStore.Audio.Media.DATA)

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
        val folderCoverCache = mutableMapOf<String, String?>()

        try {
            context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val relPathCol = if (hasRelativePath) cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) else -1
                val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                } else -1
                @Suppress("DEPRECATION")
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                var mediaStoreRetrieverCount = 0
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0 && !cursor.isNull(idCol)) cursor.getLong(idCol) else continue
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val displayName = (if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol) else null) ?: "audio_$id"
                    val ext = displayName.substringAfterLast('.', "").lowercase()

                    val mimeType = (if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else null)?.lowercase() ?: ""
                    val isSupportedFormat = ext in SUPPORTED_EXTENSIONS ||
                        mimeType.contains("mpeg") || mimeType.contains("mp3") ||
                        mimeType.contains("mp4") || mimeType.contains("m4a") ||
                        mimeType.contains("m4b") || mimeType.contains("amr") ||
                        mimeType.contains("wav")

                    if (!isSupportedFormat) continue

                    val format = detectAudioFormat(displayName, mimeType)
                    if (formatFilter != "ALL" && !format.equals(formatFilter, ignoreCase = true)) {
                        continue
                    }

                    var durationMs = if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol) else 0L
                    val sizeBytes = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                    val dateModified = (if (dateCol >= 0 && !cursor.isNull(dateCol)) cursor.getLong(dateCol) else 0L) * 1000L

                    var rawTitle = (if (titleCol >= 0 && !cursor.isNull(titleCol)) cursor.getString(titleCol) else null) ?: ""
                    var rawArtist = (if (artistCol >= 0 && !cursor.isNull(artistCol)) cursor.getString(artistCol) else null) ?: ""
                    var rawAlbum = (if (albumCol >= 0 && !cursor.isNull(albumCol)) cursor.getString(albumCol) else null) ?: ""
                    val albumId = if (albumIdCol >= 0 && !cursor.isNull(albumIdCol)) cursor.getLong(albumIdCol) else -1L
                    val fileDataPath = if (dataCol >= 0 && !cursor.isNull(dataCol)) cursor.getString(dataCol) ?: "" else ""
                    val bucketName = if (bucketCol >= 0 && !cursor.isNull(bucketCol)) cursor.getString(bucketCol) ?: "" else ""

                    val rawRelPath = when {
                        relPathCol >= 0 && !cursor.isNull(relPathCol) -> cursor.getString(relPathCol) ?: ""
                        fileDataPath.isNotBlank() -> extractRelativePathFromFilesystemPath(fileDataPath)
                        else -> ""
                    }

                    val relativePath = normalizeCanonicalBookFolder(rawRelPath, bucketName)
                    val folderName = relativePath.trim('/').split('/').lastOrNull()?.ifBlank {
                        bucketName.ifBlank { "Память телефона" }
                    } ?: bucketName.ifBlank { "Память телефона" }

                    // Limit fallback MediaMetadataRetriever usage during full MediaStore scan to avoid ANR/OOM
                    var bitrateKbps = 128
                    if (durationMs <= 0L && mediaStoreRetrieverCount < 40) {
                        mediaStoreRetrieverCount++
                        runCatching {
                            MediaMetadataRetriever().use { retriever ->
                                retriever.setDataSource(context, contentUri)
                                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                                if (dur != null && dur > 0L) {
                                    durationMs = dur
                                }
                                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                if (!metaTitle.isNullOrBlank()) rawTitle = metaTitle
                                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                                if (!metaArtist.isNullOrBlank()) rawArtist = metaArtist
                                val metaBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                                if (metaBitrate != null && metaBitrate > 0) {
                                    bitrateKbps = (metaBitrate / 1000).coerceIn(12, 1411)
                                }
                            }
                        }
                    }

                    if (durationMs < minDurationMs) continue

                    val rawFileBase = displayName.substringBeforeLast('.')
                    val cleanedFileBase = rawFileBase
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .trim()

                    var cleanTitle = if (rawTitle.isNotBlank() &&
                        !rawTitle.equals(displayName, ignoreCase = true) &&
                        !rawTitle.equals(rawFileBase, ignoreCase = true)
                    ) {
                        rawTitle
                    } else {
                        cleanedFileBase
                    }

                    var cleanAuthor = if (rawArtist.isNotBlank() && rawArtist != "<unknown>") {
                        rawArtist
                    } else {
                        "Аудиофайл ($folderName)"
                    }

                    val folderKey = relativePath.trim('/').ifBlank { folderName }
                    val needFolderCover = !folderCoverCache.containsKey(folderKey)
                    var resolvedCoverPath: String? = folderCoverCache[folderKey]

                    if ((cleanTitle == cleanedFileBase || needFolderCover) && format in listOf("M4B", "M4A", "MP3")) {
                        runCatching {
                            val mp4Meta = M4bChapterParser.extractMp4Metadata(
                                context = context,
                                uri = contentUri,
                                includeCover = needFolderCover
                            )
                            if (!mp4Meta.title.isNullOrBlank()) {
                                cleanTitle = mp4Meta.title.trim()
                            }
                            if (!mp4Meta.author.isNullOrBlank() && cleanAuthor.startsWith("Аудиофайл")) {
                                cleanAuthor = mp4Meta.author.trim()
                            }
                            if (!mp4Meta.album.isNullOrBlank() && rawAlbum.isBlank()) {
                                rawAlbum = mp4Meta.album.trim()
                            }
                            if (needFolderCover && mp4Meta.coverBytes != null && mp4Meta.coverBytes.isNotEmpty()) {
                                resolvedCoverPath = saveCoverBytesToPermanentDir(
                                    context = context,
                                    bytes = mp4Meta.coverBytes,
                                    key = "ms_${folderKey.hashCode().and(0x7FFFFFFF)}_$id"
                                )
                            }
                        }
                    }

                    if (needFolderCover && resolvedCoverPath == null) {
                        resolvedCoverPath = extractCoverFromMediaStoreOrParentDir(
                            context = context,
                            contentUri = contentUri,
                            albumId = albumId,
                            fileDataPath = fileDataPath,
                            key = "ms_${folderKey.hashCode().and(0x7FFFFFFF)}_$id"
                        )
                    }
                    if (needFolderCover) {
                        folderCoverCache[folderKey] = resolvedCoverPath
                    }

                    // Query search filter
                    if (query.isNotBlank()) {
                        val matchesQuery = cleanTitle.contains(query, ignoreCase = true) ||
                            cleanAuthor.contains(query, ignoreCase = true) ||
                            displayName.contains(query, ignoreCase = true) ||
                            folderName.contains(query, ignoreCase = true)
                        if (!matchesQuery) continue
                    }

                    val isAlreadyImported = existingUris.contains(contentUri.toString()) ||
                        existingUris.any { it.contains(displayName, ignoreCase = true) }

                    results.add(
                        ScannedAudioFile(
                            uri = contentUri,
                            title = cleanTitle,
                            author = cleanAuthor,
                            album = rawAlbum,
                            displayName = displayName,
                            folderName = folderName,
                            relativePath = relativePath,
                            format = format,
                            durationMs = durationMs.coerceAtLeast(1000L),
                            sizeBytes = sizeBytes,
                            bitrateKbps = bitrateKbps,
                            dateModifiedMs = dateModified,
                            isAlreadyInLibrary = isAlreadyImported,
                            coverImagePath = resolvedCoverPath
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        results
    }

    /**
     * Recursively scans a selected folder from the Storage Access Framework (SAF).
     */
    suspend fun scanDocumentTree(
        context: Context,
        treeUri: Uri,
        existingUris: Set<String>,
        query: String = "",
        formatFilter: String = "ALL",
        minDurationMs: Long = 0L
    ): List<ScannedAudioFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScannedAudioFile>()
        try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val dirUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val rootFolderName = getFolderName(context, treeUri)

            scanDirectoryRecursive(
                context = context,
                treeUri = treeUri,
                currentDirUri = dirUri,
                currentPath = "",
                rootFolderName = rootFolderName,
                inheritedCoverPath = null,
                existingUris = existingUris,
                query = query,
                formatFilter = formatFilter,
                minDurationMs = minDurationMs,
                outList = results
            )
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        results
    }

    private fun scanDirectoryRecursive(
        context: Context,
        treeUri: Uri,
        currentDirUri: Uri,
        currentPath: String,
        rootFolderName: String,
        inheritedCoverPath: String?,
        existingUris: Set<String>,
        query: String,
        formatFilter: String,
        minDurationMs: Long,
        outList: MutableList<ScannedAudioFile>
    ) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        try {
            val subDirs = mutableListOf<SafChildEntry>()
            val imageEntries = mutableListOf<SafChildEntry>()
            val audioEntries = mutableListOf<SafChildEntry>()

            context.contentResolver.query(currentDirUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                if (idCol < 0) return

                while (cursor.moveToNext()) {
                    val childDocId = (if (!cursor.isNull(idCol)) cursor.getString(idCol) else null) ?: continue
                    val displayName = (if (nameCol >= 0 && !cursor.isNull(nameCol)) cursor.getString(nameCol) else null) ?: ""
                    val mimeType = (if (mimeCol >= 0 && !cursor.isNull(mimeCol)) cursor.getString(mimeCol) else null) ?: ""
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                    val lastMod = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L

                    val entry = SafChildEntry(childDocId, displayName, mimeType, size, lastMod)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subDirs.add(entry)
                    } else {
                        val ext = displayName.substringAfterLast('.', "").lowercase()
                        if (ext in SUPPORTED_EXTENSIONS || mimeType.startsWith("audio/")) {
                            audioEntries.add(entry)
                        } else if (ext in IMAGE_EXTENSIONS || mimeType.startsWith("image/")) {
                            imageEntries.add(entry)
                        }
                    }
                }
            }

            // 1. Check if this folder has a standalone cover image file (e.g. cover.jpg, folder.jpg, front.png, *.jpg)
            var folderCoverPath: String? = findAndCacheFolderImage(context, treeUri, imageEntries) ?: inheritedCoverPath
            var folderCachedAuthor: String? = null
            var folderCachedAlbum: String? = null
            var folderCachedBitrate = 128
            var audioFilesInDirCount = 0
            val folderAddedStartIdx = outList.size

            // 2. Process audio files in this directory
            for (entry in audioEntries) {
                val childDocId = entry.docId
                val displayName = entry.displayName
                val mimeType = entry.mimeType
                val size = entry.size
                val lastMod = entry.lastModified

                val ext = displayName.substringAfterLast('.', "").lowercase()
                val format = detectAudioFormat(displayName, mimeType)
                if (formatFilter != "ALL" && !format.equals(formatFilter, ignoreCase = true)) {
                    continue
                }

                val fileUri = runCatching {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                }.getOrNull() ?: continue

                audioFilesInDirCount++

                val fileNameNoExt = displayName.substringBeforeLast('.')
                val fallbackTitle = fileNameNoExt.replace('_', ' ').replace('-', ' ').trim()
                var title = fallbackTitle
                var hasRealTagTitle = false
                var author = folderCachedAuthor ?: "Папка: ${currentPath.ifBlank { "Корневая" }}"
                var album = folderCachedAlbum ?: ""
                var trackNumber = 0
                var durationMs = 0L
                var bitrateKbps = folderCachedBitrate
                var coverPath: String? = folderCoverPath

                // Keep trying to extract cover for up to 8 files in the folder until a cover is found
                val needCover = (coverPath == null && audioFilesInDirCount <= 8) || currentPath.isBlank()

                // 2a. Fast M4B/M4A/ID3v2 tag parser on every file in the folder
                runCatching {
                    val mp4Meta = M4bChapterParser.extractMp4Metadata(
                        context = context,
                        uri = fileUri,
                        includeCover = needCover
                    )
                    if (!mp4Meta.title.isNullOrBlank()) {
                        title = mp4Meta.title.trim()
                        hasRealTagTitle = true
                    }
                    if (!mp4Meta.author.isNullOrBlank()) {
                        author = mp4Meta.author.trim()
                        if (folderCachedAuthor == null) folderCachedAuthor = author
                    }
                    if (!mp4Meta.album.isNullOrBlank()) {
                        album = mp4Meta.album.trim()
                        if (folderCachedAlbum == null) folderCachedAlbum = album
                    }
                    if (mp4Meta.trackNumber != null && mp4Meta.trackNumber > 0) {
                        trackNumber = mp4Meta.trackNumber
                    }
                    if ((mp4Meta.durationMs ?: 0L) > 0L) {
                        durationMs = mp4Meta.durationMs!!
                    }
                    if (needCover && coverPath == null && mp4Meta.coverBytes != null && mp4Meta.coverBytes.isNotEmpty()) {
                        coverPath = saveCoverBytesToPermanentDir(
                            context = context,
                            bytes = mp4Meta.coverBytes,
                            key = "saf_${childDocId.hashCode().and(0x7FFFFFFF)}"
                        )
                        if (folderCoverPath == null && coverPath != null) {
                            folderCoverPath = coverPath
                        }
                    }
                }

                // 2b. Inspect MediaMetadataRetriever on up to 8 files per folder (or when cover/duration is still needed)
                val shouldInspectRetriever = audioFilesInDirCount <= 8 && (!hasRealTagTitle || durationMs <= 0L || (needCover && coverPath == null))
                if (shouldInspectRetriever) {
                    runCatching {
                        val pfd = runCatching { context.contentResolver.openFileDescriptor(fileUri, "r") }.getOrNull()
                        try {
                            MediaMetadataRetriever().use { retriever ->
                                if (pfd != null) {
                                    retriever.setDataSource(pfd.fileDescriptor)
                                } else {
                                    retriever.setDataSource(context, fileUri)
                                }
                                if (!hasRealTagTitle) {
                                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                        ?.trim()
                                        ?.takeIf {
                                            it.isNotBlank() &&
                                                !it.equals(fileNameNoExt, ignoreCase = true) &&
                                                !it.equals(displayName, ignoreCase = true)
                                        }
                                        ?.let {
                                            title = it
                                            hasRealTagTitle = true
                                        }
                                }
                                val art = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                                if (!art.isNullOrBlank()) {
                                    author = art.trim()
                                    if (folderCachedAuthor == null) folderCachedAuthor = author
                                }
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                    ?.takeIf { it.isNotBlank() }?.let {
                                        album = it.trim()
                                        if (folderCachedAlbum == null) folderCachedAlbum = album
                                    }
                                if (trackNumber == 0) {
                                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                                        ?.substringBefore('/')?.trim()?.toIntOrNull()?.let { trackNumber = it }
                                }
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull()?.takeIf { it > 0L }?.let { durationMs = it }
                                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                                    ?.toIntOrNull()?.takeIf { it > 0 }?.let {
                                        bitrateKbps = (it / 1000).coerceIn(12, 1411)
                                        folderCachedBitrate = bitrateKbps
                                    }

                                if (needCover && coverPath == null) {
                                    val picBytes = retriever.embeddedPicture
                                    if (picBytes != null && picBytes.isNotEmpty() && picBytes.size <= 12 * 1024 * 1024) {
                                        coverPath = saveCoverBytesToPermanentDir(
                                            context = context,
                                            bytes = picBytes,
                                            key = "saf_${childDocId.hashCode().and(0x7FFFFFFF)}"
                                        )
                                        if (folderCoverPath == null && coverPath != null) {
                                            folderCoverPath = coverPath
                                        }
                                    }
                                }
                            }
                        } finally {
                            runCatching { pfd?.close() }
                        }
                    }
                }

                if (durationMs <= 0L && size > 0L) {
                    durationMs = ((size * 8L) / (bitrateKbps * 1000L)) * 1000L
                }

                if (durationMs < minDurationMs) continue

                // Resolve effective folder path even if the user selected the book folder directly
                val docIdFolderPath = childDocId
                    .substringAfter(':', "")
                    .replace('\\', '/')
                    .substringBeforeLast('/', "")
                    .trim('/')
                val effectiveRelativePath = when {
                    currentPath.contains('/') -> currentPath
                    docIdFolderPath.isNotBlank() -> normalizeCanonicalBookFolder(docIdFolderPath, currentPath.ifBlank { rootFolderName })
                    currentPath.isNotBlank() -> currentPath
                    rootFolderName.isNotBlank() && rootFolderName != "Выбранная папка" -> rootFolderName
                    else -> ""
                }
                val effectiveFolderName = effectiveRelativePath.substringAfterLast('/').ifBlank {
                    currentPath.ifBlank { rootFolderName.ifBlank { "Выбранная папка" } }
                }

                if (query.isNotBlank()) {
                    val matches = title.contains(query, ignoreCase = true) ||
                        author.contains(query, ignoreCase = true) ||
                        album.contains(query, ignoreCase = true) ||
                        displayName.contains(query, ignoreCase = true) ||
                        effectiveRelativePath.contains(query, ignoreCase = true)
                    if (!matches) continue
                }

                val isAlready = existingUris.contains(fileUri.toString()) ||
                    existingUris.any { it.contains(displayName, ignoreCase = true) }

                outList.add(
                    ScannedAudioFile(
                        uri = fileUri,
                        title = title,
                        author = author,
                        album = album,
                        trackNumber = trackNumber,
                        displayName = displayName,
                        folderName = effectiveFolderName,
                        relativePath = effectiveRelativePath,
                        format = format,
                        durationMs = durationMs.coerceAtLeast(1000L),
                        sizeBytes = size,
                        bitrateKbps = bitrateKbps,
                        dateModifiedMs = lastMod,
                        isAlreadyInLibrary = isAlready,
                        coverImagePath = coverPath ?: folderCoverPath
                    )
                )
            }

            // Backfill cover path for earlier files in the same folder if cover was discovered in a later file
            if (folderCoverPath != null) {
                for (idx in folderAddedStartIdx until outList.size) {
                    val item = outList[idx]
                    if (item.coverImagePath == null) {
                        outList[idx] = item.copy(coverImagePath = folderCoverPath)
                    }
                }
            }

            // 3. Recurse into subdirectories (passing down folderCoverPath if sub-folders are CD1/CD2 parts)
            for (sub in subDirs) {
                val subDirUri = runCatching {
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, sub.docId)
                }.getOrNull() ?: continue
                val nextPath = if (currentPath.isEmpty()) sub.displayName else "$currentPath/${sub.displayName}"
                scanDirectoryRecursive(
                    context = context,
                    treeUri = treeUri,
                    currentDirUri = subDirUri,
                    currentPath = nextPath,
                    rootFolderName = rootFolderName,
                    inheritedCoverPath = folderCoverPath,
                    existingUris = existingUris,
                    query = query,
                    formatFilter = formatFilter,
                    minDurationMs = minDurationMs,
                    outList = outList
                )
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun findAndCacheFolderImage(
        context: Context,
        treeUri: Uri,
        imageEntries: List<SafChildEntry>
    ): String? {
        if (imageEntries.isEmpty()) return null
        val priorityNames = listOf("cover", "folder", "front", "album", "обложка", "art", "poster", "book")
        val bestEntry = imageEntries
            .filter { it.size in 512L..(15L * 1024L * 1024L) }
            .sortedWith(
                compareByDescending<SafChildEntry> { entry ->
                    val base = entry.displayName.substringBeforeLast('.').lowercase()
                    priorityNames.indexOfFirst { base.contains(it) }.let { if (it >= 0) 100 - it else 0 }
                }.thenByDescending { it.size }
            )
            .firstOrNull() ?: imageEntries.firstOrNull() ?: return null

        return runCatching {
            val imgUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, bestEntry.docId)
            val bytes = context.contentResolver.openInputStream(imgUri)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                saveCoverBytesToPermanentDir(
                    context = context,
                    bytes = bytes,
                    key = "folder_${bestEntry.docId.hashCode().and(0x7FFFFFFF)}"
                )
            } else null
        }.getOrNull()
    }

    internal fun saveCoverBytesToPermanentDir(
        context: Context,
        bytes: ByteArray,
        key: String
    ): String? {
        if (bytes.size < 16) return null
        return runCatching {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val coverFile = File(coversDir, "cover_$key.jpg")
            if (coverFile.exists() && coverFile.length() > 64L) {
                return coverFile.absolutePath
            }
            coverFile.writeBytes(bytes)
            coverFile.absolutePath
        }.getOrNull()
    }

    private fun extractCoverFromMediaStoreOrParentDir(
        context: Context,
        contentUri: Uri,
        albumId: Long,
        fileDataPath: String,
        key: String
    ): String? {
        // 1. Check MediaMetadataRetriever embedded picture
        runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, contentUri)
                val pic = retriever.embeddedPicture
                if (pic != null && pic.isNotEmpty() && pic.size <= 12 * 1024 * 1024) {
                    saveCoverBytesToPermanentDir(context, pic, key)?.let { return it }
                }
            }
        }
        // 2. Check sibling image files in the same filesystem directory if fileDataPath is available
        if (fileDataPath.isNotBlank()) {
            runCatching {
                val parentDir = File(fileDataPath).parentFile
                if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                    val imgFiles = parentDir.listFiles { f ->
                        f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS && f.length() in 512L..(15L * 1024L * 1024L)
                    }
                    if (!imgFiles.isNullOrEmpty()) {
                        val priorityNames = listOf("cover", "folder", "front", "album", "обложка", "art")
                        val best = imgFiles.sortedWith(
                            compareByDescending<File> { f ->
                                val n = f.nameWithoutExtension.lowercase()
                                if (priorityNames.any { n.contains(it) }) 1 else 0
                            }.thenByDescending { it.length() }
                        ).first()
                        saveCoverBytesToPermanentDir(context, best.readBytes(), key)?.let { return it }
                    }
                }
            }
        }
        // 3. Check MediaStore album art URI if albumId > 0
        if (albumId > 0L) {
            runCatching {
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                val bytes = context.contentResolver.openInputStream(albumArtUri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    saveCoverBytesToPermanentDir(context, bytes, key)?.let { return it }
                }
            }
        }
        return null
    }

    private fun detectAudioFormat(displayName: String, mimeType: String): String {
        val ext = displayName.substringAfterLast('.', "").uppercase()
        return when {
            ext in listOf("MP3", "M4A", "M4B", "AMR", "WAV") -> ext
            mimeType.contains("m4b") -> "M4B"
            mimeType.contains("mp3") || mimeType.contains("mpeg") -> "MP3"
            mimeType.contains("m4a") || mimeType.contains("mp4") -> "M4A"
            mimeType.contains("amr") -> "AMR"
            mimeType.contains("wav") -> "WAV"
            else -> "MP3"
        }
    }

    fun getFolderName(context: Context, treeUri: Uri): String {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val clean = docId.substringAfterLast(':').substringAfterLast('/')
            if (clean.isNotBlank()) clean else "Выбранная папка"
        }.getOrDefault("Выбранная папка")
    }

    private val GENERIC_ROOT_FOLDERS = setOf(
        "audiobooks", "audiobook", "аудиокниги", "аудиокнига",
        "books", "book", "книги", "книга",
        "music", "музыка",
        "download", "downloads", "загрузки", "загрузка",
        "documents", "документы",
        "podcasts", "podcast", "подкасты",
        "media", "audio", "аудио",
        "storage", "emulated", "0", "sdcard", "internal", "external",
        "выбранная папка", "устройство", "корневая папка", "память телефона", "корневая"
    )

    internal fun extractRelativePathFromFilesystemPath(fileDataPath: String): String {
        val parent = fileDataPath.replace('\\', '/').substringBeforeLast('/', "")
        val afterEmulated = parent.substringAfter("emulated/0/", parent.substringAfter("sdcard/", parent))
        return afterEmulated.trim('/')
    }

    /**
     * Normalizes a relative path into its canonical book folder path:
     * - Strips generic top-level container folders (e.g. "Audiobooks/", "Download/", "Music/", "Аудиокниги/")
     * - Strips trailing multi-disc / chapter / part subfolders (e.g. "CD1", "Диск 2", "Часть 1", "01")
     *   so all files of a multi-part MP3 book stay grouped in their parent book folder.
     */
    internal fun normalizeCanonicalBookFolder(
        rawRelativePath: String,
        fallbackFolderName: String = "",
        fileUri: Uri? = null
    ): String {
        var path = rawRelativePath.replace('\\', '/').trim('/')

        // If path is empty or generic, try to extract folder path from the decoded URI (e.g. SAF document URI)
        if ((path.isBlank() || path.lowercase() in GENERIC_ROOT_FOLDERS) && fileUri != null) {
            val decoded = runCatching { Uri.decode(fileUri.toString()) }.getOrDefault("")
            val docPart = decoded.substringAfter("/document/", decoded)
                .substringAfter(':', "")
                .replace('\\', '/')
            if (docPart.contains('/')) {
                path = docPart.substringBeforeLast('/', "").trim('/')
            }
        }

        val rawSegments = path.split('/').map { it.trim() }.filter { it.isNotBlank() }.toMutableList()

        // Strip leading generic system/root directories while keeping at least 1 segment if nothing else exists
        while (rawSegments.size > 1 && rawSegments.first().lowercase() in GENERIC_ROOT_FOLDERS) {
            rawSegments.removeAt(0)
        }
        if (rawSegments.size == 1 && rawSegments[0].lowercase() in GENERIC_ROOT_FOLDERS) {
            rawSegments.clear()
        }

        // Collapse trailing sub-part / disc / chapter folders (e.g., CD1, Часть 1, Диск 2, 01) into parent book folder
        while (rawSegments.size >= 2 && isSubPartFolderName(rawSegments.last())) {
            rawSegments.removeAt(rawSegments.lastIndex)
        }

        if (rawSegments.isNotEmpty()) {
            return rawSegments.joinToString("/")
        }

        val cleanFallback = fallbackFolderName.replace('\\', '/').trim('/')
        if (cleanFallback.isNotBlank() && cleanFallback.lowercase() !in GENERIC_ROOT_FOLDERS) {
            return cleanFallback
        }
        return ""
    }

    /**
     * Extracts (Author, BookTitle) from a normalized canonical folder key.
     * Supports "Author/BookTitle", "Genre/Author/BookTitle", and "Author - BookTitle".
     */
    internal fun extractFolderAuthorAndTitle(folderKey: String): Pair<String?, String?> {
        val segments = folderKey.split('/').map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        while (segments.size > 1 && segments.first().lowercase() in GENERIC_ROOT_FOLDERS) {
            segments.removeAt(0)
        }
        while (segments.size >= 2 && isSubPartFolderName(segments.last())) {
            segments.removeAt(segments.lastIndex)
        }

        if (segments.size >= 2) {
            // In "Author/Book" or "Category/Author/Book", the last segment is the Book folder
            // and the second-to-last segment is the Author folder.
            val rawAuthorSeg = segments[segments.size - 2]
            val rawBookSeg = segments.last()
            val author = cleanFolderAuthorName(rawAuthorSeg)
            val bookTitle = BookTitleCleaner.cleanBookTitle(rawBookSeg, author).ifBlank { cleanFolderBookTitle(rawBookSeg) }
            return author to bookTitle
        } else if (segments.size == 1) {
            val rawFolder = segments[0]
            val dashSplit = rawFolder.split(Regex("""\s+[-–—]\s+"""))
            if (dashSplit.size >= 2) {
                val author = cleanFolderAuthorName(dashSplit[0])
                val rest = dashSplit.drop(1).joinToString(" - ")
                val bookTitle = BookTitleCleaner.cleanBookTitle(rest, author).ifBlank { cleanFolderBookTitle(rest) }
                return author to bookTitle
            }
            return null to cleanFolderBookTitle(rawFolder)
        }
        return null to null
    }

    /**
     * Groups scanned audio files by folder hierarchy:
     * Hierarchy rule: Root folder / Author / Book Title / *.mp3 (or Root / Book Title / *.mp3).
     * Every book is in its own folder (even if it consists of a single file).
     * Books with missing or incomplete tags are grouped by folder, with book title and author
     * derived from the folder structure to prevent library clutter.
     */
    fun groupScannedFiles(files: List<ScannedAudioFile>): List<ScannedBookGroup> {
        if (files.isEmpty()) return emptyList()

        // Group files by canonical book folder path (collapsing CD1/CD2/Часть 1 subfolders into parent book folder)
        val byFolder = files.groupBy { file ->
            val canonical = normalizeCanonicalBookFolder(file.relativePath, file.folderName, file.uri)
            if (canonical.isNotBlank()) {
                canonical
            } else {
                file.folderName.trim().ifBlank { "Корневая папка" }
            }
        }

        val resultGroups = mutableListOf<ScannedBookGroup>()
        val usedGroupIds = mutableSetOf<String>()

        for ((folderKey, folderFiles) in byFolder) {
            val isSpecificFolder = folderKey.isNotBlank() &&
                folderKey.lowercase() !in GENERIC_ROOT_FOLDERS

            // CRITICAL RULE: Every book folder represents ONE single audiobook!
            // Never split a specific book folder into multiple books even if MP3 files have missing or inconsistent tags.
            val subGroups: Map<String, List<ScannedAudioFile>> = if (isSpecificFolder) {
                mapOf("FOLDER:$folderKey" to folderFiles)
            } else {
                // Loose files in a generic root bucket without a subfolder:
                // Group by album tag, or by common title prefix, or if they are numbered chapter/track files, group them together
                folderFiles.groupBy { file ->
                    val cleanAlbum = file.album.trim()
                    if (cleanAlbum.isNotBlank() && !isTrackOrChapterLikeName(cleanAlbum)) {
                        "ALBUM:${BookTitleCleaner.cleanBookTitle(cleanAlbum).lowercase()}"
                    } else {
                        val prefix = extractCommonBookTitle(file.title, file.displayName)
                        if (prefix.isNotBlank() && !isTrackOrChapterLikeName(prefix)) {
                            "PREFIX:${prefix.lowercase()}"
                        } else if (folderFiles.size > 1 && isTrackOrChapterLikeName(file.displayName.substringBeforeLast('.'))) {
                            "ROOT_UNTAGGED_BOOK:$folderKey"
                        } else {
                            "FILE:${file.uri}"
                        }
                    }
                }
            }

            for ((subKey, groupFiles) in subGroups) {
                if (groupFiles.isEmpty()) continue

                val allHaveDistinctTrackNumbers = groupFiles.size > 1 &&
                    groupFiles.all { it.trackNumber > 0 } &&
                    groupFiles.map { "${it.relativePath}#${it.trackNumber}" }.distinct().size == groupFiles.size

                val sorted = if (allHaveDistinctTrackNumbers) {
                    groupFiles.sortedWith(
                        compareBy<ScannedAudioFile> { naturalOrderKey(it.relativePath) }
                            .thenBy { it.trackNumber }
                            .thenBy { naturalOrderKey(it.displayName) }
                    )
                } else {
                    groupFiles.sortedWith(
                        compareBy<ScannedAudioFile> { naturalOrderKey(it.relativePath) }
                            .thenBy { naturalOrderKey(it.displayName) }
                    )
                }

                val first = sorted.first()

                var folderAuthor: String? = null
                var folderBookTitle: String? = null

                if (isSpecificFolder) {
                    val (extractedAuthor, extractedTitle) = extractFolderAuthorAndTitle(folderKey)
                    folderAuthor = extractedAuthor
                    folderBookTitle = extractedTitle
                }

                // Check tags on audio files
                val albumTag = sorted.firstOrNull {
                    it.album.isNotBlank() &&
                        !it.album.equals("Audiobook", ignoreCase = true) &&
                        !isTrackOrChapterLikeName(it.album)
                }?.album?.trim()

                val tagAuthor = sorted.firstOrNull {
                    it.author.isNotBlank() &&
                        !it.author.startsWith("Папка:", ignoreCase = true) &&
                        !it.author.startsWith("Аудиофайл (", ignoreCase = true) &&
                        it.author != "Неизвестный автор" &&
                        it.author.lowercase() !in GENERIC_ROOT_FOLDERS
                }?.author?.trim()

                // Resolve Author (prefer folder author when inside Author/Book folder hierarchy, fallback to tagAuthor):
                val rawAuthor = when {
                    !folderAuthor.isNullOrBlank() && folderAuthor != "Неизвестный автор" -> folderAuthor
                    !tagAuthor.isNullOrBlank() -> tagAuthor
                    else -> "Неизвестный автор"
                }
                var resolvedAuthor = BookTitleCleaner.cleanAuthorName(rawAuthor)

                // Resolve Book Title (always prefer the folder name when inside a specific book folder):
                val rawBookTitle = when {
                    !folderBookTitle.isNullOrBlank() -> folderBookTitle
                    !albumTag.isNullOrBlank() -> albumTag
                    sorted.size > 1 -> extractCommonBookTitle(first.title, first.displayName).ifBlank { first.title }
                    else -> cleanFileTitle(first.title.ifBlank { first.displayName.substringBeforeLast('.') })
                }.ifBlank { first.displayName.substringBeforeLast('.').ifBlank { "Аудиокнига" } }

                // If author is still unknown and rawBookTitle has "Автор - Название", extract author
                if (resolvedAuthor == "Неизвестный автор") {
                    val dashSplit = rawBookTitle.split(Regex("""\s+[-–—]\s+"""))
                    if (dashSplit.size >= 2) {
                        val candidateAuthor = BookTitleCleaner.cleanAuthorName(dashSplit[0])
                        if (candidateAuthor.isNotBlank() && candidateAuthor != "Неизвестный автор" && !candidateAuthor.any { it.isDigit() }) {
                            resolvedAuthor = candidateAuthor
                        }
                    }
                }

                val resolvedBookTitle = BookTitleCleaner.cleanBookTitle(rawBookTitle, resolvedAuthor)
                    .ifBlank { rawBookTitle }

                val totalDuration = sorted.sumOf { it.durationMs.coerceAtLeast(0L) }
                val totalSize = sorted.sumOf { it.sizeBytes.coerceAtLeast(0L) }
                val formats = sorted.map { it.format.uppercase() }.distinct().sorted()
                val cover = sorted.firstOrNull { !it.coverImagePath.isNullOrBlank() }?.coverImagePath
                val isAlready = sorted.all { it.isAlreadyInLibrary }

                val baseGroupId = "${folderKey}_${subKey.hashCode().and(0x7FFFFFFF)}_${resolvedBookTitle.hashCode().and(0x7FFFFFFF)}_${first.uri.toString().hashCode().and(0x7FFFFFFF)}"
                var groupId = baseGroupId
                var suffix = 2
                while (!usedGroupIds.add(groupId)) {
                    groupId = "${baseGroupId}_$suffix"
                    suffix++
                }

                resultGroups.add(
                    ScannedBookGroup(
                        groupId = groupId,
                        title = resolvedBookTitle,
                        author = resolvedAuthor,
                        folderName = folderKey.substringAfterLast('/').ifBlank { folderKey },
                        relativePath = folderKey,
                        files = sorted,
                        totalDurationMs = totalDuration,
                        totalSizeBytes = totalSize,
                        formats = formats,
                        coverImagePath = cover,
                        isAlreadyInLibrary = isAlready
                    )
                )
            }
        }

        return resultGroups.sortedWith(
            compareBy<ScannedBookGroup> { it.author.lowercase() }
                .thenBy { it.title.lowercase() }
        )
    }

    internal fun isSubPartFolderName(name: String): Boolean {
        val lower = name.lowercase().trim()
        if (lower.matches(Regex("""^(?:cd|disc|disk|part|диск|том|часть|ч|глава|гл|главы|book|книга)\s*[-_–.#№]?\s*\d+(?:\s*[-–]\s*\d+)?$"""))) {
            return true
        }
        if (lower.matches(Regex("""^\d{1,3}$"""))) {
            return true
        }
        return lower.startsWith("cd ") ||
            lower.startsWith("cd_") ||
            lower.startsWith("cd-") ||
            lower.startsWith("part ") ||
            lower.startsWith("диск ") ||
            lower.startsWith("том ") ||
            lower.startsWith("часть ")
    }

    private fun isTrackOrChapterLikeName(name: String): Boolean {
        val s = name.trim().lowercase()
        if (s.isBlank()) return true
        if (s.matches(Regex("""^[\d\s._\-–—()]+$"""))) return true
        if (s.matches(Regex("""^(?:глава|часть|трэк|трек|дорожка|файл|диск|том|пролог|эпилог|вступление|chapter|part|track|disc|disk|cd|audio|file)\s*[-_–.#№]?\s*\d*.*$"""))) {
            return true
        }
        return false
    }

    private fun cleanFolderBookTitle(name: String): String {
        return BookTitleCleaner.cleanBookTitle(name)
    }

    private fun cleanFolderAuthorName(name: String): String {
        return BookTitleCleaner.cleanAuthorName(name)
    }

    private fun cleanFileTitle(name: String): String {
        return name
            .replace(Regex("^\\d{1,3}\\s*[-_–.]\\s*"), "")
            .replace('_', ' ')
            .trim()
            .ifBlank { name }
    }

    private fun extractCommonBookTitle(title: String, displayName: String): String {
        val base = if (title.isNotBlank() && !isTrackOrChapterLikeName(title)) title else displayName.substringBeforeLast('.')
        // Strip common chapter/track numbering endings or prefixes: e.g. " - Part 1", " Chapter 01", "Глава 1", "01 - "
        val stripped = base
            .replace(Regex("(?i)\\s*[-_–.]\\s*(часть|глава|гл|ч|part|ch|chapter|track|трек|диск|cd)\\s*\\d+.*$"), "")
            .replace(Regex("(?i)^(часть|глава|гл|ч|part|ch|chapter|track|трек|диск|cd)\\s*\\d+\\s*[-_–.]\\s*"), "")
            .replace(Regex("^\\d{1,4}\\s*[-_–.]\\s*"), "")
            .replace(Regex("\\s*[-_–.]\\s*\\d{1,4}$"), "")
            .replace('_', ' ')
            .trim()
        return if (isTrackOrChapterLikeName(stripped)) "" else stripped
    }

    internal fun naturalOrderKey(name: String): String {
        // Natural alphanumeric sorting key: pads numbers to 6 digits so "part 2" < "part 10"
        return Regex("\\d+").replace(name) { matchResult ->
            matchResult.value.padStart(6, '0')
        }.lowercase()
    }
}
