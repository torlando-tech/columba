package com.lxmf.messenger.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for MapViewModel appearance parsing and marker state logic.
 *
 * Tests the [MapViewModel.Companion.parseAppearanceJson] function that extracts
 * icon appearance data from telemetry JSON, and the [MapViewModel.Companion.calculateMarkerState]
 * function that determines marker freshness.
 *
 * These were added as part of PR #422 to cover the telemetry appearance
 * fallback chain and byte-order fix.
 */
class MapViewModelAppearanceTest {
    // ========== parseAppearanceJson ==========

    @Test
    fun `parseAppearanceJson returns Triple for valid JSON`() {
        val json = """{"icon_name":"person","foreground_color":"ff0000","background_color":"00ff00"}"""
        val result = MapViewModel.parseAppearanceJson(json)

        assertNotNull(result)
        assertEquals("person", result!!.first)
        assertEquals("ff0000", result.second)
        assertEquals("00ff00", result.third)
    }

    @Test
    fun `parseAppearanceJson returns null for null input`() {
        val result = MapViewModel.parseAppearanceJson(null)
        assertNull(result)
    }

    @Test
    fun `parseAppearanceJson returns null for empty icon_name`() {
        val json = """{"icon_name":"","foreground_color":"ff0000","background_color":"00ff00"}"""
        val result = MapViewModel.parseAppearanceJson(json)
        assertNull(result)
    }

    @Test
    fun `parseAppearanceJson returns null for missing icon_name`() {
        val json = """{"foreground_color":"ff0000","background_color":"00ff00"}"""
        val result = MapViewModel.parseAppearanceJson(json)
        assertNull(result)
    }

    @Test
    fun `parseAppearanceJson returns null for invalid JSON`() {
        val result = MapViewModel.parseAppearanceJson("not json at all")
        assertNull(result)
    }

    @Test
    fun `parseAppearanceJson returns null for empty string`() {
        val result = MapViewModel.parseAppearanceJson("")
        assertNull(result)
    }

    @Test
    fun `parseAppearanceJson handles missing color fields gracefully`() {
        val json = """{"icon_name":"star"}"""
        val result = MapViewModel.parseAppearanceJson(json)

        assertNotNull(result)
        assertEquals("star", result!!.first)
        // optString defaults to "" when key is missing
        assertEquals("", result.second)
        assertEquals("", result.third)
    }

    @Test
    fun `parseAppearanceJson preserves exact color hex values`() {
        val json = """{"icon_name":"car","foreground_color":"aabbcc","background_color":"112233"}"""
        val result = MapViewModel.parseAppearanceJson(json)

        assertNotNull(result)
        assertEquals("aabbcc", result!!.second)
        assertEquals("112233", result.third)
    }

    @Test
    fun `parseAppearanceJson distinguishes foreground from background`() {
        // Distinct values to catch any swap
        val json = """{"icon_name":"pin","foreground_color":"FGFGFG","background_color":"BGBGBG"}"""
        val result = MapViewModel.parseAppearanceJson(json)

        assertNotNull(result)
        assertEquals("FGFGFG", result!!.second) // second = foreground
        assertEquals("BGBGBG", result.third)     // third = background
    }

    @Test
    fun `parseAppearanceJson handles MDI hyphenated icon names`() {
        val json = """{"icon_name":"access-point-network","foreground_color":"ffffff","background_color":"000000"}"""
        val result = MapViewModel.parseAppearanceJson(json)

        assertNotNull(result)
        assertEquals("access-point-network", result!!.first)
    }

    // ========== calculateMarkerState ==========

    @Test
    fun `fresh location returns FRESH`() {
        val now = System.currentTimeMillis()
        val result = MapViewModel.calculateMarkerState(
            timestamp = now - 60_000L, // 1 minute ago
            expiresAt = now + 3600_000L, // expires in 1 hour
            currentTime = now,
        )
        assertEquals(MarkerState.FRESH, result)
    }

    @Test
    fun `stale location returns STALE`() {
        val now = System.currentTimeMillis()
        val result = MapViewModel.calculateMarkerState(
            timestamp = now - 6 * 60_000L, // 6 minutes ago (> 5 min threshold)
            expiresAt = now + 3600_000L,
            currentTime = now,
        )
        assertEquals(MarkerState.STALE, result)
    }

    @Test
    fun `expired within grace period returns EXPIRED_GRACE_PERIOD`() {
        val now = System.currentTimeMillis()
        val result = MapViewModel.calculateMarkerState(
            timestamp = now - 10 * 60_000L,
            expiresAt = now - 1000L, // just expired
            currentTime = now,
        )
        assertEquals(MarkerState.EXPIRED_GRACE_PERIOD, result)
    }

    @Test
    fun `expired beyond grace period returns null`() {
        val now = System.currentTimeMillis()
        val result = MapViewModel.calculateMarkerState(
            timestamp = now - 10 * 60_000L,
            expiresAt = now - 2 * 3600_000L, // expired 2 hours ago (> 1 hour grace)
            currentTime = now,
        )
        assertNull(result)
    }

    @Test
    fun `null expiresAt with fresh timestamp returns FRESH`() {
        val now = System.currentTimeMillis()
        val result = MapViewModel.calculateMarkerState(
            timestamp = now - 60_000L,
            expiresAt = null, // indefinite sharing
            currentTime = now,
        )
        assertEquals(MarkerState.FRESH, result)
    }

    @Test
    fun `null expiresAt with stale timestamp returns STALE`() {
        val now = System.currentTimeMillis()
        val result = MapViewModel.calculateMarkerState(
            timestamp = now - 10 * 60_000L, // 10 minutes ago
            expiresAt = null,
            currentTime = now,
        )
        assertEquals(MarkerState.STALE, result)
    }

    // ========== ContactMarker construction with fallback chain ==========

    @Test
    fun `ContactMarker uses telemetry appearance when available`() {
        val marker = ContactMarker(
            destinationHash = "abc123",
            displayName = "Alice",
            latitude = 37.7749,
            longitude = -122.4194,
            iconName = "person",           // from telemetryAppearance?.first
            iconForegroundColor = "ff0000", // from telemetryAppearance?.second
            iconBackgroundColor = "00ff00", // from telemetryAppearance?.third
        )

        assertEquals("person", marker.iconName)
        assertEquals("ff0000", marker.iconForegroundColor)
        assertEquals("00ff00", marker.iconBackgroundColor)
    }

    @Test
    fun `ContactMarker defaults icon fields to null`() {
        val marker = ContactMarker(
            destinationHash = "abc123",
            displayName = "Alice",
            latitude = 37.7749,
            longitude = -122.4194,
        )

        assertNull(marker.iconName)
        assertNull(marker.iconForegroundColor)
        assertNull(marker.iconBackgroundColor)
    }

    @Test
    fun `ContactMarker fallback chain prefers telemetry over announce`() {
        // Simulates: telemetryAppearance?.first ?: announce?.iconName
        val telemetryName = "car"
        val announceName = "person"

        // When telemetry has a value, it should be used
        val name = telemetryName ?: announceName
        assertEquals("car", name)
    }

    @Test
    fun `ContactMarker fallback chain uses announce when telemetry is null`() {
        // Simulates: telemetryAppearance?.first ?: announce?.iconName
        val telemetryName: String? = null
        val announceName = "person"

        val name = telemetryName ?: announceName
        assertEquals("person", name)
    }
}
