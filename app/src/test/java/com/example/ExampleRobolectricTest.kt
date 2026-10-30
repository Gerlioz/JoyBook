package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.audio.M4bChapterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read string from context and verify MainActivity launches`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("JoyBook", appName)

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("bottom_navigation_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("library_screen_list").assertIsDisplayed()
    }

    @Test
    fun `parse M4B Nero chpl chapter box accurately`() {
        val title1 = "Глава 1. Пролог".toByteArray(StandardCharsets.UTF_8)
        val title2 = "Глава 2. Путешествие".toByteArray(StandardCharsets.UTF_8)
        val payloadSize = 9 + (8 + 1 + title1.size) + (8 + 1 + title2.size)
        val totalBoxSize = 8 + payloadSize

        val buffer = ByteBuffer.allocate(totalBoxSize + 16).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(totalBoxSize)
        buffer.put("chpl".toByteArray(StandardCharsets.US_ASCII))
        buffer.put(1.toByte()) // version 1
        buffer.put(byteArrayOf(0, 0, 0)) // flags
        buffer.put(0.toByte()) // reserved
        buffer.putInt(2) // 2 chapters

        // Chapter 1 at 0 ms (0 * 10_000L)
        buffer.putLong(0L)
        buffer.put(title1.size.toByte())
        buffer.put(title1)

        // Chapter 2 at 60_000 ms (60_000 * 10_000L = 600_000_000L)
        buffer.putLong(600_000_000L)
        buffer.put(title2.size.toByte())
        buffer.put(title2)

        val chapters = M4bChapterParser.parseIsoBmffChapters(buffer.array(), 150_000L)
        assertEquals(2, chapters.size)
        assertEquals("Глава 1. Пролог", chapters[0].title)
        assertEquals(0L, chapters[0].startTimeMs)
        assertEquals(60_000L, chapters[0].durationMs)
        assertEquals("Глава 2. Путешествие", chapters[1].title)
        assertEquals(60_000L, chapters[1].startTimeMs)
        assertTrue(chapters[1].durationMs == 90_000L)
    }

    @Test
    fun `parse ID3v2 CHAP chapter tags accurately`() {
        val chapterTitleBytes = "Введение в тайну".toByteArray(StandardCharsets.UTF_8)
        val elemId = "chp1\u0000".toByteArray(StandardCharsets.ISO_8859_1)

        // TIT2 subframe: 4 id + 4 size + 2 flags + 1 encoding + text
        val tit2Size = 1 + chapterTitleBytes.size
        val tit2Buf = ByteBuffer.allocate(10 + tit2Size).order(ByteOrder.BIG_ENDIAN)
        tit2Buf.put("TIT2".toByteArray(StandardCharsets.US_ASCII))
        tit2Buf.putInt(tit2Size)
        tit2Buf.putShort(0)
        tit2Buf.put(3.toByte()) // UTF-8
        tit2Buf.put(chapterTitleBytes)

        // CHAP payload: elemId + 4 start + 4 end + 4 startOff + 4 endOff + tit2
        val chapPayloadSize = elemId.size + 16 + tit2Buf.capacity()
        val chapBuf = ByteBuffer.allocate(10 + chapPayloadSize).order(ByteOrder.BIG_ENDIAN)
        chapBuf.put("CHAP".toByteArray(StandardCharsets.US_ASCII))
        chapBuf.putInt(chapPayloadSize)
        chapBuf.putShort(0)
        chapBuf.put(elemId)
        chapBuf.putInt(0) // start 0 ms
        chapBuf.putInt(30_000) // end 30_000 ms
        chapBuf.putInt(-1) // start offset
        chapBuf.putInt(-1) // end offset
        chapBuf.put(tit2Buf.array())

        val tagSize = chapBuf.capacity()
        // ID3 header: "ID3" + major 3 + rev 0 + flags 0 + 4 synchsafe bytes
        val id3Buf = ByteBuffer.allocate(10 + tagSize).order(ByteOrder.BIG_ENDIAN)
        id3Buf.put("ID3".toByteArray(StandardCharsets.US_ASCII))
        id3Buf.put(3.toByte())
        id3Buf.put(0.toByte())
        id3Buf.put(0.toByte())
        // Synchsafe integer for tagSize
        id3Buf.put(((tagSize shr 21) and 0x7F).toByte())
        id3Buf.put(((tagSize shr 14) and 0x7F).toByte())
        id3Buf.put(((tagSize shr 7) and 0x7F).toByte())
        id3Buf.put((tagSize and 0x7F).toByte())
        id3Buf.put(chapBuf.array())

        val chapters = M4bChapterParser.parseId3v2Chapters(id3Buf.array(), 100_000L)
        assertEquals(1, chapters.size)
        assertEquals("Введение в тайну", chapters[0].title)
        assertEquals(0L, chapters[0].startTimeMs)
        assertEquals(30_000L, chapters[0].durationMs)
    }

    @Test
    fun `group untagged audio files by folder hierarchy Author slash BookTitle`() {
        val file1 = com.example.data.storage.ScannedAudioFile(
            uri = android.net.Uri.parse("content://media/1"),
            title = "01",
            author = "Папка: Стивен Кинг/Сияние",
            album = "",
            trackNumber = 1,
            displayName = "01.mp3",
            folderName = "Сияние",
            relativePath = "Стивен Кинг/Сияние",
            format = "MP3",
            durationMs = 600_000L,
            sizeBytes = 10_000_000L,
            bitrateKbps = 128,
            dateModifiedMs = System.currentTimeMillis()
        )
        val file2 = com.example.data.storage.ScannedAudioFile(
            uri = android.net.Uri.parse("content://media/2"),
            title = "02",
            author = "Папка: Стивен Кинг/Сияние",
            album = "",
            trackNumber = 2,
            displayName = "02.mp3",
            folderName = "Сияние",
            relativePath = "Стивен Кинг/Сияние",
            format = "MP3",
            durationMs = 900_000L,
            sizeBytes = 15_000_000L,
            bitrateKbps = 128,
            dateModifiedMs = System.currentTimeMillis()
        )

        // Single file in its own folder
        val singleFile = com.example.data.storage.ScannedAudioFile(
            uri = android.net.Uri.parse("content://media/3"),
            title = "book",
            author = "Папка: Агата Кристи/Десять негритят",
            album = "",
            trackNumber = 0,
            displayName = "book.m4b",
            folderName = "Десять негритят",
            relativePath = "Агата Кристи/Десять негритят",
            format = "M4B",
            durationMs = 3_600_000L,
            sizeBytes = 50_000_000L,
            bitrateKbps = 192,
            dateModifiedMs = System.currentTimeMillis()
        )

        val groups = com.example.data.storage.StorageAudioScanner.groupScannedFiles(listOf(file1, file2, singleFile))
        assertEquals(2, groups.size)

        val shining = groups.find { it.title == "Сияние" }
        assertTrue(shining != null)
        assertEquals("Стивен Кинг", shining?.author)
        assertEquals(2, shining?.files?.size)
        assertTrue(shining?.isMultiFile == true)

        val agatha = groups.find { it.title == "Десять негритят" }
        assertTrue(agatha != null)
        assertEquals("Агата Кристи", agatha?.author)
        assertEquals(1, agatha?.files?.size)
        assertTrue(agatha?.isMultiFile == false)
    }

    @Test
    fun `verify storage search button is displayed on library screen`() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("storage_search_button").assertIsDisplayed()
    }

    @Test
    fun `parse M4B ilst metadata atoms accurately`() {
        val titleBytes = "Мастер и Маргарита".toByteArray(StandardCharsets.UTF_8)
        val authorBytes = "Михаил Булгаков".toByteArray(StandardCharsets.UTF_8)

        // Build ©nam atom with child data atom
        val namDataSize = 16 + titleBytes.size
        val namAtomSize = 8 + namDataSize
        // Build ©ART atom with child data atom
        val artDataSize = 16 + authorBytes.size
        val artAtomSize = 8 + artDataSize

        val ilstSize = 8 + namAtomSize + artAtomSize
        val buf = ByteBuffer.allocate(ilstSize + 8).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(ilstSize)
        buf.put("ilst".toByteArray(StandardCharsets.ISO_8859_1))

        // ©nam
        buf.putInt(namAtomSize)
        buf.put("©nam".toByteArray(StandardCharsets.ISO_8859_1))
        buf.putInt(namDataSize)
        buf.put("data".toByteArray(StandardCharsets.ISO_8859_1))
        buf.putInt(1) // UTF-8 text type flag
        buf.putInt(0) // locale
        buf.put(titleBytes)

        // ©ART
        buf.putInt(artAtomSize)
        buf.put("©ART".toByteArray(StandardCharsets.ISO_8859_1))
        buf.putInt(artDataSize)
        buf.put("data".toByteArray(StandardCharsets.ISO_8859_1))
        buf.putInt(1)
        buf.putInt(0)
        buf.put(authorBytes)

        val meta = M4bChapterParser.parseMetadataFromBytes(buf.array())
        assertEquals("Мастер и Маргарита", meta.title)
        assertEquals("Михаил Булгаков", meta.author)
    }

    @Test
    fun `groupScannedFiles produces strictly unique groupIds for loose files in root and device memory`() {
        val looseFiles = listOf(
            com.example.data.storage.ScannedAudioFile(
                uri = android.net.Uri.parse("content://media/loose1"),
                title = "Книга Первая",
                author = "Папка: Корневая",
                album = "",
                trackNumber = 0,
                displayName = "book1.mp3",
                folderName = "Выбранная папка",
                relativePath = "",
                format = "MP3",
                durationMs = 1200_000L,
                sizeBytes = 15_000_000L,
                bitrateKbps = 128,
                dateModifiedMs = 1000L
            ),
            com.example.data.storage.ScannedAudioFile(
                uri = android.net.Uri.parse("content://media/loose2"),
                title = "Книга Вторая",
                author = "Папка: Корневая",
                album = "",
                trackNumber = 0,
                displayName = "book2.m4b",
                folderName = "Выбранная папка",
                relativePath = "",
                format = "M4B",
                durationMs = 2400_000L,
                sizeBytes = 30_000_000L,
                bitrateKbps = 128,
                dateModifiedMs = 2000L
            ),
            com.example.data.storage.ScannedAudioFile(
                uri = android.net.Uri.parse("content://media/loose3"),
                title = "Третья Аудиокнига",
                author = "Аудиофайл (Память телефона)",
                album = "",
                trackNumber = 0,
                displayName = "book3.m4a",
                folderName = "Память телефона",
                relativePath = "",
                format = "M4A",
                durationMs = 1800_000L,
                sizeBytes = 20_000_000L,
                bitrateKbps = 128,
                dateModifiedMs = 3000L
            )
        )

        val groups = com.example.data.storage.StorageAudioScanner.groupScannedFiles(looseFiles)
        assertEquals(3, groups.size)
        val uniqueIds = groups.map { it.groupId }.toSet()
        assertEquals("Every group must have a unique groupId to prevent LazyColumn crash", 3, uniqueIds.size)
        assertTrue(groups.none { it.title == "Выбранная папка" || it.title == "Память телефона" })
    }

    @Test
    fun `verify CD_CASE cover style and ShakeSensitivity levels exist with expected Russian labels`() {
        assertEquals("Прозрачный кейс компакт-диска", com.example.data.preferences.CoverFrameStyle.CD_CASE.label)
        assertEquals("Слабый", com.example.data.preferences.ShakeSensitivity.LOW.label)
        assertEquals("Умеренно", com.example.data.preferences.ShakeSensitivity.MEDIUM.label)
        assertEquals("Сильно", com.example.data.preferences.ShakeSensitivity.HIGH.label)
        assertTrue(com.example.data.preferences.ShakeSensitivity.LOW.thresholdG < com.example.data.preferences.ShakeSensitivity.MEDIUM.thresholdG)
        assertTrue(com.example.data.preferences.ShakeSensitivity.MEDIUM.thresholdG < com.example.data.preferences.ShakeSensitivity.HIGH.thresholdG)
    }

    @Test
    fun `BookTitleCleaner strips author name, release year, and narrator tags while preserving numeric titles`() {
        assertEquals(
            "Сияние",
            com.example.data.storage.BookTitleCleaner.cleanBookTitle(
                "Стивен Кинг - Сияние (2019) [Читает Игорь Князев]",
                "Стивен Кинг"
            )
        )
        assertEquals(
            "Мастер и Маргарита",
            com.example.data.storage.BookTitleCleaner.cleanBookTitle(
                "Булгаков Михаил - Мастер и Маргарита - 2021 г.",
                "Михаил Булгаков"
            )
        )
        assertEquals(
            "Пикник на обочине",
            com.example.data.storage.BookTitleCleaner.cleanBookTitle(
                "[2018] Пикник на обочине - Аркадий и Борис Стругацкие (Читает: Левашев)",
                "Аркадий и Борис Стругацкие"
            )
        )
        assertEquals(
            "1984",
            com.example.data.storage.BookTitleCleaner.cleanBookTitle(
                "Джордж Оруэлл - 1984",
                "Джордж Оруэлл"
            )
        )
        assertEquals(
            "Метро 2033",
            com.example.data.storage.BookTitleCleaner.cleanBookTitle(
                "Дмитрий Глуховский - Метро 2033 (2009)",
                "Дмитрий Глуховский"
            )
        )
    }

    @Test
    fun `groupScannedFiles keeps MP3 tracks with inconsistent album tags and CD subfolders in one book per folder`() {
        val tracks = listOf(
            com.example.data.storage.ScannedAudioFile(
                uri = android.net.Uri.parse("content://media/mp3_1"),
                title = "Глава 01",
                author = "",
                album = "Глава 01",
                trackNumber = 1,
                displayName = "01.mp3",
                folderName = "CD1",
                relativePath = "Audiobooks/Братья Стругацкие/Понедельник начинается в субботу/CD1",
                format = "MP3",
                durationMs = 600_000L,
                sizeBytes = 10_000_000L,
                bitrateKbps = 128,
                dateModifiedMs = 1000L
            ),
            com.example.data.storage.ScannedAudioFile(
                uri = android.net.Uri.parse("content://media/mp3_2"),
                title = "Глава 02",
                author = "",
                album = "Глава 02",
                trackNumber = 2,
                displayName = "02.mp3",
                folderName = "CD1",
                relativePath = "Audiobooks/Братья Стругацкие/Понедельник начинается в субботу/CD1",
                format = "MP3",
                durationMs = 700_000L,
                sizeBytes = 11_000_000L,
                bitrateKbps = 128,
                dateModifiedMs = 1000L
            ),
            com.example.data.storage.ScannedAudioFile(
                uri = android.net.Uri.parse("content://media/mp3_3"),
                title = "03_glava",
                author = "",
                album = "",
                trackNumber = 1,
                displayName = "03.mp3",
                folderName = "CD2",
                relativePath = "Audiobooks/Братья Стругацкие/Понедельник начинается в субботу/CD2",
                format = "MP3",
                durationMs = 800_000L,
                sizeBytes = 12_000_000L,
                bitrateKbps = 128,
                dateModifiedMs = 1000L
            )
        )

        val groups = com.example.data.storage.StorageAudioScanner.groupScannedFiles(tracks)
        assertEquals("All MP3 files in the same book folder/CD subfolders must be grouped into 1 book", 1, groups.size)
        val book = groups.first()
        assertEquals("Понедельник начинается в субботу", book.title)
        assertEquals("Братья Стругацкие", book.author)
        assertEquals(3, book.files.size)
        assertEquals(listOf("01.mp3", "02.mp3", "03.mp3"), book.files.map { it.displayName })
    }
}


