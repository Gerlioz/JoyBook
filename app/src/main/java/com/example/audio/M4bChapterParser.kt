package com.example.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

data class ParsedEmbeddedChapter(
    val title: String,
    val startTimeMs: Long,
    val durationMs: Long
)

data class Mp4TagMetadata(
    val title: String? = null,
    val author: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val durationMs: Long? = null,
    val coverBytes: ByteArray? = null
)

/**
 * Dedicated parser for `.m4b`, `.m4a`, `.mp4`, and `.mp3` (ID3v2 CHAP & TIT2) audiobook files.
 * Extracts:
 * 1. Nero chapters (`moov -> udta -> chpl`) — both version 0 and version 1
 * 2. QuickTime text track chapters (`moov -> trak -> mdia -> minf -> stbl -> stts / stsc / stsz / stco / co64`)
 *    reading sample text directly via FileChannel at arbitrary file offsets (including inside `mdat` or tail `moov`)
 * 3. ID3v2.2 / ID3v2.3 / ID3v2.4 `CHAP` frames (`TIT2`/`TT2` sub-frames) and `TIT2` title tags with UTF-8, UTF-16, and Windows-1251 (CP1251) Cyrillic support
 * 4. iTunes / MP4 `ilst` metadata atoms (`©nam`, `©ART`, `aART`, `©alb`, `©wrt`, `trkn`, `covr`)
 */
object M4bChapterParser {

    private val CONTAINER_BOXES = setOf(
        "moov", "trak", "edts", "mdia", "minf", "dinf", "stbl", "udta", "meta", "ilst", "tref"
    )

    private val WIN1251_CHARSET: Charset by lazy {
        runCatching { Charset.forName("windows-1251") }.getOrDefault(Charsets.ISO_8859_1)
    }

    suspend fun parseChapters(
        context: Context,
        audiobookId: Long,
        uri: Uri,
        totalDurationMs: Long,
        audioFormat: String = "M4B"
    ): List<com.example.data.local.ChapterEntity> {
        val embedded = extractChaptersFromFile(
            context = context,
            uri = uri,
            fileDurationMs = totalDurationMs,
            audioFormat = audioFormat
        )
        if (embedded.isNotEmpty()) {
            return embedded.mapIndexed { idx, ch ->
                com.example.data.local.ChapterEntity(
                    audiobookId = audiobookId,
                    chapterIndex = idx,
                    title = ch.title,
                    startTimeMs = ch.startTimeMs,
                    durationMs = ch.durationMs,
                    fileUri = uri.toString()
                )
            }
        }
        val tagTitle = extractMp4Metadata(context, uri, includeCover = false).title
            ?.takeIf { it.isNotBlank() }
            ?: "Глава 1"
        return listOf(
            com.example.data.local.ChapterEntity(
                audiobookId = audiobookId,
                chapterIndex = 0,
                title = tagTitle,
                startTimeMs = 0L,
                durationMs = totalDurationMs.coerceAtLeast(1_000L),
                fileUri = uri.toString()
            )
        )
    }

    suspend fun extractChaptersFromFile(
        context: Context,
        uri: Uri,
        fileDurationMs: Long,
        audioFormat: String = "M4B"
    ): List<ParsedEmbeddedChapter> = extractChaptersFromFile(
        context = context,
        uri = uri,
        totalDurationMs = fileDurationMs
    )

    suspend fun extractChaptersFromFile(
        context: Context,
        uri: Uri,
        totalDurationMs: Long
    ): List<ParsedEmbeddedChapter> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val fileSize = channel.size()
                    if (fileSize <= 16L) return@withContext emptyList()

                    val headSize = minOf(fileSize, 6L * 1024L * 1024L).toInt()
                    val headBuffer = ByteBuffer.allocate(headSize)
                    channel.position(0L)
                    channel.read(headBuffer)
                    val headBytes = headBuffer.array()

                    // 1. Check for ID3v2 header at start of file (MP3 or ID3-wrapped M4B/M4A)
                    if (headSize >= 10 &&
                        headBytes[0] == 'I'.code.toByte() &&
                        headBytes[1] == 'D'.code.toByte() &&
                        headBytes[2] == '3'.code.toByte()
                    ) {
                        val id3Size = getSynchsafeInt(headBytes, 6) + 10
                        val fullId3Bytes = if (id3Size <= headSize) {
                            headBytes
                        } else if (id3Size <= 16 * 1024 * 1024 && id3Size <= fileSize) {
                            val buf = ByteBuffer.allocate(id3Size)
                            channel.position(0L)
                            channel.read(buf)
                            buf.array()
                        } else {
                            headBytes
                        }
                        val id3Chapters = parseId3v2ChapFrames(fullId3Bytes, totalDurationMs)
                        if (id3Chapters.isNotEmpty()) {
                            return@withContext id3Chapters
                        }
                    }

                    // 2. Parse MP4 / M4B / M4A boxes in head buffer
                    val chaptersFromHead = parseMp4BufferForChapters(
                        bytes = headBytes,
                        baseFileOffset = 0L,
                        channel = channel,
                        totalDurationMs = totalDurationMs
                    )
                    if (chaptersFromHead.isNotEmpty()) {
                        return@withContext chaptersFromHead
                    }

                    // 3. Locate top-level `moov` atom by scanning top-level box headers across the entire file
                    val moovLocation = findTopLevelBox(channel, fileSize, "moov")
                    if (moovLocation != null) {
                        val (moovOffset, moovSize) = moovLocation
                        if (moovSize in 8L..(24L * 1024L * 1024L) && moovOffset + moovSize <= fileSize) {
                            val moovBuf = ByteBuffer.allocate(moovSize.toInt())
                            channel.position(moovOffset)
                            channel.read(moovBuf)
                            val chaptersFromMoov = parseMp4BufferForChapters(
                                bytes = moovBuf.array(),
                                baseFileOffset = moovOffset,
                                channel = channel,
                                totalDurationMs = totalDurationMs
                            )
                            if (chaptersFromMoov.isNotEmpty()) {
                                return@withContext chaptersFromMoov
                            }
                        }
                    }

