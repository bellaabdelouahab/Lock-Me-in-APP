package app.lockmein

import app.lockmein.core.EventKind
import app.lockmein.core.RequestKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The MCP can do what the app allows and nothing more: the phone's answers to the relay's calls, on the preview build's sample
 * data (protection on, nothing applied to the phone).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35])
class RemoteCommandsTest {
    private val context: android.content.Context = org.robolectric.RuntimeEnvironment.getApplication()
    private val remote = RemoteCommands(context)

    private fun call(tool: String, vararg args: Pair<String, String>) = remote.run(tool, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    @Before
    fun seed() {
        assumeTrue("runs on the preview build (-Ppreview=true), which applies nothing to the phone", BuildConfig.PREVIEW)
        PreviewDemo.seed(context)
    }

    @Test
    fun readsWork() {
        val status = call("status").getOrThrow().jsonObject
        assertEquals(JsonPrimitive(true), status["protection_on"])
        val use = call("phone_use").getOrThrow().jsonObject
        assertEquals(JsonPrimitive(3), use["tries_on_closed_apps"])
        assertTrue(call("requests").getOrThrow().jsonObject["waiting"]!!.jsonArray.isNotEmpty())
        assertTrue(call("weekly_report").isSuccess)
        assertTrue(call("settings").isSuccess)
        assertEquals(4, call("filter_blocks").getOrThrow().jsonArray.size)
    }

    @Test
    fun closingMoreIsInstantOpeningUpIsARequest() {
        val manager = ChangeManager(context)
        val config = manager.config()
        val watched = config.watchedApps.first()
        // Stricter filter for one app: applied at once.
        assertTrue(call("set_app_strictness", "package" to watched, "level" to "STRICTER").isSuccess)
        assertEquals(-0.1f, manager.config().filterAppOffsets[watched])
        // Looser: a request, nothing changes yet.
        val before = manager.config()
        assertTrue(call("change_setting", "field" to "filter_porn_hentai", "value" to "0.95").isSuccess)
        assertEquals(before, manager.config())
        assertTrue(manager.requests().any { it.kind == RequestKind.SETTINGS })
        // Taking an app off the always-blocked list: a request too.
        val blocked = config.alwaysBlocked.firstOrNull { pkg: String -> runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess }
        if (blocked != null) {
            assertTrue(call("edit_list", "list" to "ALWAYS_BLOCKED", "op" to "REMOVE", "package" to blocked).isSuccess)
            assertTrue(blocked in manager.config().alwaysBlocked)
        }
        assertTrue(EventLog(context).read().any { it.kind == EventKind.REMOTE_CHANGE })
    }

    @Test
    fun whatTheAppRefusesTheMcpCannotDo() {
        assertFalse(call("change_setting", "field" to "watchedApps", "value" to "").isSuccess)
        assertFalse(call("edit_list", "list" to "ALWAYS_BLOCKED", "op" to "ADD", "package" to "not.installed.app").isSuccess)
        assertFalse(call("friend_code", "code" to "123456").isSuccess)
        assertFalse(call("remove_protection").isSuccess)
        // Approving waits for the weekly window and a confirmed clock; outside it the app refuses, so the MCP does too.
        val id = ChangeManager(context).requests().first().id
        val approve = call("review_request", "id" to id, "decision" to "APPROVE")
        if (!app.lockmein.core.ReviewWindow.isOpen(java.time.Instant.now(), java.time.ZoneId.systemDefault())) {
            assertFalse(call("filter_screenshot", "block_id" to "demo-0").isSuccess)
            assertFalse(call("label_block", "block_id" to "demo-0", "label" to "WRONG").isSuccess)
        }
        assertTrue("approve outside the Sunday window must fail: ${approve.exceptionOrNull()?.message}", approve.isFailure || isSundayWindow())
    }

    private fun isSundayWindow() = Rules.schedule(ChangeManager(context).config()).inSettingsWindow(java.time.Instant.now())
}
