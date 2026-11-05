package app.lockmein.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.lockmein.BuildConfig
import app.lockmein.EventLog
import app.lockmein.Updater
import app.lockmein.core.Reports
import java.time.Instant
import java.time.ZoneId

/** The last seven days as numbers. Nothing about what was on the screen is ever recorded. */
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember { Reports.week(EventLog(context).read(), Instant.now()).toText(ZoneId.systemDefault()) }
    Page("Weekly report", onBack) {
        AppCard {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        SecondaryButton("Share this report", {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "Share the report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        Note("The report stays on this phone unless you share it. It holds events (changes, refusals, new apps), never browsing or screen content.")
    }
}

/** About destination in the navigation drawer: app name, version, device owner status, and weekly report link. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val navigate = LocalNavigate.current
    val isOwner = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)
    val text = remember { Reports.week(EventLog(context).read(), Instant.now()).toText(ZoneId.systemDefault()) }

    Page("About", onBack) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Lock Me In", style = MaterialTheme.typography.titleLarge)
            }
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
            Text("Build kind: ${Updater.kind}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (isOwner) "Device owner: active on this phone." else "Device owner: inactive.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Keeps the phone calm at night through Android's device management layer. Rules are decided while calm and cannot be loosened on the spot.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Heading("Weekly report")
        AppCard {
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        SecondaryButton("Share weekly report", {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "Share the report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }
}