                    // 4. Fallback: if file > headSize, check the last 6MB of the file
                    if (fileSize > headSize) {
                        val tailSize = minOf(fileSize - headSize, 6L * 1024L * 1024L).toInt()
                        val tailStart = fileSize - tailSize
                        val tailBuffer = ByteBuffer.allocate(tailSize)
                        channel.position(tailStart)
                        channel.read(tailBuffer)
                        val chaptersFromTail = parseMp4BufferForChapters(
                            bytes = tailBuffer.array(),
                            baseFileOffset = tailStart,
                            channel = channel,
                            totalDurationMs = totalDurationMs
                        )
                        if (chaptersFromTail.isNotEmpty()) {
                            return@withContext chaptersFromTail
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        emptyList()
    }

    /**
     * Extracts title, author, album, track number, duration, and optionally embedded cover artwork
     * from `.m4b`, `.m4a`, `.mp4`, or `.mp3` files.
     * Set [includeCover] = false when fast-scanning multi-file folders where folder cover is already known.
     */
    fun extractMp4Metadata(
        context: Context,
        uri: Uri,
        includeCover: Boolean = true
    ): Mp4TagMetadata {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    val fileSize = channel.size()
                    if (fileSize <= 16L) return Mp4TagMetadata()

                    val maxHead = if (includeCover) 4L * 1024L * 1024L else 512L * 1024L
                    val headSize = minOf(fileSize, maxHead).toInt()
                    val headBuffer = ByteBuffer.allocate(headSize)
                    channel.position(0L)
                    channel.read(headBuffer)
                    val headBytes = headBuffer.array()

                    // 1. Check ID3v2 tags (MP3 or ID3-wrapped M4B/M4A)
                    if (headSize >= 10 &&
                        headBytes[0] == 'I'.code.toByte() &&
                        headBytes[1] == 'D'.code.toByte() &&
                        headBytes[2] == '3'.code.toByte()
                    ) {
                        val id3TotalSize = getSynchsafeInt(headBytes, 6) + 10
                        val id3Bytes = if (id3TotalSize <= headSize) {
                            headBytes
                        } else if (includeCover && id3TotalSize <= 14 * 1024 * 1024 && id3TotalSize <= fileSize) {
                            val fullId3Buf = ByteBuffer.allocate(id3TotalSize)
                            channel.position(0L)
                            channel.read(fullId3Buf)
                            fullId3Buf.array()
                        } else {
                            headBytes
                        }
                        val id3Meta = parseId3v2Metadata(id3Bytes, includeCover)
                        if (id3Meta.title != null || id3Meta.author != null || id3Meta.album != null || id3Meta.coverBytes != null) {
                            if (!includeCover || id3Meta.coverBytes != null) {
                                return id3Meta
                            }
                            // If ID3 had text tags but no cover yet, keep id3Meta and also check MP4 boxes below in case it's a hybrid file
                            val mp4Fallback = parseMp4BoxesForMetadata(headBytes, 0, headBytes.size, includeCover = true)
                            return mergeMetadata(id3Meta, mp4Fallback)
                        }
                    }

                    // 2. Parse MP4 boxes from head
                    var meta = parseMp4BoxesForMetadata(headBytes, 0, headBytes.size, includeCover)
                    if (meta.title != null && (meta.author != null || meta.album != null) && (!includeCover || meta.coverBytes != null)) {
                        return meta
                    }

                    // 3. Locate top-level `moov` box directly via FileChannel (handles both tail `moov` and `moov` > headSize)
                    val moovLoc = findTopLevelBox(channel, fileSize, "moov")
                    if (moovLoc != null) {
                        val (moovOffset, moovSize) = moovLoc
                        val moovEnd = moovOffset + moovSize
                        val maxMoovRead = if (includeCover) 16L * 1024L * 1024L else 2L * 1024L * 1024L
                        if (moovEnd > headSize && moovSize in 8L..maxMoovRead && moovEnd <= fileSize) {
                            val moovBuf = ByteBuffer.allocate(moovSize.toInt())
                            channel.position(moovOffset)
                            channel.read(moovBuf)
                            val moovMeta = parseMp4BoxesForMetadata(moovBuf.array(), 0, moovBuf.capacity(), includeCover)
                            meta = mergeMetadata(meta, moovMeta)
                            if (meta.title != null && (!includeCover || meta.coverBytes != null)) {
                                return meta
                            }
                        } else if (moovEnd > headSize && moovSize > maxMoovRead && moovEnd <= fileSize) {
                            // Huge moov box (> 16MB): locate `udta` inside `moov` directly via FileChannel
                            val udtaLoc = findChildBoxInChannel(channel, moovOffset + 8L, moovEnd, "udta")
                            if (udtaLoc != null) {
                                val (udtaOffset, udtaSize) = udtaLoc
                                if (udtaSize in 8L..(14L * 1024L * 1024L) && udtaOffset + udtaSize <= fileSize) {
                                    val udtaBuf = ByteBuffer.allocate(udtaSize.toInt())
                                    channel.position(udtaOffset)
                                    channel.read(udtaBuf)
                                    val udtaMeta = parseMp4BoxesForMetadata(udtaBuf.array(), 0, udtaBuf.capacity(), includeCover)
                                    meta = mergeMetadata(meta, udtaMeta)
                                    if (meta.title != null && (!includeCover || meta.coverBytes != null)) {
                                        return meta
                                    }
                                }
                            }
                        }
                    }

                    // 4. Fallback tail scan
                    if (fileSize > headSize && (meta.title == null || (includeCover && meta.coverBytes == null))) {
                        val maxTail = if (includeCover) 4L * 1024L * 1024L else 512L * 1024L
                        val tailSize = minOf(fileSize - headSize, maxTail).toInt()
                        val tailBuffer = ByteBuffer.allocate(tailSize)
                        channel.position(fileSize - tailSize)
                        channel.read(tailBuffer)
                        val tailMeta = parseMp4BoxesForMetadata(tailBuffer.array(), 0, tailSize, includeCover)
                        meta = mergeMetadata(meta, tailMeta)
                    }
                    return meta
                }
            }
        } catch (_: Throwable) {
        }
        return Mp4TagMetadata()
    }

    private fun mergeMetadata(primary: Mp4TagMetadata, secondary: Mp4TagMetadata): Mp4TagMetadata {
        return Mp4TagMetadata(
            title = primary.title ?: secondary.title,
            author = primary.author ?: secondary.author,
            album = primary.album ?: secondary.album,
            trackNumber = primary.trackNumber ?: secondary.trackNumber,
            durationMs = primary.durationMs ?: secondary.durationMs,
            coverBytes = primary.coverBytes ?: secondary.coverBytes
        )
    }

    /**
     * Walks top-level MP4 atoms (`ftyp`, `moov`, `mdat`, `free`, etc.) with minimal I/O (8–16 bytes per hop)
     * to find the exact file offset and size of `targetBoxType` (e.g. `moov`).
     */
    private fun findTopLevelBox(
        channel: FileChannel,
        fileSize: Long,
        targetBoxType: String
    ): Pair<Long, Long>? = findChildBoxInChannel(channel, 0L, fileSize, targetBoxType)

    private fun findChildBoxInChannel(
        channel: FileChannel,
        startOffset: Long,
        endOffset: Long,
        targetBoxType: String
    ): Pair<Long, Long>? {
        return try {
            val headerBuf = ByteBuffer.allocate(16)
            var offset = startOffset
            var hops = 0
            while (offset + 8L <= endOffset && hops < 128) {
                hops++
                headerBuf.clear()
                channel.position(offset)
                val read = channel.read(headerBuf)
                if (read < 8) break
                headerBuf.flip()

                val size32 = headerBuf.int.toLong() and 0xFFFFFFFFL
                val typeBytes = ByteArray(4)
                headerBuf.get(typeBytes)
                val type = String(typeBytes, Charsets.ISO_8859_1)

                val boxSize = when {
                    size32 == 1L -> {
                        if (read < 16) break
                        headerBuf.long
                    }
                    size32 == 0L -> endOffset - offset
                    else -> size32
                }

                if (boxSize < 8L || offset + boxSize > endOffset) break
                if (type == targetBoxType) {
                    return offset to boxSize
                }
                offset += boxSize
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    internal fun parseIsoBmffChapters(
        bytes: ByteArray,
        totalDurationMs: Long
    ): List<ParsedEmbeddedChapter> = parseMp4BufferForChapters(
        bytes = bytes,
        totalDurationMs = totalDurationMs
    )

    internal fun parseId3v2Chapters(
        bytes: ByteArray,
        totalDurationMs: Long
    ): List<ParsedEmbeddedChapter> = parseId3v2ChapFrames(
        bytes = bytes,
        totalDurationMs = totalDurationMs
    )

    internal fun parseMetadataFromBytes(
        bytes: ByteArray,
        includeCover: Boolean = true
    ): Mp4TagMetadata = parseMp4BoxesForMetadata(
        bytes = bytes,
        start = 0,
        end = bytes.size,
        includeCover = includeCover
    )

    internal fun parseMp4BufferForChapters(
        bytes: ByteArray,
        totalDurationMs: Long,
        baseFileOffset: Long = 0L,
        channel: FileChannel? = null
    ): List<ParsedEmbeddedChapter> {
        // 1. Check Nero `chpl` chapters first
        val neroChapters = mutableListOf<Pair<Long, String>>()
        scanForChplAtom(bytes, 0, bytes.size, neroChapters)
        if (neroChapters.isNotEmpty()) {
            val sorted = neroChapters.distinctBy { it.first }.sortedBy { it.first }
            return buildChapterListFromStartTimes(sorted, totalDurationMs)
        }

        // 2. Check QuickTime chapter text tracks (`trak` -> `mdia` -> `minf` -> `stbl`)
        val qtChapters = extractQuickTimeTextTrackChapters(
            bytes = bytes,
            totalDurationMs = totalDurationMs,
            baseFileOffset = baseFileOffset,
            channel = channel
        )
        if (qtChapters.isNotEmpty()) {
            return qtChapters
        }

        return emptyList()
    }

    private fun scanForChplAtom(
        bytes: ByteArray,
        start: Int,
        end: Int,
        outChapters: MutableList<Pair<Long, String>>
    ) {
        var pos = start
        while (pos + 8 <= end) {
            val boxSizeLong = readUInt32BE(bytes, pos)
            val type = readBoxType(bytes, pos + 4)

            if (type == "chpl" && boxSizeLong >= 16 && pos + boxSizeLong <= end) {
                parseNeroChplBox(bytes, pos, (pos + boxSizeLong).toInt(), outChapters)
                return
            }

            if (boxSizeLong < 8 || pos + boxSizeLong > end) {
                pos += 4
                continue
            }

            val boxEnd = (pos + boxSizeLong).toInt()
            if (type in CONTAINER_BOXES) {
                val headerSkip = if (type == "meta") 12 else 8
                if (pos + headerSkip < boxEnd) {
                    scanForChplAtom(bytes, pos + headerSkip, boxEnd, outChapters)
                }
            }
            pos = boxEnd
        }
    }

    /**
     * Parses Nero `chpl` atom (supports both version 0 and version 1 layouts).
     * Each entry contains:
     * - 8-byte Int64 timestamp in 100-nanosecond units (divide by 10,000 for milliseconds)
     * - 1-byte string length `L`
     * - `L` bytes of UTF-8 / Windows-1251 chapter title
     */
    private fun parseNeroChplBox(
        bytes: ByteArray,
        boxStart: Int,
        boxEnd: Int,
        outChapters: MutableList<Pair<Long, String>>
    ) {
        val version = bytes[boxStart + 8].toInt() and 0xFF
        // Try standard v1 header (boxStart + 17) and v0 header (boxStart + 13), or auto-detect
        val candidateOffsets = if (version == 0) {
            listOf(boxStart + 12 to 1, boxStart + 13 to 4, boxStart + 12 to 4)
        } else {
            listOf(boxStart + 13 to 4, boxStart + 12 to 4, boxStart + 12 to 1)
        }

        for ((countPos, countWidth) in candidateOffsets) {
            if (countPos + countWidth > boxEnd) continue
            val count = if (countWidth == 1) {
                bytes[countPos].toInt() and 0xFF
            } else {
                readInt32BE(bytes, countPos)
            }
            if (count !in 1..4000) continue

            var cursor = countPos + countWidth
            val tempChapters = mutableListOf<Pair<Long, String>>()
            var valid = true
            for (i in 0 until count) {
                if (cursor + 9 > boxEnd) {
                    valid = false
                    break
                }
                val rawTime100ns = readInt64BE(bytes, cursor)
                if (rawTime100ns < 0L) {
                    valid = false
                    break
                }
                val startTimeMs = (rawTime100ns / 10_000L).coerceAtLeast(0L)
                val titleLen = bytes[cursor + 8].toInt() and 0xFF
                cursor += 9
                if (cursor + titleLen > boxEnd) {
                    valid = false
                    break
                }
                val rawTitle = decodeSmartString(bytes, cursor, titleLen).trim()
                val title = rawTitle.ifBlank { "Глава ${i + 1}" }
                tempChapters.add(startTimeMs to title)
                cursor += titleLen
            }
            if (valid && tempChapters.isNotEmpty()) {
                outChapters.addAll(tempChapters)
                return
            }
        }
    }

    /**
     * Parses QuickTime text chapter tracks (`trak` with `hdlr` subtype `text`, `sbtl`, or `subt`).
     * Supports reading sample text from any offset in the file via [channel] (`stco` 32-bit and `co64` 64-bit),
     * as well as multi-sample chunks via `stsc` and `stsz`.
     */
    private fun extractQuickTimeTextTrackChapters(
        bytes: ByteArray,
        totalDurationMs: Long,
        baseFileOffset: Long = 0L,
        channel: FileChannel? = null
    ): List<ParsedEmbeddedChapter> {
        val trakRanges = mutableListOf<IntRange>()
        collectBoxesOfType(bytes, 0, bytes.size, "trak", trakRanges)

        for (trak in trakRanges) {
            val chapters = extractChaptersFromTrack(
                bytes = bytes,
                trakStart = trak.first,
                trakEnd = trak.last + 1,
                totalDurationMs = totalDurationMs,
                baseFileOffset = baseFileOffset,
                channel = channel
            )
            if (chapters.isNotEmpty()) {
                return chapters
            }
        }
        return emptyList()
    }

    private data class StscEntry(
        val firstChunk: Int,
        val samplesPerChunk: Int
    )

    private fun extractChaptersFromTrack(
        bytes: ByteArray,
        trakStart: Int,
        trakEnd: Int,
        totalDurationMs: Long,
        baseFileOffset: Long,
        channel: FileChannel?
    ): List<ParsedEmbeddedChapter> {
        val hdlrRanges = mutableListOf<IntRange>()
        collectBoxesOfType(bytes, trakStart, trakEnd, "hdlr", hdlrRanges)
        val isTextTrack = hdlrRanges.any { range ->
            val start = range.first
            if (start + 20 <= range.last + 1) {
                val handlerType = readBoxType(bytes, start + 16)
                handlerType == "text" || handlerType == "sbtl" || handlerType == "subt" || handlerType == "chap"
            } else false
        }
        if (!isTextTrack) return emptyList()

        // Extract media timescale from `mdhd`
        val mdhdRanges = mutableListOf<IntRange>()
        collectBoxesOfType(bytes, trakStart, trakEnd, "mdhd", mdhdRanges)
        var timeScale = 1000L
        for (mdhd in mdhdRanges) {
            val s = mdhd.first
            if (s + 24 <= mdhd.last + 1) {
                val version = bytes[s + 8].toInt() and 0xFF
                timeScale = if (version == 1 && s + 32 <= mdhd.last + 1) {
                    readUInt32BE(bytes, s + 28).coerceAtLeast(1L)
                } else {
                    readUInt32BE(bytes, s + 20).coerceAtLeast(1L)
                }
            }
        }

        // Extract sample durations from `stts`
        val sttsRanges = mutableListOf<IntRange>()
        collectBoxesOfType(bytes, trakStart, trakEnd, "stts", sttsRanges)
        val sampleDurationsMs = mutableListOf<Long>()
        for (stts in sttsRanges) {
            val s = stts.first
            val e = stts.last + 1
            if (s + 16 <= e) {
                val entryCount = readInt32BE(bytes, s + 12).coerceIn(0, 10_000)
                var pos = s + 16
                for (i in 0 until entryCount) {
                    if (pos + 8 > e) break
                    val sampleCount = readInt32BE(bytes, pos).coerceIn(0, 10_000)
                    val sampleDelta = readUInt32BE(bytes, pos + 4)
                    val durationMs = (sampleDelta * 1000L) / timeScale.coerceAtLeast(1L)
                    repeat(sampleCount) {
                        sampleDurationsMs.add(durationMs)
                    }
                    pos += 8
                }
            }
        }
        if (sampleDurationsMs.isEmpty()) return emptyList()

        // Extract chunk offsets from `stco` (32-bit) or `co64` (64-bit)
        val chunkOffsets = mutableListOf<Long>()
        val stcoRanges = mutableListOf<IntRange>()
        collectBoxesOfType(bytes, trakStart, trakEnd, "stco", stcoRanges)
        for (stco in stcoRanges) {
            val s = stco.first
            val e = stco.last + 1
            if (s + 16 <= e) {
                val entryCount = readInt32BE(bytes, s + 12).coerceIn(0, 10_000)
                var pos = s + 16
                for (i in 0 until entryCount) {
                    if (pos + 4 > e) break
                    chunkOffsets.add(readUInt32BE(bytes, pos))
                    pos += 4
                }
            }
        }
        if (chunkOffsets.isEmpty()) {
            val co64Ranges = mutableListOf<IntRange>()
            collectBoxesOfType(bytes, trakStart, trakEnd, "co64", co64Ranges)
            for (co64 in co64Ranges) {
                val s = co64.first
                val e = co64.last + 1
                if (s + 16 <= e) {
                    val entryCount = readInt32BE(bytes, s + 12).coerceIn(0, 10_000)
                    var pos = s + 16
                    for (i in 0 until entryCount) {
                        if (pos + 8 > e) break
                        chunkOffsets.add(readInt64BE(bytes, pos))
                        pos += 8
                    }
                }
            }
        }

        // Extract sample sizes from `stsz`
        var defaultSampleSize = 0
        val sampleSizes = mutableListOf<Int>()
        val stszRanges = mutableListOf<IntRange>()
        collectBoxesOfType(bytes, trakStart, trakEnd, "stsz", stszRanges)
        for (stsz in stszRanges) {
            val s = stsz.first
            val e = stsz.last + 1
            if (s + 20 <= e) {
                defaultSampleSize = readInt32BE(bytes, s + 12).coerceAtLeast(0)
                val sampleCount = readInt32BE(bytes, s + 16).coerceIn(0, 10_000)
                if (defaultSampleSize == 0) {
                    var pos = s + 20
                    for (i in 0 until sampleCount) {
                        if (pos + 4 > e) break
                        sampleSizes.add(readInt32BE(bytes, pos).coerceAtLeast(0))
                        pos += 4
                    }
                }
            }
        }

        // Extract sample-to-chunk mapping from `stsc`
        val stscEntries = mutableListOf<StscEntry>()
        val stscRanges = mutableListOf<IntRange>()
        collectBoxesOfType(bytes, trakStart, trakEnd, "stsc", stscRanges)
        for (stsc in stscRanges) {
            val s = stsc.first
            val e = stsc.last + 1
            if (s + 16 <= e) {
                val entryCount = readInt32BE(bytes, s + 12).coerceIn(0, 10_000)
                var pos = s + 16
                for (i in 0 until entryCount) {
                    if (pos + 12 > e) break
                    val firstChunk = readInt32BE(bytes, pos).coerceAtLeast(1)
                    val samplesPerChunk = readInt32BE(bytes, pos + 4).coerceAtLeast(1)
                    stscEntries.add(StscEntry(firstChunk, samplesPerChunk))
                    pos += 12
                }
            }
        }

        // Compute exact file offset and byte size for each chapter sample
        val totalSamples = sampleDurationsMs.size
        val sampleFileOffsets = LongArray(totalSamples) { -1L }
        val sampleByteLengths = IntArray(totalSamples) { idx ->
            if (defaultSampleSize > 0) defaultSampleSize else (sampleSizes.getOrNull(idx) ?: 256)
        }

        if (chunkOffsets.size >= totalSamples && (stscEntries.isEmpty() || stscEntries.all { it.samplesPerChunk == 1 })) {
            for (idx in 0 until totalSamples) {
                sampleFileOffsets[idx] = chunkOffsets[idx]
            }
        } else if (chunkOffsets.isNotEmpty()) {
            var sampleIdx = 0
            for (chunkIndex0 in chunkOffsets.indices) {
                if (sampleIdx >= totalSamples) break
                val chunkNum1Based = chunkIndex0 + 1
                val stscRule = stscEntries.lastOrNull { chunkNum1Based >= it.firstChunk }
                val samplesInThisChunk = stscRule?.samplesPerChunk ?: if (chunkOffsets.size == 1) totalSamples else 1
                var runningOffsetInChunk = chunkOffsets[chunkIndex0]
                for (sInChunk in 0 until samplesInThisChunk) {
                    if (sampleIdx >= totalSamples) break
                    sampleFileOffsets[sampleIdx] = runningOffsetInChunk
                    val sz = sampleByteLengths[sampleIdx].coerceAtLeast(2)
                    runningOffsetInChunk += sz.toLong()
                    sampleIdx++
                }
            }
        }

        val chapters = mutableListOf<ParsedEmbeddedChapter>()
        var runningStartMs = 0L
        for (idx in 0 until totalSamples) {
            val durationMs = sampleDurationsMs[idx]
            val sampleOffset = sampleFileOffsets[idx]
            val maxSampleBytes = sampleByteLengths[idx].coerceIn(2, 2048)

            val chapterTitle = readQuickTimeSampleText(
                sampleFileOffset = sampleOffset,
                maxBytes = maxSampleBytes,
                bytes = bytes,
                baseFileOffset = baseFileOffset,
                channel = channel
            )?.takeIf { it.isNotBlank() } ?: "Глава ${idx + 1}"

            if (durationMs > 0L) {
                chapters.add(
                    ParsedEmbeddedChapter(
                        title = chapterTitle,
                        startTimeMs = runningStartMs,
                        durationMs = durationMs
                    )
                )
                runningStartMs += durationMs
            }
        }

        if (chapters.isNotEmpty() && totalDurationMs > runningStartMs) {
            val last = chapters.last()
            chapters[chapters.lastIndex] = last.copy(
                durationMs = (totalDurationMs - last.startTimeMs).coerceAtLeast(last.durationMs)
            )
        }
        return chapters
    }

    private fun readQuickTimeSampleText(
        sampleFileOffset: Long,
        maxBytes: Int,
        bytes: ByteArray,
        baseFileOffset: Long,
        channel: FileChannel?
    ): String? {
        if (sampleFileOffset < 0L) return null
        val sampleBytes: ByteArray = try {
            val localOffset = sampleFileOffset - baseFileOffset
            if (localOffset >= 0L && localOffset + 2L <= bytes.size.toLong()) {
                val len = minOf(maxBytes.toLong(), bytes.size.toLong() - localOffset).toInt()
                bytes.copyOfRange(localOffset.toInt(), localOffset.toInt() + len)
            } else if (channel != null && sampleFileOffset + 2L <= channel.size()) {
                val len = minOf(maxBytes.toLong(), channel.size() - sampleFileOffset).toInt()
                val buf = ByteBuffer.allocate(len)
                channel.position(sampleFileOffset)
                channel.read(buf)
                buf.array()
            } else {
                return null
            }
        } catch (_: Throwable) {
            return null
        }

        if (sampleBytes.size < 2) return null
        val declaredLen = ((sampleBytes[0].toInt() and 0xFF) shl 8) or (sampleBytes[1].toInt() and 0xFF)
        return if (declaredLen in 1..(sampleBytes.size - 2)) {
            decodeSmartString(sampleBytes, 2, declaredLen)
                .substringBefore('\u0000')
                .trim()
        } else {
            // Some encoders store plain UTF-8 text without the 2-byte tx3g length prefix
            decodeSmartString(sampleBytes, 0, sampleBytes.size)
                .substringBefore("encd")
                .substringBefore('\u0000')
                .filter { !it.isISOControl() }
                .trim()
        }
    }

    /**
     * Parses ID3v2.2 / ID3v2.3 / ID3v2.4 `CHAP` frames embedded in MP3 or ID3-wrapped M4B/M4A files.
     */
    internal fun parseId3v2ChapFrames(
        bytes: ByteArray,
        totalDurationMs: Long
    ): List<ParsedEmbeddedChapter> {
        if (bytes.size < 14) return emptyList()
        val version = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = minOf(getSynchsafeInt(bytes, 6) + 10, bytes.size)

        var pos = 10
        if ((flags and 0x40) != 0 && pos + 4 <= tagSize) {
            val extSize = if (version == 4) getSynchsafeInt(bytes, pos) else readInt32BE(bytes, pos)
            pos += extSize.coerceAtLeast(4)
        }

        val rawChapters = mutableListOf<Triple<Long, Long, String>>()
        while (pos + 10 <= tagSize) {
            val frameId = String(bytes, pos, 4, Charsets.ISO_8859_1)
            if (frameId.all { it == '\u0000' }) break

            val frameSize = if (version == 4) {
                getSynchsafeInt(bytes, pos + 4)
            } else {
                readInt32BE(bytes, pos + 4)
            }
            if (frameSize <= 0 || pos + 10 + frameSize > tagSize) break

            val frameStart = pos + 10
            val frameEnd = frameStart + frameSize
            if (frameId == "CHAP") {
                var idEnd = frameStart
                while (idEnd < frameEnd && bytes[idEnd] != 0.toByte()) {
                    idEnd++
                }
                val timeFieldStart = idEnd + 1
                if (timeFieldStart + 16 <= frameEnd) {
                    val startTimeMs = readUInt32BE(bytes, timeFieldStart)
                    val endTimeMs = readUInt32BE(bytes, timeFieldStart + 4)
                    var subPos = timeFieldStart + 16
                    var chapterTitle = ""
                    while (subPos + 10 <= frameEnd) {
                        val subId = String(bytes, subPos, 4, Charsets.ISO_8859_1)
                        val subSize = if (version == 4) {
                            getSynchsafeInt(bytes, subPos + 4)
                        } else {
                            readInt32BE(bytes, subPos + 4)
                        }
                        if (subSize <= 0 || subPos + 10 + subSize > frameEnd) break
                        if (subId == "TIT2" || subId == "TIT3") {
                            chapterTitle = decodeId3TextPayload(bytes, subPos + 10, subSize)
                            if (chapterTitle.isNotBlank()) break
                        }
                        subPos += 10 + subSize
                    }
                    val duration = if (endTimeMs > startTimeMs && endTimeMs != 0xFFFFFFFFL) {
                        endTimeMs - startTimeMs
                    } else {
                        0L
                    }
                    rawChapters.add(
                        Triple(
                            startTimeMs,
                            duration,
                            chapterTitle.ifBlank { "Глава ${rawChapters.size + 1}" }
                        )
                    )
                }
            }
            pos = frameEnd
        }

        if (rawChapters.isEmpty()) return emptyList()
        val sorted = rawChapters.sortedBy { it.first }
        return sorted.mapIndexed { idx, (startMs, explicitDur, title) ->
            val nextStart = sorted.getOrNull(idx + 1)?.first ?: totalDurationMs.coerceAtLeast(startMs + 60_000L)
            val resolvedDuration = if (explicitDur > 0L) explicitDur else (nextStart - startMs).coerceAtLeast(1_000L)
            ParsedEmbeddedChapter(
                title = title,
                startTimeMs = startMs,
                durationMs = resolvedDuration
            )
        }
    }

    internal fun parseId3v2Metadata(
        bytes: ByteArray,
        includeCover: Boolean = true
    ): Mp4TagMetadata {
        if (bytes.size < 14) return Mp4TagMetadata()
        val version = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = minOf(getSynchsafeInt(bytes, 6) + 10, bytes.size)

        var pos = 10
        if ((flags and 0x40) != 0 && pos + 4 <= tagSize && version >= 3) {
            val extSize = if (version == 4) getSynchsafeInt(bytes, pos) else readInt32BE(bytes, pos)
            pos += extSize.coerceAtLeast(4)
        }

        var title: String? = null
        var author: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var coverBytes: ByteArray? = null

        val headerLen = if (version == 2) 6 else 10
        while (pos + headerLen <= tagSize) {
            val frameId = if (version == 2) {
                String(bytes, pos, 3, Charsets.ISO_8859_1)
            } else {
                String(bytes, pos, 4, Charsets.ISO_8859_1)
            }
            if (frameId.all { it == '\u0000' } || frameId.any { it.code !in 32..90 }) break

            val frameSize = when (version) {
                2 -> ((bytes[pos + 3].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 4].toInt() and 0xFF) shl 8) or
                    (bytes[pos + 5].toInt() and 0xFF)
                4 -> {
                    val sync = getSynchsafeInt(bytes, pos + 4)
                    if (sync > 0 && pos + 10 + sync <= tagSize) sync else readInt32BE(bytes, pos + 4)
                }
                else -> readInt32BE(bytes, pos + 4)
            }
            if (frameSize <= 0 || pos + headerLen + frameSize > tagSize) break

            val payloadStart = pos + headerLen
            when (frameId) {
                "TIT2", "TT2" -> {
                    val decoded = decodeId3TextPayload(bytes, payloadStart, frameSize)
                    if (decoded.isNotBlank()) title = decoded
                }
                "TPE1", "TPE2", "TP1", "TP2" -> {
                    if (author == null) {
                        val decoded = decodeId3TextPayload(bytes, payloadStart, frameSize)
                        if (decoded.isNotBlank()) author = decoded
                    }
                }
                "TALB", "TAL" -> {
                    val decoded = decodeId3TextPayload(bytes, payloadStart, frameSize)
                    if (decoded.isNotBlank()) album = decoded
                }
                "TRCK", "TRK" -> {
                    val decoded = decodeId3TextPayload(bytes, payloadStart, frameSize)
                    val parsedTrack = decoded.substringBefore('/').trim().toIntOrNull()
                    if (parsedTrack != null && parsedTrack > 0) trackNumber = parsedTrack
                }
                "APIC", "PIC" -> {
                    if (includeCover && frameSize in 24..(12 * 1024 * 1024)) {
                        val candidate = extractApicImageBytes(bytes, payloadStart, frameSize)
                        if (candidate != null && (coverBytes == null || candidate.size > coverBytes.size)) {
                            coverBytes = candidate
                        }
                    }
                }
            }
            pos = payloadStart + frameSize
        }

        // Fallback: if includeCover is true and no APIC frame matched (e.g. non-standard frame layout or unsync header),
        // scan the ID3 tag range for an embedded JPEG/PNG image
        if (includeCover && coverBytes == null && tagSize > 64) {
            coverBytes = extractApicImageBytes(bytes, 10, tagSize - 10)
        }

        return Mp4TagMetadata(
            title = title,
            author = author,
            album = album,
            trackNumber = trackNumber,
            coverBytes = coverBytes
        )
    }

    internal fun decodeId3TextPayload(bytes: ByteArray, start: Int, length: Int): String {
        if (length <= 1 || start + length > bytes.size) return ""
        val encoding = bytes[start].toInt() and 0xFF
        val textBytesCount = length - 1
        val raw = when (encoding) {
            0 -> decodeSmartString(bytes, start + 1, textBytesCount)
            1 -> runCatching { String(bytes, start + 1, textBytesCount, Charsets.UTF_16) }.getOrDefault("")
            2 -> runCatching { String(bytes, start + 1, textBytesCount, Charsets.UTF_16BE) }.getOrDefault("")
            else -> runCatching { String(bytes, start + 1, textBytesCount, Charsets.UTF_8) }.getOrDefault("")
        }
        return raw.replace("\u0000", "").trim()
    }

    /**
     * Decodes a byte slice that may be UTF-8, UTF-16 with BOM, or Windows-1251 (CP1251) Cyrillic
     * (very common in Russian audiobooks when tag encoding is set to 0 / ISO-8859-1).
     */
    internal fun decodeSmartString(bytes: ByteArray, start: Int, length: Int): String {
        if (length <= 0 || start < 0 || start + length > bytes.size) return ""
        if (length >= 2) {
            val b0 = bytes[start].toInt() and 0xFF
            val b1 = bytes[start + 1].toInt() and 0xFF
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                return runCatching { String(bytes, start, length, Charsets.UTF_16) }
                    .getOrDefault("")
                    .replace("\u0000", "")
                    .trim()
            }
        }

        // Check if valid UTF-8 with multibyte sequences
        if (isValidUtf8(bytes, start, length)) {
            return String(bytes, start, length, Charsets.UTF_8).replace("\u0000", "").trim()
        }

        // If high bytes (0x80..0xFF) are not valid UTF-8, decode as Windows-1251 (Cyrillic)
        var highBytesCount = 0
        for (i in start until (start + length)) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0xC0..0xFF || b == 0xA8 || b == 0xB8) {
                highBytesCount++
            }
        }
        if (highBytesCount > 0) {
            return String(bytes, start, length, WIN1251_CHARSET).replace("\u0000", "").trim()
        }

        return String(bytes, start, length, Charsets.ISO_8859_1).replace("\u0000", "").trim()
    }

    private fun isValidUtf8(bytes: ByteArray, start: Int, length: Int): Boolean {
        var i = start
        val end = start + length
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= end) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    if ((b2 and 0xC0) != 0x80) return false
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= end) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    if ((b2 and 0xC0) != 0x80 || (b3 and 0xC0) != 0x80) return false
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= end) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    val b4 = bytes[i + 3].toInt() and 0xFF
                    if ((b2 and 0xC0) != 0x80 || (b3 and 0xC0) != 0x80 || (b4 and 0xC0) != 0x80) return false
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }

    private fun extractApicImageBytes(bytes: ByteArray, start: Int, length: Int): ByteArray? {
        val end = minOf(start + length, bytes.size)
        for (i in start until (end - 8)) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            // 1. JPEG magic: FF D8 FF (or ID3 unsynchronised FF 00 D8 FF)
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                return removeId3UnsynchronisationIfNeeded(bytes.copyOfRange(i, end))
            }
            if (b0 == 0xFF && b1 == 0x00 && b2 == 0xD8 && b3 == 0xFF) {
                return removeId3UnsynchronisationIfNeeded(bytes.copyOfRange(i, end))
            }
            // 2. PNG magic: 89 50 4E 47
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
                return bytes.copyOfRange(i, end)
            }
            // 3. WebP magic: "RIFF" .... "WEBP"
            if (b0 == 'R'.code && b1 == 'I'.code && b2 == 'F'.code && b3 == 'F'.code && i + 12 <= end) {
                if (bytes[i + 8].toInt().toChar() == 'W' &&
                    bytes[i + 9].toInt().toChar() == 'E' &&
                    bytes[i + 10].toInt().toChar() == 'B' &&
                    bytes[i + 11].toInt().toChar() == 'P'
                ) {
                    return bytes.copyOfRange(i, end)
                }
            }
        }
        return null
    }

    private fun removeId3UnsynchronisationIfNeeded(raw: ByteArray): ByteArray {
        var unsyncPairs = 0
        for (i in 0 until (raw.size - 1)) {
            if ((raw[i].toInt() and 0xFF) == 0xFF && (raw[i + 1].toInt() and 0xFF) == 0x00) {
                unsyncPairs++
            }
        }
        if (unsyncPairs == 0) return raw
        // Only de-unsynchronise if the header itself had FF 00 D8 or high ratio of FF 00
        val startsWithUnsyncJpeg = raw.size >= 4 &&
            (raw[0].toInt() and 0xFF) == 0xFF &&
            (raw[1].toInt() and 0xFF) == 0x00 &&
            (raw[2].toInt() and 0xFF) == 0xD8
        if (!startsWithUnsyncJpeg && unsyncPairs < 8) return raw

        val out = ByteArray(raw.size - unsyncPairs)
        var outIdx = 0
        var i = 0
        while (i < raw.size) {
            out[outIdx++] = raw[i]
            if (i + 1 < raw.size && (raw[i].toInt() and 0xFF) == 0xFF && (raw[i + 1].toInt() and 0xFF) == 0x00) {
                i += 2
            } else {
                i++
            }
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }

    private fun parseMp4BoxesForMetadata(
        bytes: ByteArray,
        start: Int,
        end: Int,
        includeCover: Boolean = true
    ): Mp4TagMetadata {
        var title: String? = null
        var author: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var durationMs: Long? = null
        var coverBytes: ByteArray? = null

        fun walk(s: Int, e: Int) {
            var pos = s
            while (pos + 8 <= e) {
                var boxSizeLong = readUInt32BE(bytes, pos)
                val type = readBoxType(bytes, pos + 4)
                var headerSkipBase = 8
                if (boxSizeLong == 1L && pos + 16 <= e) {
                    boxSizeLong = readInt64BE(bytes, pos + 8)
                    headerSkipBase = 16
                } else if (boxSizeLong == 0L) {
                    boxSizeLong = (e - pos).toLong()
                }

                // If a container box (`moov`, `udta`, `meta`, `ilst`) is larger than the buffer slice `e`,
                // clamp its end to `e` so we still descend into its children inside the available buffer!
                if (boxSizeLong < 8L || (pos + boxSizeLong > e && type !in CONTAINER_BOXES)) {
                    pos += 4
                    continue
                }
                val boxEnd = minOf(pos.toLong() + boxSizeLong, e.toLong()).toInt()

                when (type) {
                    "mvhd" -> {
                        if (pos + 28 <= boxEnd) {
                            val version = bytes[pos + 8].toInt() and 0xFF
                            if (version == 1 && pos + 36 <= boxEnd) {
                                val scale = readUInt32BE(bytes, pos + 28).coerceAtLeast(1L)
                                val dur = readInt64BE(bytes, pos + 32)
                                if (dur > 0) durationMs = (dur * 1000L) / scale
                            } else {
                                val scale = readUInt32BE(bytes, pos + 20).coerceAtLeast(1L)
                                val dur = readUInt32BE(bytes, pos + 24)
                                if (dur > 0) durationMs = (dur * 1000L) / scale
                            }
                        }
                    }
                    "©nam" -> {
                        extractIlstDataString(bytes, pos + 8, boxEnd)?.takeIf { it.isNotBlank() }?.let {
                            title = it
                        }
                    }
                    "©ART", "aART", "©wrt" -> {
                        if (author == null) {
                            extractIlstDataString(bytes, pos + 8, boxEnd)?.takeIf { it.isNotBlank() }?.let {
                                author = it
                            }
                        }
                    }
                    "©alb" -> {
                        extractIlstDataString(bytes, pos + 8, boxEnd)?.takeIf { it.isNotBlank() }?.let {
                            album = it
                        }
                    }
                    "trkn" -> {
                        if (trackNumber == null) {
                            trackNumber = extractIlstTrackNumber(bytes, pos + 8, boxEnd)
                        }
                    }
                    "covr" -> {
                        if (includeCover && coverBytes == null) {
                            coverBytes = extractIlstDataBytes(bytes, pos + 8, boxEnd)
                        }
                    }
                    in CONTAINER_BOXES -> {
                        val headerSkip = if (type == "meta") 12 else headerSkipBase
                        if (pos + headerSkip < boxEnd) {
                            walk(pos + headerSkip, boxEnd)
                        }
                    }
                }
                pos = boxEnd
            }
        }

        walk(start, end)
        return Mp4TagMetadata(
            title = title,
            author = author,
            album = album,
            trackNumber = trackNumber,
            durationMs = durationMs,
            coverBytes = coverBytes
        )
    }

    private fun extractIlstDataString(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos + 16 <= end) {
            val size = readUInt32BE(bytes, pos).toInt()
            val type = readBoxType(bytes, pos + 4)
            if (size < 16 || pos + size > end) break
            if (type == "data") {
                val payloadStart = pos + 16
                val payloadLen = size - 16
                if (payloadLen > 0) {
                    return decodeSmartString(bytes, payloadStart, payloadLen)
                }
            }
            pos += size
        }
        return null
    }

    private fun extractIlstTrackNumber(bytes: ByteArray, start: Int, end: Int): Int? {
        var pos = start
        while (pos + 16 <= end) {
            val size = readUInt32BE(bytes, pos).toInt()
            val type = readBoxType(bytes, pos + 4)
            if (size < 16 || pos + size > end) break
            if (type == "data") {
                val payloadStart = pos + 16
                val payloadLen = size - 16
                if (payloadLen >= 4 && payloadStart + 4 <= end) {
                    val trk = ((bytes[payloadStart + 2].toInt() and 0xFF) shl 8) or
                        (bytes[payloadStart + 3].toInt() and 0xFF)
                    if (trk > 0) return trk
                }
            }
            pos += size
        }
        return null
    }

    private fun extractIlstDataBytes(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        var pos = start
        while (pos + 16 <= end) {
            val size = readUInt32BE(bytes, pos).toInt()
            val type = readBoxType(bytes, pos + 4)
            if (size < 16 || pos + size > end) break
            if (type == "data") {
                val payloadStart = pos + 16
                val payloadLen = size - 16
                if (payloadLen in 16..(12 * 1024 * 1024)) {
                    return bytes.copyOfRange(payloadStart, payloadStart + payloadLen)
                }
            }
            pos += size
        }
        // Fallback: scan directly for JPEG/PNG/WebP magic bytes inside `covr` atom
        if (end - start > 24) {
            return extractApicImageBytes(bytes, start, end - start)
        }
        return null
    }

    private fun buildChapterListFromStartTimes(
        sortedStartTimes: List<Pair<Long, String>>,
        totalDurationMs: Long
    ): List<ParsedEmbeddedChapter> {
        val safeTotal = totalDurationMs.coerceAtLeast(
            (sortedStartTimes.lastOrNull()?.first ?: 0L) + 60_000L
        )
        return sortedStartTimes.mapIndexed { idx, (startMs, title) ->
            val nextStart = sortedStartTimes.getOrNull(idx + 1)?.first ?: safeTotal
            val duration = (nextStart - startMs).coerceAtLeast(1_000L)
            ParsedEmbeddedChapter(
                title = title,
                startTimeMs = startMs,
                durationMs = duration
            )
        }
    }

    private fun collectBoxesOfType(
        bytes: ByteArray,
        start: Int,
        end: Int,
        targetType: String,
        outRanges: MutableList<IntRange>
    ) {
        var pos = start
        while (pos + 8 <= end) {
            val sizeLong = readUInt32BE(bytes, pos)
            val type = readBoxType(bytes, pos + 4)
            if (sizeLong < 8 || pos + sizeLong > end) {
                pos += 4
                continue
            }
            val boxEnd = (pos + sizeLong).toInt()
            if (type == targetType) {
                outRanges.add(pos until boxEnd)
            } else if (type in CONTAINER_BOXES) {
                val skip = if (type == "meta") 12 else 8
                if (pos + skip < boxEnd) {
                    collectBoxesOfType(bytes, pos + skip, boxEnd, targetType, outRanges)
                }
            }
            pos = boxEnd
        }
    }

    private fun readBoxType(bytes: ByteArray, offset: Int): String {
        if (offset + 4 > bytes.size) return ""
        return String(bytes, offset, 4, Charsets.ISO_8859_1)
    }

    private fun readUInt32BE(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        return readUInt32BE(bytes, offset).toInt()
    }

    private fun readInt64BE(bytes: ByteArray, offset: Int): Long {
        if (offset + 8 > bytes.size) return 0L
        return (readUInt32BE(bytes, offset) shl 32) or (readUInt32BE(bytes, offset + 4) and 0xFFFFFFFFL)
    }

    private fun getSynchsafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }
}
