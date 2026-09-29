package com.tradingplatform.app.data.repository

import com.tradingplatform.app.domain.model.NotifCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fonctions pures de lecture / réécriture de `ui.notifications` sur l'arbre JSON brut. */
class NotificationPreferencesJsonTest {

    @Test
    fun `normalizeNumbers turns whole doubles into longs and keeps fractions`() {
        val input = linkedMapOf<String, Any?>(
            "whole" to 25.0,
            "fraction" to 80.5,
            "negative" to -3.0,
            "nested" to listOf(1.0, 2.5, linkedMapOf<String, Any?>("z" to null, "flag" to true)),
            "text" to "12.0",
        )

        val output = NotificationPreferencesJson.normalizeNumbers(input) as Map<*, *>

        assertEquals(25L, output["whole"])
        assertEquals(80.5, output["fraction"])
        assertEquals(-3L, output["negative"])
        assertEquals(listOf(1L, 2.5, mapOf("z" to null, "flag" to true)), output["nested"])
        assertEquals("12.0", output["text"])
        // Ordre des clés conservé.
        assertEquals(listOf("whole", "fraction", "negative", "nested", "text"), output.keys.toList())
    }

    @Test
    fun `normalizeNumbers keeps a double too large to be an exact integer`() {
        assertEquals(1.0E20, NotificationPreferencesJson.normalizeNumbers(1.0E20))
    }

    @Test
    fun `parse falls back to defaults when values have the wrong type`() {
        val ui = mapOf<String, Any?>(
            "notifications" to mapOf<String, Any?>(
                "notifTypes" to listOf(
                    mapOf<String, Any?>("key" to "risk_alert", "push" to "no", "inApp" to false),
                    "garbage",
                    mapOf<String, Any?>("key" to "unknown_category", "push" to false),
                ),
                "quietHours" to "oops",
                "riskAlertThresholds" to mapOf<String, Any?>("varPctOfMax" to "high", "suppressedSignalsPerDay" to 12.0),
            ),
        )

        val prefs = NotificationPreferencesJson.parse(ui)

        val risk = prefs.channelsFor(NotifCategory.RISK_ALERT)
        assertFalse(risk.inApp)
        assertTrue(risk.push)
        assertTrue(risk.email)
        assertTrue(prefs.isPushEnabled(NotifCategory.STRATEGY_SIGNAL))
        assertFalse(prefs.quietHours.enabled)
        assertEquals("22:00", prefs.quietHours.start)
        assertEquals("08:00", prefs.quietHours.end)
        assertNull(prefs.riskAlertThresholds.varPctOfMax)
        assertEquals(12, prefs.riskAlertThresholds.suppressedSignalsPerDay)
    }

    @Test
    fun `withPushEnabled leaves other top-level ui keys out and omits a missing appearance`() {
        val ui = mapOf<String, Any?>(
            "theme" to "dark",
            "notifications" to mapOf<String, Any?>(
                "notifTypes" to listOf(mapOf<String, Any?>("key" to "system", "push" to true)),
            ),
        )

        val body = NotificationPreferencesJson.withPushEnabled(ui, NotifCategory.SYSTEM, false)

        assertEquals(listOf("notifications"), body.keys.toList())
        val types = (body["notifications"] as Map<*, *>)["notifTypes"] as List<*>
        assertEquals(listOf(mapOf("key" to "system", "push" to false)), types)
    }

    @Test
    fun `withPushEnabled updates every duplicated entry of the category`() {
        val ui = mapOf<String, Any?>(
            "notifications" to mapOf<String, Any?>(
                "notifTypes" to listOf(
                    mapOf<String, Any?>("key" to "system", "push" to true),
                    mapOf<String, Any?>("key" to "system", "push" to true),
                ),
            ),
        )

        val body = NotificationPreferencesJson.withPushEnabled(ui, NotifCategory.SYSTEM, false)

        val types = (body["notifications"] as Map<*, *>)["notifTypes"] as List<*>
        assertEquals(2, types.size)
        assertTrue(types.all { (it as Map<*, *>)["push"] == false })
    }

    @Test
    fun `withPushEnabled does not mutate the tree it was given`() {
        val entry = linkedMapOf<String, Any?>("key" to "system", "push" to true)
        val ui = linkedMapOf<String, Any?>(
            "notifications" to linkedMapOf<String, Any?>("notifTypes" to arrayListOf<Any?>(entry)),
        )

        NotificationPreferencesJson.withPushEnabled(ui, NotifCategory.SYSTEM, false)

        assertEquals(true, entry["push"])
    }
}
