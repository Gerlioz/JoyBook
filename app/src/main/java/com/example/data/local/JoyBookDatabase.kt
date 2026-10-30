package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JoyBookDao {
    @Query("SELECT * FROM audiobooks ORDER BY lastPlayedTimestamp DESC, id ASC")
    fun getAllAudiobooks(): Flow<List<AudiobookEntity>>

    @Query("SELECT * FROM audiobooks ORDER BY lastPlayedTimestamp DESC, id ASC")
    suspend fun getAllAudiobooksOnce(): List<AudiobookEntity>

    @Query("SELECT * FROM audiobooks WHERE id = :id LIMIT 1")
    suspend fun getAudiobookById(id: Long): AudiobookEntity?

    @Query("SELECT COUNT(*) FROM audiobooks")
    suspend fun getAudiobookCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudiobook(audiobook: AudiobookEntity): Long

    @Update
    suspend fun updateAudiobook(audiobook: AudiobookEntity)

    @Query("UPDATE audiobooks SET title = :title, author = :author, narrator = '', coverImagePath = :coverPath WHERE id = :id")
    suspend fun updateBookMetadataAndCover(id: Long, title: String, author: String, coverPath: String?)

    @Query("UPDATE audiobooks SET currentPositionMs = :positionMs, lastPlayedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateProgress(id: Long, positionMs: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE audiobooks SET playbackSpeed = :speed WHERE id = :id")
    suspend fun updatePlaybackSpeed(id: Long, speed: Float)

    @Query("UPDATE audiobooks SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, isFavorite: Boolean)

    @Query("DELETE FROM audiobooks WHERE id = :id")
    suspend fun deleteAudiobook(id: Long)

    @Query("DELETE FROM audiobooks WHERE isBuiltIn = 1 OR title IN ('Хроники Янтарной Туманности', 'Тайна Полночного Фонаря', 'Шёпот Изумрудного Леса', 'Механика Времени: Осознанность', 'Полярный Дневник Капитана', 'Полярный дневник', 'Механика времени', 'Шепот Изумрудного леса', 'Тайна полуночного фонаря', 'Хроники янтарной туманности')")
    suspend fun deleteBuiltInAudiobooks()

    @Query("SELECT * FROM chapters WHERE audiobookId = :audiobookId ORDER BY chapterIndex ASC")
    fun getChaptersForBook(audiobookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE audiobookId = :audiobookId ORDER BY chapterIndex ASC")
    suspend fun getChaptersForBookOnce(audiobookId: Long): List<ChapterEntity>

    @Query("SELECT DISTINCT fileUri FROM chapters WHERE fileUri IS NOT NULL")
    suspend fun getAllChapterFileUrisOnce(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE audiobookId = :audiobookId")
    suspend fun deleteChaptersForBook(audiobookId: Long)

    @Query("DELETE FROM chapters")
    suspend fun deleteAllChapters()

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE audiobookId = :audiobookId ORDER BY positionMs ASC")
    fun getBookmarksForBook(audiobookId: Long): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmarks WHERE audiobookId = :audiobookId")
    suspend fun deleteBookmarksForBook(audiobookId: Long)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAllBookmarks()

    @Query("DELETE FROM audiobooks")
    suspend fun deleteAllAudiobooks()
}

@Database(
    entities = [AudiobookEntity::class, ChapterEntity::class, BookmarkEntity::class],
    version = 3,
    exportSchema = false
)
abstract class JoyBookDatabase : RoomDatabase() {
    abstract fun dao(): JoyBookDao

    companion object {
        @Volatile
        private var INSTANCE: JoyBookDatabase? = null

        fun getInstance(context: Context): JoyBookDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JoyBookDatabase::class.java,
                    "joybook_library.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
