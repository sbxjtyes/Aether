package com.zhousl.aether.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.core.DataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistRepositoryTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scope.cancel()
        files.forEach { it.delete() }
    }

    @Test
    fun addAndRemoveEntryPersistsAndDeduplicatesSymbols() = runBlocking {
        val repository = newFixture().repository

        repository.addEntry("600519.sh", "Kweichow Moutai")
        repository.addEntry("600519.SH", "Duplicate")
        repository.addEntry("000001.SZ", "Ping An Bank")

        val added = repository.entries.first()
        assertEquals(listOf("600519.SH", "000001.SZ"), added.map { it.symbol })
        assertEquals("Kweichow Moutai", added.first().name)

        repository.removeEntry("600519.sh")

        val remaining = repository.entries.first()
        assertEquals(listOf("000001.SZ"), remaining.map { it.symbol })
    }

    @Test
    fun alertRulesSerializeDeserializeAndUpdateLastTriggered() = runBlocking {
        val repository = newFixture().repository
        val rule = AlertRule(
            id = "rule-1",
            symbol = "600519.SH",
            name = "Price alert",
            type = AlertType.PriceAbove,
            threshold = 1700.0,
            cooldownMinutes = 5,
        )

        repository.addEntry("600519.SH", "Kweichow Moutai")
        repository.addAlertRule("600519.SH", rule)
        repository.updateAlertRuleLastTriggered("rule-1", 123_456L)

        val entry = repository.entries.first().single()
        assertEquals(1, entry.alertRules.size)
        assertEquals(rule.copy(lastTriggeredAtMillis = 123_456L), entry.alertRules.single())

        repository.removeAlertRule("600519.SH", "rule-1")

        assertTrue(repository.entries.first().single().alertRules.isEmpty())
    }

    @Test
    fun damagedJsonFallsBackToEmptyList() = runBlocking {
        val fixture = newFixture()
        val repository = fixture.repository

        fixture.dataStore.edit { prefs ->
            prefs[WATCHLIST_ENTRIES_KEY] = "{not-valid-json"
        }

        assertTrue(repository.entries.first().isEmpty())
    }

    private fun newFixture(): Fixture {
        val file = kotlin.io.path.createTempFile(prefix = "watchlist-", suffix = ".preferences_pb").toFile()
        files += file
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        return Fixture(
            repository = WatchlistRepository(dataStore, Unit),
            dataStore = dataStore,
        )
    }

    private data class Fixture(
        val repository: WatchlistRepository,
        val dataStore: DataStore<Preferences>,
    )
}
