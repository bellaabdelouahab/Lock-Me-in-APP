package app.lockmein.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.lockmein.ChangeManager
import app.lockmein.ConfigStore
import app.lockmein.DeviceInventory
import app.lockmein.FeedbackStore
import app.lockmein.FilterModelClassifier
import app.lockmein.FilterService
import app.lockmein.Outcome
import app.lockmein.TrustedClock
import app.lockmein.core.Base32
import app.lockmein.core.BlockLabel
import app.lockmein.core.BlockRecord
import app.lockmein.core.EvalItem
import app.lockmein.core.EvalReport
import app.lockmein.core.Feedback
import app.lockmein.core.FilterDecisionMachine
import app.lockmein.core.FilterJudge
import app.lockmein.core.ReviewWindow
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The screen filter's feedback loop (spec v3.1 F2 to F5): the review window for blocks saved with "Wrong block?", per-app
 * strictness, eval mode on a labelled folder, the export for training and the personal judge.
 */
@Composable
fun FilterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()
    val store = remember { FeedbackStore(context) }
    val inventory = remember { DeviceInventory(context) }
    val manager = remember { ChangeManager(context) }
    var version by remember { mutableIntStateOf(0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val now = remember(version) { runCatching { TrustedClock(context).now() }.getOrDefault(Instant.now()) }
    // The preview build can show the window open (screenshots); the real app goes by the clock only.
    val previewOpen = remember { app.lockmein.BuildConfig.PREVIEW && (context as? android.app.Activity)?.intent?.getBooleanExtra("reviewOpen", false) == true }
    val open = ReviewWindow.isOpen(now, zone) || previewOpen
    val records = remember(version) { runCatching { store.records() }.getOrDefault(emptyList()) }
    val config = remember(version) { ConfigStore(context).load() }
    val dayHm = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(zone)
    var skipped by remember { mutableStateOf(setOf<String>()) }

    Page("Screen filter", onBack) {
        val waiting = records.filter { it.waiting }
        val weekAgo = now.minusSeconds(7 * 24 * 3600)
        // Training runs on its own (FEATURES 3.20): this card says where it stands and that nothing is asked of the user.
        val training = remember(version) { context.getSharedPreferences("lock_training", android.content.Context.MODE_PRIVATE) }
        val toSend = app.lockmein.core.Training.pending(records).size
        val falseAlarms = records.count { it.label == BlockLabel.WRONG || it.label == BlockLabel.CLAIMED_FALSE }
        val today = now.atZone(zone).toLocalDate()
        val sentToday = training.getString("done_on", null) == today.toString()
        val beforeNine = now.atZone(zone).toLocalTime().isBefore(app.lockmein.core.Training.SEND_FROM)
        Heading("Training")
        InfoCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Figure(toSend.toString(), "to send")
                Figure(falseAlarms.toString(), "false alarms")
                Figure(records.count { it.at.isAfter(weekAgo) }.toString(), "blocks this week")
            }
            Text(
                when {
                    training.getString("token", null) == null -> "Connecting to the training server. It happens on its own while protection is on."
                    toSend == 0 -> "Everything is sent. Nothing to do."
                    sentToday -> "Sent today. New blocks go tomorrow from 09:00 on Wi-Fi."
                    beforeNine -> "Goes today from 09:00 on Wi-Fi, on its own."
                    else -> "Goes on its own at the next Wi-Fi connection today."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Note("You do nothing here: tap \"Wrong?\" only on a block that should not have happened. Every block goes to training with its screen, encrypted. A better filter is offered once the server has 30 of your false alarms and it passes its check; installing it waits for the change gate.")
        }
        OutcomeText(outcome)

        if (config != null) {
            Heading("Strictness per app")
            InfoCard {
                Note("Stricter takes effect at once. Looser waits for the change gate (Sunday review or friend's code).")
                val apps = config.watchedApps.sortedBy { inventory.label(it).lowercase() }
                apps.forEachIndexed { i, pkg ->
                    val current = config.filterAppOffsets[pkg] ?: 0f
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(inventory.label(pkg), style = MaterialTheme.typography.titleSmall)
                        val steps = listOf(-STEP to "Stricter", 0f to "Default", STEP to "Looser")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            steps.forEachIndexed { j, (value, label) ->
                                SegmentedButton(
                                    selected = kotlin.math.abs(current - value) < 0.001f,
                                    onClick = {
                                        val offsets = (config.filterAppOffsets + (pkg to value)).filterValues { it != 0f }
                                        scope.background({ manager.saveSettings(config.copy(filterAppOffsets = offsets)) }) { outcome = it; version++ }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(j, steps.size),
                                ) { Text(label) }
                            }
                        }
                    }
                    if (i < apps.size - 1) RowDivider()
                }
            }

            Heading("Personal judge")
            val judge = config.filterJudge
            val judgePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.background({
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                        val j = Json { ignoreUnknownKeys = true }.decodeFromString(FilterJudge.serializer(), text)
                        j.problem()?.let { error(it) }
                        manager.saveSettings(config.copy(filterJudge = j))
                    }.getOrElse { Outcome.Refused("Not a judge file: ${it.message}") }
                }) { outcome = it; version++ }
            }
            InfoCard {
                Text(
                    if (judge == null) "None installed. The filter goes by the three models and the thresholds alone."
                    else "Installed: ${judge.version}. It drops hits it is sure are false alarms; never a very strong one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecondaryButton("Install a judge from a file", { judgePicker.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
                if (judge != null) SecondaryButton("Take the judge off", {
                    scope.background({ manager.saveSettings(config.copy(filterJudge = null)) }) { outcome = it; version++ }
                })
                Note("Trained on the server from your blocks and offered here on its own when it passes; a file from the laptop works too. Installing one waits for the change gate; taking it off is instant.")
            }
        }

        Heading("Look at saved screens (optional)")
        if (!open) {
            InfoCard {
                Note("Opens daily 19:30 to 20:00. Saved screens stay encrypted and out of sight until then. Not needed for training.")
            }
        } else {
            if (waiting.none { it.id !in skipped }) {
                InfoCard { Note(if (waiting.isEmpty()) "Nothing saved to look at." else "Everything left was skipped for now.") }
            } else {
                for (r in waiting.filter { it.id !in skipped }.take(20)) {
                    ReviewItem(r, store, inventory.label(r.pkg), dayHm) { label ->
                        if (label == null) skipped = skipped + r.id
                        else scope.background({ store.label(r.id, label, now) }) { version++ }
                    }
                }
            }
            InfoCard {
                var passphrase by remember { mutableStateOf<String?>(null) }
                SecondaryButton("Export to the laptop (encrypted)", {
                    val phrase = newPassphrase()
                    scope.background({
                        runCatching {
                            val (bytes, count) = store.exportBundle(phrase)
                            val name = "lockmein-review-${now.atZone(zone).toLocalDate()}.lmib"
                            saveToDownloads(context, name, "application/octet-stream", bytes)
                            "$count blocks saved to Downloads as $name. Copy it to the laptop by USB."
                        }
                    }) { r ->
                        r.onSuccess { passphrase = phrase }
                        outcome = r.fold({ Outcome.Done(it) }, { Outcome.Refused("Could not export: ${it.message}") })
                    }
                })
                passphrase?.let {
                    Text("Passphrase for this file (shown once):", style = MaterialTheme.typography.bodySmall)
                    Text(it, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Heading("Check the filter on a folder")
        var report by remember { mutableStateOf<EvalReport?>(null) }
        var progress by remember { mutableStateOf<String?>(null) }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree == null || config == null) return@rememberLauncherForActivityResult
            progress = "Reading the folder..."
            scope.background({ runCatching { evaluate(context, tree, config) { done, total -> progress = "Checked $done of $total" } } }) { r ->
                progress = null
                r.onSuccess { report = it }.onFailure { outcome = Outcome.Refused("Could not check the folder: ${it.message}") }
            }
        }
        InfoCard {
            Note("Pick a folder with two folders inside: \"normal\" (screens that should stay open) and \"risky\". Screenshots named by Android keep the app's name, so the report also goes per app. Nothing is changed or kept.")
            if (open) SecondaryButton(if (progress == null) "Choose the folder" else progress!!, { if (progress == null) folderPicker.launch(null) })
            else Note("Opens with the review window.")
            report?.let { EvalSummary(it, inventory) }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A saved block: a heavily blurred screenshot (hold to see it, release to blur again), the app, the time, the scores, three labels. */
@Composable
private fun ReviewItem(r: BlockRecord, store: FeedbackStore, appLabel: String, fmt: DateTimeFormatter, onLabel: (BlockLabel?) -> Unit) {
    val full = remember(r.id) { store.image(r)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    val blurred = remember(full) { full?.let { Bitmap.createScaledBitmap(it, 12, (12f * it.height / it.width).toInt().coerceAtLeast(1), true) } }
    var reveal by remember { mutableStateOf(false) }
    InfoCard {
        Box(
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pointerInput(r.id) { detectTapGestures(onPress = { reveal = true; tryAwaitRelease(); reveal = false }) },
            contentAlignment = Alignment.Center,
        ) {
            val shown = if (reveal) full else blurred
            if (shown != null) {
                Image(shown.asImageBitmap(), contentDescription = "Saved screen, hold to see it", contentScale = ContentScale.Crop, filterQuality = if (reveal) FilterQuality.Medium else FilterQuality.Low, modifier = Modifier.fillMaxWidth().height(220.dp))
            } else {
                Note("No screenshot (the frame was too old to keep).")
            }
            if (!reveal && shown != null) Text("Hold to see", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Text("$appLabel, ${fmt.format(r.at)}", style = MaterialTheme.typography.titleSmall)
        Text(r.scores.toScores().toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${r.agreed.count { it.hit }} of ${r.agreed.size} frames agreed" + (if (r.appOffset != 0f) ", app setting ${r.appOffset}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ButtonRow {
            SecondaryButtonSmall("Wrong block") { onLabel(BlockLabel.WRONG) }
            SecondaryButtonSmall("Correct block") { onLabel(BlockLabel.CORRECT) }
        }
        androidx.compose.material3.TextButton(onClick = { onLabel(null) }, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }
    }
}

@Composable
private fun EvalSummary(r: EvalReport, inventory: DeviceInventory) {
    val pct = { c: app.lockmein.core.EvalCount -> if (c.total == 0) "none" else "%d of %d (%.1f%%)".format(c.closed, c.total, c.share * 100) }
    Text("False closes on normal screens: ${pct(r.normal)}", style = MaterialTheme.typography.titleSmall)
    Text("Risky screens closed: ${pct(r.risky)}", style = MaterialTheme.typography.titleSmall)
    Text(if (r.meetsTarget) "Meets the target (at most 1 false close in 100)." else "Above the target of 1 false close in 100.", style = MaterialTheme.typography.bodySmall)
    for ((pkg, counts) in r.byApp().entries.sortedByDescending { it.value.first.total + it.value.second.total }) {
        Text("${if (pkg == "unknown app") pkg else inventory.label(pkg)}: normal ${pct(counts.first)}, risky ${pct(counts.second)}", style = MaterialTheme.typography.bodySmall)
    }
    for ((cls, counts) in r.byClass()) Text("Read as $cls: normal ${pct(counts.first)}, risky ${pct(counts.second)}", style = MaterialTheme.typography.bodySmall)
    if (r.falseCloses.isNotEmpty()) Text("Closed by mistake: " + r.falseCloses.take(10).joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
    if (r.misses.isNotEmpty()) Text("Missed: " + r.misses.take(10).joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
}

private const val STEP = 0.10f

private fun newPassphrase(): String =
    Base32.encode(ByteArray(10).also(SecureRandom()::nextBytes)).take(16).chunked(4).joinToString("-")

private fun saveToDownloads(context: Context, name: String, mime: String, bytes: ByteArray) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no Downloads")
    context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
}

/** Eval mode: every image under the folder's "normal" and "risky" folders, read by the models as a still screen. */
private fun evaluate(context: Context, tree: Uri, config: app.lockmein.core.LockConfig, onProgress: (Int, Int) -> Unit): EvalReport {
    val files = ArrayList<Pair<Pair<String, Uri>, Boolean>>()
    val resolver = context.contentResolver
    fun children(docId: String): List<Triple<String, String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2) ?: "")) }
        }.orEmpty()
    }
    fun collect(docId: String, risky: Boolean, depth: Int) {
        for ((id, name, mime) in children(docId)) {
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < 3) collect(id, risky, depth + 1) }
            else if (mime.startsWith("image/")) files += (name to DocumentsContract.buildDocumentUriUsingTree(tree, id)) to risky
        }
    }
    for ((id, name, mime) in children(DocumentsContract.getTreeDocumentId(tree))) {
        if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
        when {
            EvalReport.isRiskyFolder(name) -> collect(id, true, 0)
            EvalReport.isNormalFolder(name) -> collect(id, false, 0)
        }
    }
    require(files.isNotEmpty()) { "no images in folders named \"normal\" and \"risky\"" }
    val machine = FilterDecisionMachine(
        config.filterPornHentaiThreshold, config.filterSexyThreshold, config.filterAnimeThreshold,
        config.filterSeductiveThreshold.takeIf { config.filterSeductiveLive }, appOffsets = config.filterAppOffsets, judge = config.filterJudge,
    )
    val shared = FilterService.instance?.classifier?.takeIf { it.isReady }
    val cls = shared ?: FilterModelClassifier(context)
    try {
        val items = files.mapIndexed { i, (file, risky) ->
            onProgress(i, files.size)
            val bitmap = resolver.openInputStream(file.second)!!.use { BitmapFactory.decodeStream(it) } ?: return@mapIndexed null
            val scores = cls.predict(bitmap) { machine.rule.level(it) }
            bitmap.recycle()
            val pkg = EvalReport.packageOf(file.first)
            EvalItem(file.first, risky, scores, closed = machine.frameHit(pkg, scores))
        }.filterNotNull()
        return EvalReport(items)
    } finally {
        if (shared == null) cls.close()
    }
}

