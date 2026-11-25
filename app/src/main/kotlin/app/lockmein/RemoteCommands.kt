package app.lockmein

import android.content.Context
import android.util.Base64
import app.lockmein.core.AppList
import app.lockmein.core.BlockLabel
import app.lockmein.core.EventKind
import app.lockmein.core.FilterJudge
import app.lockmein.core.ListOp
import app.lockmein.core.PhoneUse
import app.lockmein.core.RemoteSettings
import app.lockmein.core.Reports
import app.lockmein.core.ReviewWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * The MCP's calls, run on the phone through the same code the app's screens call (ChangeManager, FeedbackStore), so the MCP can
 * do what the app allows and nothing more: tightening applies at once, opening anything up becomes a request for the Sunday
 * review, approving works only in the weekly window, review labels and screenshots only in the daily review window. No friend's
 * code or holder code, no removal, no setup reset. Every change is logged as made through the MCP.
 */
class RemoteCommands(private val context: Context) {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val hms = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    private val manager by lazy { ChangeManager(context) }
    private val inventory by lazy { DeviceInventory(context) }
    private val events by lazy { EventLog(context) }

    /** Answers (ok, data or error). Never throws. */
    fun run(tool: String, args: JsonObject): Result<JsonElement> = runCatching {
        fun s(name: String) = args[name]?.jsonPrimitive?.contentOrNull
        fun need(name: String) = requireNotNull(s(name)?.takeIf { it.isNotBlank() }) { "$name is missing" }
        val now = TrustedClock(context).now()
        when (tool) {
            "status" -> status(now)
            "phone_use" -> phoneUse(s("day")?.let(LocalDate::parse) ?: LocalDate.now(zone))
            "day_scores" -> dayScores(s("day")?.let(LocalDate::parse) ?: LocalDate.now(zone).minusDays(1), now)
            "mood" -> {
                val end = s("end")?.let(LocalDate::parse) ?: LocalDate.now(zone)
                val start = s("start")?.let(LocalDate::parse) ?: end.minusDays(6)
                buildJsonArray {
                    MoodLog(context).read().filter { val d = it.at.atZone(zone).toLocalDate(); !d.isBefore(start) && !d.isAfter(end) }.forEach {
                        add(buildJsonObject { put("at", it.at.atZone(zone).toLocalDateTime().toString()); put("score", it.score); put("asked", it.asked) })
                    }
                }
            }
            "phone_use_events" -> phoneUseEvents(LocalDate.parse(need("start")), s("end")?.let(LocalDate::parse) ?: LocalDate.now(zone))
            "weekly_report" -> {
                val days = (s("days")?.toLongOrNull() ?: 7L).coerceIn(1, 90)
                val starts = PhoneUseLog(context).firstDay()?.let { "\nPhone-use log kept from $it (nothing was recorded before it)." }.orEmpty()
                JsonPrimitive(Reports.week(events.read(), now, Duration.ofDays(days)).toText(zone) + starts)
            }
            "events" -> {
                val kind = s("kind")?.let { EventKind.valueOf(it.uppercase()) }
                val limit = (s("limit")?.toIntOrNull() ?: 100).coerceIn(1, 1000)
                buildJsonArray {
                    events.read().asReversed().filter { kind == null || it.kind == kind }.take(limit).forEach {
                        add(buildJsonObject { put("at", it.at.atZone(zone).toString()); put("kind", it.kind.name); put("detail", it.detail) })
                    }
                }
            }
            "apps" -> apps()
            "requests" -> requests()
            "settings" -> Json.parseToJsonElement(app.lockmein.core.ConfigCodec.encode(manager.config()))
            "filter_blocks" -> {
                val limit = (s("limit")?.toIntOrNull() ?: 100).coerceIn(1, 500)
                Json.parseToJsonElement(app.lockmein.core.Feedback.encode(FeedbackStore(context).records().sortedByDescending { it.atMillis }.take(limit)))
            }
            "filter_screenshot" -> {
                require(ReviewWindow.isOpen(now, zone)) { "Screenshots open only in the review window, 19:30 to 20:00." }
                val store = FeedbackStore(context)
                val r = requireNotNull(store.records().firstOrNull { it.id == need("block_id") }) { "No such block." }
                val img = requireNotNull(store.image(r)) { "This block has no screenshot." }
                buildJsonObject { put("block_id", r.id); put("jpeg_base64", Base64.encodeToString(img, Base64.NO_WRAP)) }
            }

            "edit_list" -> {
                val list = AppList.valueOf(need("list").uppercase())
                val op = ListOp.valueOf(need("op").uppercase())
                outcome(manager.editList(list, op, installed(need("package")), now), "edit_list $op ${s("package")} on $list")
            }
            "release_app" -> outcome(manager.releaseApp(installed(need("package")), now), "release_app ${s("package")}")
            "request_browser" -> outcome(manager.approveBrowser(installed(need("package")), now), "request_browser ${s("package")}")
            "change_setting" -> {
                val updated = RemoteSettings.apply(manager.config(), need("field"), need("value"))
                outcome(manager.saveSettings(updated, now), "change_setting ${s("field")}=${s("value")}")
            }
            "set_app_strictness" -> {
                val config = manager.config()
                val pkg = need("package")
                require(pkg in config.watchedApps) { "$pkg is not on the watched list." }
                val offsets = (config.filterAppOffsets + (pkg to RemoteSettings.strictness(need("level")))).filterValues { it != 0f }
                outcome(manager.saveSettings(config.copy(filterAppOffsets = offsets), now), "set_app_strictness $pkg ${s("level")}")
            }
            "cancel_request" -> outcome(manager.cancelRequest(need("id"), now), "cancel_request ${s("id")}")
            "review_request" -> when (need("decision").uppercase()) {
                "APPROVE" -> outcome(manager.approveSundayRequest(need("id"), now, NetworkClock.now()), "approve ${s("id")}")
                "DISMISS" -> outcome(manager.dismissSundayRequest(need("id"), now), "dismiss ${s("id")}")
                else -> throw IllegalArgumentException("decision must be APPROVE or DISMISS")
            }
            "label_block" -> {
                require(ReviewWindow.isOpen(now, zone)) { "Labels open only in the review window, 19:30 to 20:00." }
                val label = when (need("label").uppercase()) { "WRONG" -> BlockLabel.WRONG; "CORRECT" -> BlockLabel.CORRECT; else -> throw IllegalArgumentException("label must be WRONG or CORRECT") }
                val store = FeedbackStore(context)
                require(store.records().any { it.id == need("block_id") && it.waiting }) { "No saved block with that id is waiting." }
                store.label(need("block_id"), label, now)
                logged("label_block ${s("block_id")} $label")
                JsonPrimitive("Labelled ${label.name.lowercase()}.")
            }
            "install_judge" -> {
                val judge = Json { ignoreUnknownKeys = true }.decodeFromString(FilterJudge.serializer(), need("judge_json"))
                judge.problem()?.let { throw IllegalArgumentException(it) }
                outcome(manager.saveSettings(manager.config().copy(filterJudge = judge), now), "install_judge ${judge.version}")
            }
            "remove_judge" -> outcome(manager.saveSettings(manager.config().copy(filterJudge = null), now), "remove_judge")
            else -> throw IllegalArgumentException("Unknown tool $tool.")
        }
    }

