package dev.learnai.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusStatsTest {
    private fun s(day: String, focusedMin: Int, mode: String = "soft", distractedMin: Int = 0, nags: Int = 0, awayMin: Int = 0) =
        FocusSession("${day}T10:00:00+01:00", "${day}T12:00:00+01:00", mode, focusedMin * 60, distractedMin * 60, awayMin * 60, nags)

    @Test fun streakTodayDays() {
        val today = LocalDate.parse("2026-11-09")
        val st = focusStats(listOf(
            s("2026-11-03", 70),                       // goal day, then a gap on the 4th
            s("2026-11-05", 61), s("2026-11-06", 30), s("2026-11-06", 40, "hard"),  // two sessions add up to the goal
            s("2026-11-07", 90, "focus"), s("2026-11-08", 60, distractedMin = 20, nags = 3),
            s("2026-11-09", 20),                       // today, goal not reached yet: streak counts from yesterday
        ), today)
        assertEquals(4, st.streak)        // 5, 6, 7, 8
        assertEquals(4, st.bestStreak)
        assertEquals(20, st.todayMin)
        assertEquals(14, st.days.size)
        assertEquals(LocalDate.parse("2026-11-06") to 70, st.days[10])  // index 13 = today
    }

    @Test fun xpPerFiveMinutesAndBreakdown() {
        // XP: (60·1 + 40·3 + 90·2) / 5 = 72; each session is 2 h of wall clock
        val t = focusTotals(listOf(s("2026-11-01", 60, distractedMin = 20, awayMin = 10, nags = 2),
            s("2026-11-02", 40, "hard"), s("2026-11-03", 90, "focus")))
        assertEquals(72, t.xp)
        assertEquals(3, t.sessions)
        assertEquals(6 * 3600, t.duration)
        assertEquals(190 * 60, t.focused)
        assertEquals(20 * 60, t.distracted)
        assertEquals(10 * 60, t.away)
        assertEquals((360 - 190 - 20 - 10) * 60, t.untracked)
        assertEquals(2, t.nags)
        assertEquals(12, sessionXp(s("2026-11-01", 60)))       // 60 min soft
        assertEquals(2, sessionXp(s("2026-11-01", 4, "hard")))  // 4 min hard: 4·3/5 = 2.4, rounded down
    }

    @Test fun levels() {
        assertEquals(0, focusStats(emptyList(), LocalDate.parse("2026-11-09")).streak)
        assertEquals(Level(1, 0, 100), levelOf(0))
        assertEquals(2, levelOf(100).level)
        assertEquals(Level(3, 300, 600), levelOf(541))
        // week / month levels: one level every step XP (same numbers as the laptop app's self-check)
        assertEquals(Level(1, 0, 20), periodLevel(0, WEEK_LEVEL_XP))
        assertEquals(Level(5, 80, 100), periodLevel(98, WEEK_LEVEL_XP))
        assertEquals(Level(2, 60, 120), periodLevel(98, MONTH_LEVEL_XP))
        assertEquals(LocalDate.of(2026, 11, 9), weekStart(LocalDate.of(2026, 11, 10)))
        assertEquals(LocalDate.of(2026, 11, 9), weekStart(LocalDate.of(2026, 11, 15)))  // Sunday: same week
        assertEquals(LocalDate.of(2026, 11, 9), weekStart(LocalDate.of(2026, 11, 9)))
    }

    @Test fun grades() {
        fun s(focused: Int, distracted: Int, away: Int = 0) =
            FocusSession("2026-11-10T09:00:00+01:00", "2026-11-10T10:00:00+01:00", "soft", focused, distracted, away, 0)
        assertEquals(Grade.Good, gradeOf(s(80, 20, away = 500)))  // away doesn't count against you
        assertEquals(Grade.Okay, gradeOf(s(79, 21)))
        assertEquals(Grade.Okay, gradeOf(s(60, 40)))
        assertEquals(Grade.Poor, gradeOf(s(59, 41)))
        assertEquals(Grade.Good, gradeOf(s(0, 0, away = 60)))  // only away: nothing to grade
        assertEquals(1, focusStats(listOf(s("2026-11-09", 60)), LocalDate.parse("2026-11-09")).streak)  // reached today: counts today
    }
}
