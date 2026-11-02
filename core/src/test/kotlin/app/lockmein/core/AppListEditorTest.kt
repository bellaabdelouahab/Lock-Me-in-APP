package app.lockmein.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppListEditorTest {
    private val zone = ZoneId.of("Africa/Casablanca")
    private val schedule = Schedule(ScheduleConfig(), { LocalTime.of(5, 30) }, zone)
    private val editor = AppListEditor(ChangeGate(schedule))

    private fun at(date: String, time: String): Instant = ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    private val sundayWindow = at("2026-11-01", "20:30")
    private val mondayNoon = at("2026-11-02", "12:00")
    private val untouchable = setOf("com.android.settings", "app.lockmein")

    private fun edit(list: AppList, op: ListOp, current: Set<String>, pkg: String, now: Instant, emergencyUntil: Instant? = null) =
        editor.edit(list, op, current, pkg, untouchable, now, networkTime = now, emergencyUntil = emergencyUntil)

    @Test
    fun `which edits open the phone up and which close it more`() {
        assertEquals(ChangeKind.LOOSEN, editor.kindOf(AppList.NIGHT_ALLOWED, ListOp.ADD))
        assertEquals(ChangeKind.TIGHTEN, editor.kindOf(AppList.NIGHT_ALLOWED, ListOp.REMOVE))
        assertEquals(ChangeKind.TIGHTEN, editor.kindOf(AppList.ALWAYS_BLOCKED, ListOp.ADD))
        assertEquals(ChangeKind.LOOSEN, editor.kindOf(AppList.ALWAYS_BLOCKED, ListOp.REMOVE))
    }

    @Test
    fun `adding a night app works in the Sunday window and not on a Monday`() {
        val inWindow = edit(AppList.NIGHT_ALLOWED, ListOp.ADD, setOf("deezer"), "spotify", sundayWindow)
        assertIs<Decision.Allowed>(inWindow.decision)
        assertEquals(setOf("deezer", "spotify"), inWindow.list)

        val monday = edit(AppList.NIGHT_ALLOWED, ListOp.ADD, setOf("deezer"), "spotify", mondayNoon)
        assertIs<Decision.Denied>(monday.decision)
        assertEquals(setOf("deezer"), monday.list)
    }

    @Test
    fun `removing a night app and blocking an app work at any time`() {
        val removed = edit(AppList.NIGHT_ALLOWED, ListOp.REMOVE, setOf("deezer", "spotify"), "spotify", mondayNoon)
        assertEquals(setOf("deezer"), removed.list)
        val blocked = edit(AppList.ALWAYS_BLOCKED, ListOp.ADD, emptySet(), "com.instagram.android", mondayNoon)
        assertEquals(setOf("com.instagram.android"), blocked.list)
    }

    @Test
    fun `unblocking an app needs the window`() {
        val monday = edit(AppList.ALWAYS_BLOCKED, ListOp.REMOVE, setOf("x"), "x", mondayNoon)
        assertIs<Decision.Denied>(monday.decision)
        assertEquals(setOf("x"), monday.list)
        assertEquals(emptySet(), edit(AppList.ALWAYS_BLOCKED, ListOp.REMOVE, setOf("x"), "x", sundayWindow).list)
    }

    @Test
    fun `an emergency window allows loosening on a Monday`() {
        val until = mondayNoon.plus(Duration.ofMinutes(60))
        val result = edit(AppList.NIGHT_ALLOWED, ListOp.ADD, emptySet(), "spotify", mondayNoon.plusSeconds(30), emergencyUntil = until)
        assertEquals(setOf("spotify"), result.list)
    }

    @Test
    fun `protected packages and this app cannot be put on a list, at any time`() {
        for (pkg in untouchable) {
            for (list in AppList.entries) {
                for (op in ListOp.entries) {
                    val result = edit(list, op, emptySet(), pkg, sundayWindow)
                    assertIs<Decision.Denied>(result.decision, "$list $op $pkg")
                }
            }
        }
    }

    @Test
    fun `adding twice or removing what is not there changes nothing`() {
        assertEquals(setOf("a"), edit(AppList.NIGHT_ALLOWED, ListOp.ADD, setOf("a"), "a", sundayWindow).list)
        assertEquals(setOf("a"), edit(AppList.NIGHT_ALLOWED, ListOp.REMOVE, setOf("a"), "zzz", mondayNoon).list)
    }
}