    private fun installed(pkg: String): String {
        require(runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess) { "$pkg is not installed on the phone." }
        return pkg
    }

    private fun logged(what: String) = events.add(EventKind.REMOTE_CHANGE, what)

    /** The app's own answer, word for word; a refusal is an error for the MCP. */
    private fun outcome(o: Outcome, what: String): JsonElement {
        logged("$what: ${if (o is Outcome.Done) o.message else (o as Outcome.Refused).reason}")
        return when (o) {
            is Outcome.Done -> JsonPrimitive(o.message)
            is Outcome.Refused -> throw IllegalStateException(o.reason)
        }
    }

    private fun status(now: java.time.Instant): JsonElement {
        val store = ConfigStore(context)
        val config = store.load()
        return buildJsonObject {
            put("protection_on", store.protectionActive)
            put("app_version", BuildConfig.VERSION_NAME)
            if (config != null) {
                put("phase", Rules.schedule(config, zone).phaseAt(now).toString())
                put("closed_now", buildJsonArray {
                    ClosedReasons.all(context, config, now).filter { it.closedNow }.forEach {
                        add(buildJsonObject { put("package", it.packageName); put("app", inventory.label(it.packageName)); put("why", it.why) })
                    }
                })
            }
            put("screen_filter_working", FilterService.isWorking(context))
            put("training", buildJsonObject { TrainingUpload.state(context).forEach { (k, v) -> put(k, v) } })
            put("checks", buildJsonArray {
                runCatching { Health(context).check(now) }.getOrDefault(emptyList()).forEach {
                    add(buildJsonObject { put("check", it.title); put("ok", it.ok); put("detail", it.detail) })
                }
            })
        }
    }

    private fun ignored(): Set<String> {
        val home = context.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        return setOfNotNull(home, "com.android.systemui")
    }

    private fun phoneUse(day: LocalDate): JsonElement {
        val u = PhoneUse.summarize(PhoneUseLog(context).read(day), day, zone, java.time.Instant.now(), ignored())
        return buildJsonObject {
            put("day", day.toString())
            put("screen_minutes", u.screenMillis / 60_000)
            put("unlocks", u.unlocks)
            put("notifications", u.notifications)
            put("first_unlock", u.firstUnlock?.atZone(zone)?.toLocalTime()?.toString())
            put("last_screen_off", u.lastScreenOff?.atZone(zone)?.toLocalTime()?.toString())
            put("tries_on_closed_apps", u.blockedTries)
            put("apps", buildJsonArray {
                u.apps.forEach {
                    add(buildJsonObject {
                        put("package", it.pkg); put("app", inventory.label(it.pkg)); put("minutes", it.millis / 60_000.0)
                        put("opens", it.opens); put("notifications", it.notifications); put("tries_while_closed", it.tries)
                    })
                }
            })
        }
    }

