package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AppListEditorTest {
    private val editor = AppListEditor()

    @Test
    fun `which edits open the phone up and which close it more`() {
        assertEquals(ChangeKind.TIGHTEN, editor.kindOf(AppList.NIGHT_CLOSED, ListOp.ADD))
        assertEquals(ChangeKind.LOOSEN, editor.kindOf(AppList.NIGHT_CLOSED, ListOp.REMOVE))
        assertEquals(ChangeKind.TIGHTEN, editor.kindOf(AppList.ALWAYS_BLOCKED, ListOp.ADD))
        assertEquals(ChangeKind.LOOSEN, editor.kindOf(AppList.ALWAYS_BLOCKED, ListOp.REMOVE))
    }

    @Test
    fun `an opening edit becomes the matching request`() {
        assertEquals(RequestKind.NIGHT_REMOVE, editor.requestKind(AppList.NIGHT_CLOSED))
        assertEquals(RequestKind.BLOCKED_REMOVE, editor.requestKind(AppList.ALWAYS_BLOCKED))
    }
}
