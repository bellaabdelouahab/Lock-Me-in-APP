package app.lockmein.ui

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.lockmein.EventLog
import app.lockmein.core.Reports
import java.time.Instant
import java.time.ZoneId

/** The last seven days as numbers, for the Sunday session. Nothing about what was on the screen is ever recorded. */
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember { Reports.week(EventLog(context).read(), Instant.now()).toText(ZoneId.systemDefault()) }
    Page("This week", onBack) {
        InfoCard { Text(text) }
        SecondaryButton("Share this report", {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "Share the report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        Note("The report stays on this phone unless you share it. It holds events (changes, refusals, new apps), never your browsing or screen content.")
    }
}
