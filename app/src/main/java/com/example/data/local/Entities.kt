package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "audiobooks")
data class AudiobookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val audioFormat: String, // "MP3", "M4A", "M4B", "AMR", "WAV"
    val fileUri: String,
    val isBuiltIn: Boolean = true,
    val coverArtStyle: Int = 0, // 0..4 bespoke procedural cover themes
    val coverImagePath: String? = null, // Embedded album art from audio file tags (ID3/MP4)
    val durationMs: Long,
    val currentPositionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isFavorite: Boolean = false,
    val lastPlayedTimestamp: Long = 0L,
    val bitrateKbps: Int = 192,
    val sampleRateHz: Int = 44100
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = AudiobookEntity::class,
            parentColumns = ["id"],
            childColumns = ["audiobookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("audiobookId")]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val audiobookId: Long,
    val chapterIndex: Int,
    val title: String,
    val startTimeMs: Long,
    val durationMs: Long,
    val fileUri: String? = null // For multi-file audiobooks, specifies the file URI for this chapter
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = AudiobookEntity::class,
            parentColumns = ["id"],
            childColumns = ["audiobookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("audiobookId")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val audiobookId: Long,
    val audiobookTitle: String,
    val positionMs: Long,
    val chapterTitle: String,
    val note: String,
    val createdAt: Long = System.currentTimeMillis()
)
