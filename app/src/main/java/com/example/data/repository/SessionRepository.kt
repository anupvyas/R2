package com.example.data.repository

import com.example.data.database.MeditationSession
import com.example.data.database.SessionDao
import kotlinx.coroutines.flow.Flow

class SessionRepository(private val sessionDao: SessionDao) {
    val allSessions: Flow<List<MeditationSession>> = sessionDao.getAllSessions()

    suspend fun insertSession(session: MeditationSession) {
        sessionDao.insertSession(session)
    }

    suspend fun clearAll() {
        sessionDao.deleteAllSessions()
    }
}
