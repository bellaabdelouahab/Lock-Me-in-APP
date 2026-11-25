package app.lockmein.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** One setting a request changes: its name, the value now and the value asked for. */
data class SettingChange(val field: String, val before: String, val after: String) {
    override fun toString() = "$field: $before to $after"
}

/**
 * What a settings request would change, so it can be judged: every field whose value differs, by name. A settings request holds the
 * whole new configuration and approving it puts all of it in place, so this is worked out against the settings in force now (a
 * tightening made since the request shows up here as something approving would undo).
 */
object SettingsDiff {
    private val json = Json { encodeDefaults = true }
    private const val MAX_VALUE = 80
    private const val MAX_NESTED = 6

    fun changes(before: LockConfig, after: LockConfig): List<SettingChange> {
        val a = json.encodeToJsonElement(LockConfig.serializer(), before).jsonObject
        val b = json.encodeToJsonElement(LockConfig.serializer(), after).jsonObject
        return diff("", a, b)
    }

    /** One line for the event log and the MCP: "field: old to new; ..." (or "nothing" when the request changes nothing now). */
    fun summary(changes: List<SettingChange>): String = if (changes.isEmpty()) "nothing (it matches the settings in force)" else changes.joinToString("; ")

    private fun diff(prefix: String, a: JsonObject, b: JsonObject): List<SettingChange> = (a.keys + b.keys).flatMap { key ->
        val name = if (prefix.isEmpty()) key else "$prefix.$key"
        val x = a[key] ?: JsonNull
        val y = b[key] ?: JsonNull
        when {
            x == y -> emptyList()
            x is JsonObject && y is JsonObject -> diff(name, x, y).let { inner ->
                if (inner.size > MAX_NESTED) listOf(SettingChange(name, "${inner.size} values", "changed")) else inner
            }
            x is JsonArray && y is JsonArray && (x + y).all { it is JsonPrimitive } -> {
                val xs = x.map(::show)
                val ys = y.map(::show)
                val added = ys - xs.toSet()
                val removed = xs - ys.toSet()
                if (added.isEmpty() && removed.isEmpty()) listOf(SettingChange(name, "same items", "new order"))
                else listOf(SettingChange(name, if (removed.isEmpty()) "-" else "drops ${removed.joinToString(", ")}", if (added.isEmpty()) "-" else "adds ${added.joinToString(", ")}"))
            }
            else -> listOf(SettingChange(name, show(x), show(y)))
        }
    }

    private fun show(e: JsonElement): String {
        val text = when (e) {
            is JsonNull -> "none"
            is JsonPrimitive -> e.content
            else -> e.toString()
        }
        return if (text.length > MAX_VALUE) text.take(MAX_VALUE - 3) + "..." else text
    }
}
