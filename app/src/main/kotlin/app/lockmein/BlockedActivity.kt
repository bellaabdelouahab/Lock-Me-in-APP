package app.lockmein

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.lockmein.ui.AppIcon
import app.lockmein.ui.LockTheme
import app.lockmein.ui.MenuButton
import app.lockmein.ui.Note
import app.lockmein.ui.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opened by FilterService in place of Android's generic "blocked by your admin" dialog: which app, why it is closed and
 * when it opens, in the app's own words.
 */
class BlockedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = DeviceInventory(this).label(pkg)
        setContent {
            LockTheme {
                val why by produceState<String?>(null) { value = withContext(Dispatchers.IO) { ClosedReasons.of(this@BlockedActivity, pkg) } }
                Page("$label is closed", bottomBar = { MenuButton("OK") { goHome() } }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(pkg, 48.dp)
                        Text(label, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(why ?: "Checking why...", style = MaterialTheme.typography.bodyLarge)
                    Note("Lock Me In closed this app. Your friend's code can open it for an hour; Apps, Now lists everything that is closed.")
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"

        fun open(context: Context, packageName: String) = context.startActivity(
            Intent(context, BlockedActivity::class.java).putExtra(EXTRA_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
        )
    }
}
