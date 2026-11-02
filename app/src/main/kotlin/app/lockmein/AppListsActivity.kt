package app.lockmein

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.lockmein.core.AppList
import app.lockmein.core.Decision
import app.lockmein.core.ListOp
import kotlin.concurrent.thread

/** Add and remove apps on the night list and the always-blocked list. Opening the phone up is refused outside the window. */
class AppListsActivity : Activity() {
    private lateinit var manager: AppListManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = AppListManager(this)
        render()
    }

    private fun render() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        section(column, "Apps allowed at night", "Adding one opens the phone up, so it works only in the Sunday window. Removing one works any time.", AppList.NIGHT_ALLOWED)
        section(column, "Apps blocked all the time", "Blocking works any time. Unblocking works only in the Sunday window.", AppList.ALWAYS_BLOCKED)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun section(column: LinearLayout, title: String, note: String, list: AppList) {
        column.addView(text(title, 18f).apply { setPadding(0, 32, 0, 4) })
        column.addView(text(note, 13f))
        val entries = manager.current(list).sorted()
        if (entries.isEmpty()) column.addView(text("None.", 14f))
        for (pkg in entries) {
            column.addView(text(labelOf(pkg), 15f).apply { setPadding(0, 16, 0, 0) })
            column.addView(Button(this).apply {
                text = "Remove"
                setOnClickListener { change(list, ListOp.REMOVE, pkg) }
            })
        }
        column.addView(Button(this).apply {
            text = "Add an app"
            setOnClickListener { pickApp(list) }
        })
    }

    private fun pickApp(list: AppList) {
        val taken = manager.current(list)
        val protectedPackages = DeviceInventory(this).read().protectedPackages
        val choices = launchableApps().filter { it.first !in taken && it.first !in protectedPackages && it.first != packageName }
        AlertDialog.Builder(this)
            .setTitle("Choose an app")
            .setItems(choices.map { it.second }.toTypedArray()) { _, which -> change(list, ListOp.ADD, choices[which].first) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun change(list: AppList, op: ListOp, pkg: String) {
        // Asks the network for the time and re-applies the policy, so it runs off the main thread.
        thread(name = "lock-me-in-edit") {
            val decision = manager.edit(list, op, pkg)
            runOnUiThread {
                when (decision) {
                    is Decision.Allowed -> Toast.makeText(this, "Done.", Toast.LENGTH_SHORT).show()
                    is Decision.Denied -> Toast.makeText(this, "Not now: ${decision.why}", Toast.LENGTH_LONG).show()
                }
                render()
            }
        }
    }

    private fun launchableApps(): List<Pair<String, String>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
    }
}
