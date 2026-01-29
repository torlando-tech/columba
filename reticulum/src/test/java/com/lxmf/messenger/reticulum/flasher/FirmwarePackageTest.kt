package com.lxmf.messenger.reticulum.flasher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for firmware package handling.
 */
class FrequencyBandTest {
    @Test
    fun `fromModelCode returns 868_915 for appropriate model codes`() {
        assertEquals(FrequencyBand.BAND_868_915, FrequencyBand.fromModelCode(0x11))
        assertEquals(FrequencyBand.BAND_868_915, FrequencyBand.fromModelCode(0x04))
        assertEquals(FrequencyBand.BAND_868_915, FrequencyBand.fromModelCode(0x05))
    }

    @Test
    fun `fromModelCode returns 433 for appropriate model codes`() {
        assertEquals(FrequencyBand.BAND_433, FrequencyBand.fromModelCode(0x12))
        assertEquals(FrequencyBand.BAND_433, FrequencyBand.fromModelCode(0x06))
        assertEquals(FrequencyBand.BAND_433, FrequencyBand.fromModelCode(0x07))
    }

    @Test
    fun `fromModelCode returns UNKNOWN for unrecognized codes`() {
        assertEquals(FrequencyBand.UNKNOWN, FrequencyBand.fromModelCode(0x00))
        assertEquals(FrequencyBand.UNKNOWN, FrequencyBand.fromModelCode(0x03))
    }

    @Test
    fun `fromFilename detects 433 band`() {
        assertEquals(FrequencyBand.BAND_433, FrequencyBand.fromFilename("rnode_firmware_rak4631_433.zip"))
        assertEquals(FrequencyBand.BAND_433, FrequencyBand.fromFilename("firmware_433mhz.bin"))
    }

    @Test
    fun `fromFilename detects 868_915 band`() {
        assertEquals(FrequencyBand.BAND_868_915, FrequencyBand.fromFilename("rnode_firmware_rak4631_868.zip"))
        assertEquals(FrequencyBand.BAND_868_915, FrequencyBand.fromFilename("firmware_915mhz.bin"))
    }

    @Test
    fun `fromFilename defaults to 868_915 for unknown`() {
        assertEquals(FrequencyBand.BAND_868_915, FrequencyBand.fromFilename("rnode_firmware.zip"))
        assertEquals(FrequencyBand.BAND_868_915, FrequencyBand.fromFilename("unknown.bin"))
    }

    @Test
    fun `frequency band has correct display names`() {
        assertEquals("868/915 MHz", FrequencyBand.BAND_868_915.displayName)
        assertEquals("433 MHz", FrequencyBand.BAND_433.displayName)
        assertEquals("Unknown", FrequencyBand.UNKNOWN.displayName)
    }

    @Test
    fun `frequency band has correct model suffix`() {
        assertEquals("_868", FrequencyBand.BAND_868_915.modelSuffix)
        assertEquals("_433", FrequencyBand.BAND_433.modelSuffix)
        assertEquals("", FrequencyBand.UNKNOWN.modelSuffix)
    }
}

class GitHubReleaseTest {
    @Test
    fun `version extracts from tag_name with v prefix`() {
        val release =
            GitHubRelease(
                id = 1,
                tagName = "v1.78",
                name = "RNode Firmware 1.78",
                createdAt = "2024-01-01T00:00:00Z",
                htmlUrl = "https://github.com/markqvist/RNode_Firmware/releases/tag/v1.78",
            )

        assertEquals("1.78", release.version)
    }

    @Test
    fun `version extracts from tag_name with V prefix`() {
        val release =
            GitHubRelease(
                id = 1,
                tagName = "V1.78",
                name = "RNode Firmware 1.78",
                createdAt = "2024-01-01T00:00:00Z",
                htmlUrl = "https://github.com/test",
            )

        assertEquals("1.78", release.version)
    }

    @Test
    fun `version preserves tag without prefix`() {
        val release =
            GitHubRelease(
                id = 1,
                tagName = "1.78",
                name = "RNode Firmware 1.78",
                createdAt = "2024-01-01T00:00:00Z",
                htmlUrl = "https://github.com/test",
            )

        assertEquals("1.78", release.version)
    }
}

class GitHubAssetTest {
    @Test
    fun `browserDownloadUrl property works`() {
        val asset =
            GitHubAsset(
                id = 1,
                name = "firmware.zip",
                size = 1000,
                browserDownloadUrl = "https://example.com/firmware.zip",
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-01T00:00:00Z",
            )

        assertEquals("https://example.com/firmware.zip", asset.browserDownloadUrl)
    }
}
