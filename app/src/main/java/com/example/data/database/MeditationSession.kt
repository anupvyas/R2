package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meditation_sessions")
data class MeditationSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMillis: Long, // timestamp when session was completed
    val durationMinutes: Int, // duration in minutes
    val points: Int, // 1 minute = 1 point
    val isGuided: Boolean, // guided or unguided
    val note: String = "" // quick review or note
)
