package com.zhousl.aether.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.watchlistDataStore by preferencesDataStore(name = "aether_watchlist")
internal val WATCHLIST_ENTRIES_KEY = stringPreferencesKey("entries_v1")

class WatchlistRepository private constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.watchlistDataStore)

    internal constructor(dataStore: DataStore<Preferences>, @Suppress("UNUSED_PARAMETER") forTest: Unit) : this(dataStore)

    val entries: Flow<List<WatchlistEntry>> = dataStore.data.map { prefs ->
        parseEntries(prefs[WATCHLIST_ENTRIES_KEY].orEmpty())
    }

    suspend fun addEntry(symbol: String, name: String) {
        dataStore.edit { prefs ->
            val current = parseEntries(prefs[WATCHLIST_ENTRIES_KEY].orEmpty()).toMutableList()
            if (current.none { it.symbol.equals(symbol, ignoreCase = true) }) {
                current.add(WatchlistEntry(symbol = symbol.uppercase(), name = name))
                prefs[WATCHLIST_ENTRIES_KEY] = serializeEntries(current)
            }
        }
    }

    suspend fun removeEntry(symbol: String) {
        dataStore.edit { prefs ->
            val current = parseEntries(prefs[WATCHLIST_ENTRIES_KEY].orEmpty())
            prefs[WATCHLIST_ENTRIES_KEY] = serializeEntries(
                current.filter { !it.symbol.equals(symbol, ignoreCase = true) }
            )
        }
    }

    suspend fun addAlertRule(symbol: String, rule: AlertRule) {
        dataStore.edit { prefs ->
            val current = parseEntries(prefs[WATCHLIST_ENTRIES_KEY].orEmpty()).toMutableList()
            val idx = current.indexOfFirst { it.symbol.equals(symbol, ignoreCase = true) }
            if (idx >= 0) {
                current[idx] = current[idx].copy(alertRules = current[idx].alertRules + rule)
                prefs[WATCHLIST_ENTRIES_KEY] = serializeEntries(current)
            }
        }
    }

    suspend fun removeAlertRule(symbol: String, ruleId: String) {
        dataStore.edit { prefs ->
            val current = parseEntries(prefs[WATCHLIST_ENTRIES_KEY].orEmpty()).toMutableList()
            val idx = current.indexOfFirst { it.symbol.equals(symbol, ignoreCase = true) }
            if (idx >= 0) {
                current[idx] = current[idx].copy(
                    alertRules = current[idx].alertRules.filter { it.id != ruleId }
                )
                prefs[WATCHLIST_ENTRIES_KEY] = serializeEntries(current)
            }
        }
    }

    suspend fun updateAlertRuleLastTriggered(ruleId: String, triggeredAtMillis: Long) {
        dataStore.edit { prefs ->
            val current = parseEntries(prefs[WATCHLIST_ENTRIES_KEY].orEmpty()).toMutableList()
            for (i in current.indices) {
                val ruleIdx = current[i].alertRules.indexOfFirst { it.id == ruleId }
                if (ruleIdx >= 0) {
                    val updatedRules = current[i].alertRules.toMutableList()
                    updatedRules[ruleIdx] = updatedRules[ruleIdx].copy(lastTriggeredAtMillis = triggeredAtMillis)
                    current[i] = current[i].copy(alertRules = updatedRules)
                    break
                }
            }
            prefs[WATCHLIST_ENTRIES_KEY] = serializeEntries(current)
        }
    }

    // ── 序列化 / 反序列化 ────────────────────────────────────────────────────

    private fun parseEntries(json: String): List<WatchlistEntry> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { parseEntry(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseEntry(obj: JSONObject): WatchlistEntry {
        val rulesArr = obj.optJSONArray("alertRules") ?: JSONArray()
        return WatchlistEntry(
            symbol = obj.getString("symbol"),
            name = obj.optString("name"),
            addedAtMillis = obj.optLong("addedAtMillis", System.currentTimeMillis()),
            alertRules = (0 until rulesArr.length()).mapNotNull { j ->
                runCatching { parseRule(rulesArr.getJSONObject(j)) }.getOrNull()
            },
        )
    }

    private fun parseRule(r: JSONObject): AlertRule = AlertRule(
        id = r.getString("id"),
        symbol = r.getString("symbol"),
        name = r.optString("name"),
        type = AlertType.valueOf(r.getString("type")),
        threshold = r.getDouble("threshold"),
        enabled = r.optBoolean("enabled", true),
        cooldownMinutes = r.optInt("cooldownMinutes", 5),
        lastTriggeredAtMillis = r.optLong("lastTriggeredAtMillis", 0L),
    )

    private fun serializeEntries(entries: List<WatchlistEntry>): String =
        JSONArray().apply {
            entries.forEach { entry ->
                put(JSONObject().apply {
                    put("symbol", entry.symbol)
                    put("name", entry.name)
                    put("addedAtMillis", entry.addedAtMillis)
                    put("alertRules", JSONArray().apply {
                        entry.alertRules.forEach { rule ->
                            put(JSONObject().apply {
                                put("id", rule.id)
                                put("symbol", rule.symbol)
                                put("name", rule.name)
                                put("type", rule.type.name)
                                put("threshold", rule.threshold)
                                put("enabled", rule.enabled)
                                put("cooldownMinutes", rule.cooldownMinutes)
                                put("lastTriggeredAtMillis", rule.lastTriggeredAtMillis)
                            })
                        }
                    })
                })
            }
        }.toString()
}
