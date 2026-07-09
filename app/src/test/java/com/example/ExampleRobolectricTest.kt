package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("RRR - Relax Reset Rise", appName)
  }

  @Test
  fun printTrackDurations() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val rawIds = listOf(
        R.raw.gong_c5,
        R.raw.o1_instruction,
        R.raw.o2_instruction,
        R.raw.o3_instruction,
        R.raw.o4_instruction,
        R.raw.o5_instruction,
        R.raw.o6_instruction,
        R.raw.o7_instruction,
        R.raw.o8_instruction,
        R.raw.o9_instruction,
        R.raw.o10_instruction,
        R.raw.o11_instruction,
        R.raw.o12_instruction,
        R.raw.o13_instruction
    )
    for (i in rawIds.indices) {
        val retriever = android.media.MediaMetadataRetriever()
        val uri = android.net.Uri.parse("android.resource://" + context.packageName + "/" + rawIds[i])
        try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L
            println("TRACK_DURATION_LOG: Track ${i+1} duration in ms: $durationMs (${durationMs / 1000.0} seconds)")
        } catch (e: Exception) {
            println("TRACK_DURATION_LOG: Failed to get duration for track ${i+1}: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }
  }

  @Test
  fun testGuidedTrackStartTimes() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.viewmodel.MeditationViewModel(application)
    val startTimes = viewModel.getGuidedTrackStartTimes(10)
    println("TRACK_START_TIMES_LOG: Start times for 10-minute session (in seconds): $startTimes")
    
    // Calculate the gaps between tracks in the generated start times
    val trackDurations = listOf(
        R.raw.o1_instruction, R.raw.o2_instruction, R.raw.o3_instruction,
        R.raw.o4_instruction, R.raw.o5_instruction, R.raw.o6_instruction,
        R.raw.o7_instruction, R.raw.o8_instruction, R.raw.o9_instruction,
        R.raw.o10_instruction, R.raw.o11_instruction, R.raw.o12_instruction,
        R.raw.o13_instruction
    ).map { resId ->
        val retriever = android.media.MediaMetadataRetriever()
        val uri = android.net.Uri.parse("android.resource://" + application.packageName + "/" + resId)
        try {
            retriever.setDataSource(application, uri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLong() ?: 0L
            ((durationMs + 500) / 1000).toInt()
        } catch (e: Exception) {
            0
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }

    val actualGaps = mutableListOf<Int>()
    for (i in 0 until startTimes.size - 1) {
        val gap = startTimes[i + 1] - startTimes[i] - trackDurations[i]
        actualGaps.add(gap)
    }
    println("TRACK_START_TIMES_LOG: Calculated gaps between tracks: $actualGaps")
    
    // Expected gaps: listOf(2, 4, 7, 27, 18, 27, 27, 1, 18, 4, 18, 18)
    val expectedGaps = listOf(2, 4, 7, 27, 18, 27, 27, 1, 18, 4, 18, 18)
    for (i in expectedGaps.indices) {
        assertEquals("Gap after track O${i+1} mismatch", expectedGaps[i], actualGaps[i])
    }
  }

  @Test
  fun testDailyStreakCalculation() {
    val application = ApplicationProvider.getApplicationContext<android.app.Application>()
    val viewModel = com.example.ui.viewmodel.MeditationViewModel(application)

    val now = System.currentTimeMillis()
    val oneDayMs = 24 * 60 * 60 * 1000L

    // Case 1: Empty sessions -> streak should be 0
    assertEquals(0, viewModel.calculateStreak(emptyList()))

    // Case 2: One session today -> streak should be 1
    val sessionToday = com.example.data.database.MeditationSession(
        dateMillis = now,
        durationMinutes = 10,
        points = 10,
        isGuided = true
    )
    assertEquals(1, viewModel.calculateStreak(listOf(sessionToday)))

    // Case 3: One session yesterday -> streak should be 1
    val sessionYesterday = com.example.data.database.MeditationSession(
        dateMillis = now - oneDayMs,
        durationMinutes = 10,
        points = 10,
        isGuided = true
    )
    assertEquals(1, viewModel.calculateStreak(listOf(sessionYesterday)))

    // Case 4: Sessions on consecutive days: today and yesterday -> streak should be 2
    assertEquals(2, viewModel.calculateStreak(listOf(sessionToday, sessionYesterday)))

    // Case 5: Sessions on consecutive days: today, yesterday, and 2 days ago -> streak should be 3
    val session2DaysAgo = com.example.data.database.MeditationSession(
        dateMillis = now - 2 * oneDayMs,
        durationMinutes = 10,
        points = 10,
        isGuided = true
    )
    assertEquals(3, viewModel.calculateStreak(listOf(sessionToday, sessionYesterday, session2DaysAgo)))

    // Case 6: Session today and 2 days ago (gap yesterday) -> streak should be 1
    assertEquals(1, viewModel.calculateStreak(listOf(sessionToday, session2DaysAgo)))

    // Case 7: Session yesterday and 2 days ago (gap today) -> streak should be 2
    assertEquals(2, viewModel.calculateStreak(listOf(sessionYesterday, session2DaysAgo)))

    // Case 8: Session 2 days ago and 3 days ago (gap today and yesterday) -> streak should be 0
    val session3DaysAgo = com.example.data.database.MeditationSession(
        dateMillis = now - 3 * oneDayMs,
        durationMinutes = 10,
        points = 10,
        isGuided = true
    )
    assertEquals(0, viewModel.calculateStreak(listOf(session2DaysAgo, session3DaysAgo)))
  }
}
