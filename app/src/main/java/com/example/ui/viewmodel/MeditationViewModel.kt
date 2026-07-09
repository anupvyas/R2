package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.MeditationSession
import com.example.data.repository.SessionRepository
import com.example.ui.audio.GongPlayer
import com.example.ui.audio.InstructionPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

enum class DurationSelectionMode {
    PRESET,
    SLIDER
}

enum class SessionState {
    IDLE,
    ACTIVE,
    COMPLETED
}

class MeditationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: SessionRepository
    private val gongPlayer = GongPlayer(application)
    private val instructionPlayer = InstructionPlayer(application)
    private var activeTrackStartTimes: List<Int> = emptyList()
    private var lastPlayedTrackIndex = -1
    private var hasPlayedEndGong = false

    private val guidedTrackResIds = listOf(
        com.example.R.raw.o1_instruction,
        com.example.R.raw.o2_instruction,
        com.example.R.raw.o3_instruction,
        com.example.R.raw.o4_instruction,
        com.example.R.raw.o5_instruction,
        com.example.R.raw.o6_instruction,
        com.example.R.raw.o7_instruction,
        com.example.R.raw.o8_instruction,
        com.example.R.raw.o9_instruction,
        com.example.R.raw.o10_instruction,
        com.example.R.raw.o11_instruction,
        com.example.R.raw.o12_instruction,
        com.example.R.raw.o13_instruction
    )

    val allSessions: StateFlow<List<MeditationSession>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = SessionRepository(database.sessionDao())
        allSessions = repository.allSessions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    // Settings States
    private val _selectionMode = MutableStateFlow(DurationSelectionMode.PRESET)
    val selectionMode = _selectionMode.asStateFlow()

    private val _selectedPresetMinutes = MutableStateFlow(10)
    val selectedPresetMinutes = _selectedPresetMinutes.asStateFlow()

    private val _customSliderMinutes = MutableStateFlow(20) // Default custom slider
    val customSliderMinutes = _customSliderMinutes.asStateFlow()

    private val _isGuided = MutableStateFlow(true)
    val isGuided = _isGuided.asStateFlow()

    // Timer States
    private val _currentSessionState = MutableStateFlow(SessionState.IDLE)
    val currentSessionState = _currentSessionState.asStateFlow()

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning = _isTimerRunning.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds = _elapsedSeconds.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds = _remainingSeconds.asStateFlow()

    private var timerJob: Job? = null

    // Scoring States (Derived dynamically from sessions)
    val todayScore: StateFlow<Int> = allSessions.map { sessions ->
        sessions.filter { isToday(it.dateMillis) }.sumOf { it.points }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val yesterdayScore: StateFlow<Int> = allSessions.map { sessions ->
        sessions.filter { isYesterday(it.dateMillis) }.sumOf { it.points }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lifetimeScore: StateFlow<Int> = allSessions.map { sessions ->
        sessions.sumOf { it.points }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Preset options constant
    val presetOptions = listOf(10, 15, 30, 60, 75, 90, 120)

    fun selectPreset(minutes: Int) {
        _selectionMode.value = DurationSelectionMode.PRESET
        _selectedPresetMinutes.value = minutes
    }

    fun selectSlider(minutes: Int) {
        _selectionMode.value = DurationSelectionMode.SLIDER
        _customSliderMinutes.value = minutes
    }

    fun setGuided(guided: Boolean) {
        _isGuided.value = guided
        if (guided) {
            if (_customSliderMinutes.value < 10) {
                _customSliderMinutes.value = 10
            }
            if (_selectedPresetMinutes.value < 10) {
                _selectedPresetMinutes.value = 10
            }
        }
    }

    fun getGuidedTrackStartTimes(durationMinutes: Int): List<Int> {
        val context = getApplication<Application>()
        val trackDurations = guidedTrackResIds.map { resId ->
            getRawTrackDuration(context, resId)
        }
        // Gaps between tracks in seconds from the user's spreadsheet
        val baseGaps = listOf(2.0, 4.0, 7.0, 27.0, 18.0, 27.0, 27.0, 1.0, 18.0, 4.0, 18.0, 18.0)
        
        // For other durations, we scale the gaps proportionately.
        val scaleFactor = durationMinutes.toDouble() / 10.0
        
        val startTimes = mutableListOf<Int>()
        var currentOffset = getGongDurationSeconds().toDouble()
        
        for (i in trackDurations.indices) {
            startTimes.add(currentOffset.toInt())
            currentOffset += trackDurations[i]
            if (i < baseGaps.size) {
                currentOffset += baseGaps[i] * scaleFactor
            }
        }
        
        return startTimes
    }

    private fun getRawTrackDuration(context: android.content.Context, resId: Int): Int {
        // First, check if the raw file is empty or invalid to avoid blocking calls to MediaMetadataRetriever
        try {
            context.resources.openRawResourceFd(resId).use { fd ->
                if (fd == null || fd.length <= 0) {
                    return getFallbackTrackDuration(resId)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MeditationViewModel", "Error opening raw resource fd for $resId: ${e.message}")
            return getFallbackTrackDuration(resId)
        }

        val retriever = android.media.MediaMetadataRetriever()
        val uri = android.net.Uri.parse("android.resource://${context.packageName}/$resId")
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L
            ((durationMs + 500) / 1000).toInt()
        } catch (e: Exception) {
            android.util.Log.e("MeditationViewModel", "Error getting track duration for $resId: ${e.message}")
            getFallbackTrackDuration(resId)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }

    private fun getFallbackTrackDuration(resId: Int): Int {
        return when (resId) {
            com.example.R.raw.o1_instruction -> 44
            com.example.R.raw.o2_instruction -> 22
            com.example.R.raw.o3_instruction -> 28
            com.example.R.raw.o4_instruction -> 46
            com.example.R.raw.o5_instruction -> 19
            com.example.R.raw.o6_instruction -> 45
            com.example.R.raw.o7_instruction -> 30
            com.example.R.raw.o8_instruction -> 29
            com.example.R.raw.o9_instruction -> 21
            com.example.R.raw.o10_instruction -> 41
            com.example.R.raw.o11_instruction -> 36
            com.example.R.raw.o12_instruction -> 36
            com.example.R.raw.o13_instruction -> 14
            else -> 30
        }
    }

    fun getGongDurationSeconds(): Int {
        val context = getApplication<Application>()
        val resourceId = context.resources.getIdentifier("gong_c5", "raw", context.packageName)
        if (resourceId == 0) return 9
        
        val retriever = android.media.MediaMetadataRetriever()
        val uri = android.net.Uri.parse("android.resource://${context.packageName}/$resourceId")
        return try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L
            if (durationMs > 0) {
                ((durationMs + 500) / 1000).toInt()
            } else {
                9
            }
        } catch (e: Exception) {
            android.util.Log.e("MeditationViewModel", "Error getting gong duration: ${e.message}")
            9
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }

    fun startMeditation() {
        val durationMinutes = when (_selectionMode.value) {
            DurationSelectionMode.PRESET -> _selectedPresetMinutes.value
            DurationSelectionMode.SLIDER -> _customSliderMinutes.value
        }
        val totalSeconds = durationMinutes * 60
        _elapsedSeconds.value = 0
        _remainingSeconds.value = totalSeconds
        _isTimerRunning.value = true
        _currentSessionState.value = SessionState.ACTIVE
        lastPlayedTrackIndex = -1
        hasPlayedEndGong = false

        // Initialize active track start times in a background coroutine to prevent any UI freeze
        viewModelScope.launch {
            if (_isGuided.value) {
                activeTrackStartTimes = withContext(Dispatchers.IO) {
                    getGuidedTrackStartTimes(durationMinutes)
                }
            } else {
                activeTrackStartTimes = emptyList()
            }
            
            // Play the start gong first
            gongPlayer.playGong()
            
            if (_currentSessionState.value == SessionState.ACTIVE && _isTimerRunning.value) {
                startTimer(totalSeconds)
            }
        }
    }

    private fun startTimer(totalSeconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            // Check immediately on start
            checkAndPlayGuidedTrack(_elapsedSeconds.value)

            val gongDuration = getGongDurationSeconds()

            while (_remainingSeconds.value > 0 && _isTimerRunning.value) {
                delay(1000)
                _elapsedSeconds.value += 1
                _remainingSeconds.value -= 1
                
                // Play end gong gongDuration seconds before ending the session
                if (_remainingSeconds.value == gongDuration && !hasPlayedEndGong) {
                    hasPlayedEndGong = true
                    gongPlayer.playGong()
                }

                checkAndPlayGuidedTrack(_elapsedSeconds.value)
            }
            if (_remainingSeconds.value == 0) {
                completeMeditation()
            }
        }
    }

    private fun checkAndPlayGuidedTrack(elapsed: Int) {
        if (_isGuided.value) {
            var trackIndexToPlay = -1
            for (i in activeTrackStartTimes.indices) {
                if (elapsed >= activeTrackStartTimes[i]) {
                    trackIndexToPlay = i
                }
            }
            if (trackIndexToPlay != -1 && trackIndexToPlay > lastPlayedTrackIndex) {
                lastPlayedTrackIndex = trackIndexToPlay
                val resId = guidedTrackResIds.getOrNull(trackIndexToPlay)
                if (resId != null && resId != 0) {
                    instructionPlayer.playTrack(resId)
                }
            }
        }
    }

    fun pauseMeditation() {
        _isTimerRunning.value = false
        timerJob?.cancel()
        instructionPlayer.pause()
    }

    fun resumeMeditation() {
        _isTimerRunning.value = true
        instructionPlayer.resume()
        startTimer(_remainingSeconds.value)
    }

    fun completeMeditationEarly() {
        timerJob?.cancel()
        completeMeditation()
    }

    private fun completeMeditation() {
        _isTimerRunning.value = false
        _currentSessionState.value = SessionState.COMPLETED
        instructionPlayer.stop()
        
        // Play the end gong only if not already played
        if (!hasPlayedEndGong) {
            hasPlayedEndGong = true
            gongPlayer.playGong()
        }

        val secondsCompleted = _elapsedSeconds.value
        // Calculate points: 1 minute = 1 point. Let's award 1 point for any session >= 30 seconds
        val pointsEarned = if (secondsCompleted >= 30) {
            (secondsCompleted + 30) / 60
        } else {
            0
        }

        if (pointsEarned > 0) {
            viewModelScope.launch {
                val session = MeditationSession(
                    dateMillis = System.currentTimeMillis(),
                    durationMinutes = (secondsCompleted + 59) / 60, // rounded up minutes
                    points = pointsEarned,
                    isGuided = _isGuided.value
                )
                repository.insertSession(session)
            }
        }
    }

    /**
     * Allows adding points manually to either Today or Yesterday (Previous Day) for flexibility.
     */
    fun logManualSession(minutes: Int, isYesterday: Boolean) {
        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            if (isYesterday) {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }
            val session = MeditationSession(
                dateMillis = calendar.timeInMillis,
                durationMinutes = minutes,
                points = minutes,
                isGuided = _isGuided.value,
                note = "Manual Log"
            )
            repository.insertSession(session)
        }
    }

    fun resetSession() {
        _currentSessionState.value = SessionState.IDLE
        _elapsedSeconds.value = 0
        _remainingSeconds.value = 0
        _isTimerRunning.value = false
        timerJob?.cancel()
        instructionPlayer.stop()
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    override fun onCleared() {
        super.onCleared()
        gongPlayer.release()
        instructionPlayer.release()
    }

    // Helper functions to identify date relative to User's time
    private fun isToday(timestamp: Long): Boolean {
        val sessionCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val todayCal = Calendar.getInstance()
        return sessionCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                sessionCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(timestamp: Long): Boolean {
        val sessionCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return sessionCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
                sessionCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    }
}
