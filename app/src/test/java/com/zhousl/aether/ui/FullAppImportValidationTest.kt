package com.zhousl.aether.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FullAppImportValidationTest {
    @Test
    fun acceptsSupportedAppExport() {
        validateFullAppImportEnvelope(validAppExport())
    }

    @Test
    fun rejectsSessionExportBeforeItCanReplaceAppData() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateFullAppImportEnvelope(
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("exportType", "session")
                    .put("session", JSONObject()),
            )
        }

        assertEquals("The selected file is not an Aether app data export.", error.message)
    }

    @Test
    fun rejectsIncompleteAppExportBeforeItCanClearSessions() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateFullAppImportEnvelope(
                JSONObject()
                    .put("schemaVersion", 2)
                    .put("exportType", "app")
                    .put("settings", JSONObject()),
            )
        }

        assertEquals("The app data export is missing sessions.", error.message)
    }

    @Test
    fun rejectsNewerSchemaBeforeItCanPartiallyImport() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateFullAppImportEnvelope(validAppExport().put("schemaVersion", 3))
        }

        assertEquals("This app data export was created by a newer version of Aether.", error.message)
    }

    private fun validAppExport(): JSONObject = JSONObject()
        .put("schemaVersion", 2)
        .put("exportType", "app")
        .put("settings", JSONObject())
        .put("sessions", JSONArray())
}