    private fun dayScores(day: LocalDate, now: java.time.Instant): JsonElement {
        val config = manager.config()
        val log = PhoneUseLog(context)
        val uses = log.read(day) + log.read(day.plusDays(1)).filter { it.at.atZone(zone).toLocalDate() == day.plusDays(1) }
        val blocks = FeedbackStore(context).records().map { java.time.Instant.ofEpochMilli(it.atMillis) to it.pkg }
        val fajr = app.lockmein.core.AstronomicalFajr(config.fajrConfig(), zone)
        val d = app.lockmein.core.Slides.day(day, zone, now, uses, events.read(), blocks, MoodLog(context).read(), fajr::fajr, ignored())
        fun name(pkg: String?) = pkg?.takeIf { it.isNotEmpty() }?.let { inventory.label(it) }
        fun leads(list: List<app.lockmein.core.Lead>) = buildJsonArray {
            list.forEach { l ->
                add(buildJsonObject {
                    put("at", hms.format(l.at.atZone(zone))); put("app", name(l.app) ?: "not named")
                    put("previous_app", name(l.previousApp)); put("seconds_since_unlock", l.sinceUnlockSeconds); put("started_by_notification_from", name(l.startedBy))
                })
            }
        }
        return buildJsonObject {
            put("day", day.toString())
            put("in_bed_by_22", d.inBedBy22)
            put("screen_minutes_after_22", d.screenMinutesAfter22)
            put("screen_minutes_2130_to_fajr", d.screenMinutesNight)
            put("screen_minutes_midnight_to_fajr", d.screenMinutesBeforeFajr)
            put("screen_minutes", d.screenMinutes)
            put("unlocks", d.unlocks)
            put("tries", leads(d.tries))
            put("filter_blocks", leads(d.blocks))
            put("requests_to_open_up", d.loosenAsked)
            put("tightenings", d.tightened)
            put("top_apps", buildJsonArray { d.topApps.forEach { a -> add(buildJsonObject { put("app", name(a.pkg)); put("minutes", a.millis / 60_000) }) } })
            put("moods", buildJsonArray { d.moods.forEach { m -> add(buildJsonObject { put("at", hms.format(m.at.atZone(zone))); put("score", m.score); put("asked", m.asked) }) } })
            put("usage_log_starts", log.firstDay()?.toString())
        }
    }

    private fun phoneUseEvents(start: LocalDate, end: LocalDate): JsonElement {
        require(!end.isBefore(start) && Duration.between(start.atStartOfDay(), end.atStartOfDay()).toDays() <= 31) { "At most 31 days at a time." }
        val from = start.atStartOfDay(zone).toInstant()
        val to = end.plusDays(1).atStartOfDay(zone).toInstant()
        return buildJsonArray {
            PhoneUseLog(context).readAll().filter { it.at >= from && it.at < to }.forEach {
                add(buildJsonObject { put("at", it.at.atZone(zone).toLocalDateTime().toString()); put("event", it.kind.name.lowercase()); put("package", it.pkg) })
            }
        }
    }

    private fun apps(): JsonElement {
        val c = manager.config()
        val state = LockState(context)
        return buildJsonObject {
            put("approved_browser", c.allowedBrowser)
            put("apps", buildJsonArray {
                inventory.launchableApps().sortedBy { it.label.lowercase() }.forEach { a ->
                    add(buildJsonObject {
                        put("package", a.packageName); put("app", a.label)
                        put("lists", buildJsonArray {
                            if (a.packageName in c.nightClosed) add(JsonPrimitive("NIGHT_CLOSED"))
                            if (a.packageName in c.alwaysBlocked) add(JsonPrimitive("ALWAYS_BLOCKED"))
                            if (a.packageName in c.windowApps) add(JsonPrimitive("WINDOW_APPS"))
                            if (a.packageName in c.watchedApps) add(JsonPrimitive("WATCHED"))
                        })
                        put("held_back_new_app", a.packageName in state.quarantined)
                    })
                }
            })
        }
    }

    private fun requests(): JsonElement = buildJsonObject {
        put("waiting", buildJsonArray {
            manager.requests().forEach { r ->
                add(buildJsonObject {
                    put("id", r.id); put("kind", r.kind.name); put("target", r.target); put("app", r.target.takeIf { it.isNotEmpty() }?.let { inventory.label(it) })
                    put("asked_times", r.count); put("first_asked", r.requestedAt.atZone(zone).toString())
                    r.settingsApplied(manager.config())?.let { asked ->
                        put("changes", buildJsonArray {
                            app.lockmein.core.SettingsDiff.changes(manager.config(), asked).forEach { c ->
                                add(buildJsonObject { put("field", c.field); put("now", c.before); put("asked", c.after) })
                            }
                        })
                    }
                })
            }
        })
        put("history", buildJsonArray {
            LockState(context).requestHistory.take(30).forEach { h ->
                add(buildJsonObject { put("kind", h.kind.name); put("target", h.target); put("resolution", h.resolution.name); put("asked_times", h.count) })
            }
        })
    }
}
