package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.MeditationSession
import com.example.data.repository.SessionRepository
import com.example.ui.audio.ChimePlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val chimePlayer = ChimePlayer()

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

    private val _isGuided = MutableStateFlow(false)
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

        // Play meditation opening chime!
        chimePlayer.playChime()

        startTimer(totalSeconds)
    }

    private fun startTimer(totalSeconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_remainingSeconds.value > 0 && _isTimerRunning.value) {
                delay(1000)
                _elapsedSeconds.value += 1
                _remainingSeconds.value -= 1
            }
            if (_remainingSeconds.value == 0) {
                completeMeditation()
            }
        }
    }

    fun pauseMeditation() {
        _isTimerRunning.value = false
        timerJob?.cancel()
    }

    fun resumeMeditation() {
        _isTimerRunning.value = true
        startTimer(_remainingSeconds.value)
    }

    fun completeMeditationEarly() {
        timerJob?.cancel()
        completeMeditation()
    }

    private fun completeMeditation() {
        _isTimerRunning.value = false
        _currentSessionState.value = SessionState.COMPLETED

        // Play meditation ending chime!
        chimePlayer.playChime()

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
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    override fun onCleared() {
        super.onCleared()
        chimePlayer.release()
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
