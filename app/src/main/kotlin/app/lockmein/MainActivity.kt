package app.lockmein

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.lockmein.core.AstronomicalFajr
import app.lockmein.core.BrowserApproval
import app.lockmein.core.FajrConfig
import app.lockmein.core.Schedule
import app.lockmein.core.ScheduleConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** Status and the browser requests. The window screens (review new apps, change settings) come with the later features. */
class MainActivity : Activity() {
    private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val owner = getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)
        column.addView(label(if (owner) "Lock Me In is the device owner." else "Lock Me In is installed but is not the device owner yet.", 18f))
        if (owner) {
            column.addView(
                Button(this).apply {
                    text = "Night and blocked apps"
                    setOnClickListener { startActivity(Intent(this@MainActivity, AppListsActivity::class.java)) }
                },
            )
            addBrowserRequests(column)
        }
        setContentView(column)
    }

    private fun addBrowserRequests(column: LinearLayout) {
        val zone = ZoneId.systemDefault()
        val approval = BrowserApproval(Schedule(ScheduleConfig(), AstronomicalFajr(FajrConfig(), zone), zone))
        val state = LockState(this)
        val snapshot = DeviceInventory(this).read()
        val closed = (snapshot.browsers - OwnerRules.BRAVE - state.approvedBrowsers).filter { it in snapshot.installed }.sorted()

        column.addView(label("Other browsers", 16f).apply { setPadding(0, 48, 0, 8) })
        if (closed.isEmpty()) column.addView(label("No other browser is closed.", 14f))
        for (browser in closed) {
            val requestedAt = state.browserRequests[browser]
            column.addView(label(browser, 14f))
            if (requestedAt == null) {
                column.addView(
                    Button(this).apply {
                        text = "Request (opens after 30 minutes, by day)"
                        setOnClickListener { request(browser, state, approval) }
                    },
                )
            } else {
                val opens = approval.approvalTime(requestedAt).atZone(zone).format(timeFormat)
                column.addView(label("Requested. Opens $opens.", 14f))
            }
        }
    }

    private fun request(browser: String, state: LockState, approval: BrowserApproval) {
        state.browserRequests = approval.requested(state.browserRequests, browser, Instant.now())
        // The alarm for the moment it opens is set by a pass; run one now off the main thread.
        thread(name = "lock-me-in-request") { PolicyRunner(applicationContext).run() }
        render()
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
    }
}
