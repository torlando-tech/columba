package com.lxmf.messenger.detekt.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BleLoggingTagRuleTest {

    private val rule = BleLoggingTagRule(Config.empty)

    @Test
    fun `valid TAG pattern passes`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.client

            class BleScanner {
                companion object {
                    private const val TAG = "Columba:BLE:K:Scan"
                }
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Valid TAG pattern should not report any issues")
    }

    @Test
    fun `invalid TAG pattern reports issue`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.client

            class BleScanner {
                companion object {
                    private const val TAG = "Columba:Kotlin:BleScanner"
                }
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "Invalid TAG pattern should report an issue")
        assert(findings[0].message.contains("must follow pattern"))
    }

    @Test
    fun `missing TAG reports issue`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.client

            class BleScanner {
                companion object {
                    private const val SOME_OTHER_CONST = "value"
                }
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "Missing TAG should report an issue")
        assert(findings[0].message.contains("must have a TAG constant"))
    }

    @Test
    fun `missing companion object reports issue`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.client

            class BleScanner {
                private val someField = "value"
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "Missing companion object should report an issue")
    }

    @Test
    fun `non-BLE package is ignored`() {
        val code = """
            package com.lxmf.messenger.reticulum.bridge

            class SomeBridge {
                // No TAG needed - not in BLE package
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Non-BLE package should be ignored")
    }

    @Test
    fun `data class is ignored`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.model

            data class BleDevice(val address: String, val name: String)
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Data class should be ignored")
    }

    @Test
    fun `enum class is ignored`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.model

            enum class BleConnectionState { CONNECTED, DISCONNECTED }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Enum class should be ignored")
    }

    @Test
    fun `exception class is ignored`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.util

            class TimeoutException(message: String) : Exception(message)
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Exception class should be ignored")
    }

    @Test
    fun `interface is ignored`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.client

            interface BleCallback {
                fun onConnected()
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Interface should be ignored")
    }

    @Test
    fun `sealed class is ignored`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.util

            sealed class BleOperation {
                data class Connect(val address: String) : BleOperation()
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Sealed class should be ignored")
    }

    @Test
    fun `model package is ignored`() {
        val code = """
            package com.lxmf.messenger.reticulum.ble.model

            class BleConfig {
                val timeout = 5000
            }
        """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Classes in model package should be ignored")
    }

    @Test
    fun `various valid component names pass`() {
        val validTags = listOf(
            "Columba:BLE:K:Bridge",
            "Columba:BLE:K:Scan",
            "Columba:BLE:K:Client",
            "Columba:BLE:K:Server",
            "Columba:BLE:K:Adv",
            "Columba:BLE:K:Queue",
            "Columba:BLE:K:ConnMgr",
            "Columba:BLE:K:Pair",
        )

        for (tag in validTags) {
            val code = """
                package com.lxmf.messenger.reticulum.ble.service

                class TestComponent {
                    companion object {
                        private const val TAG = "$tag"
                    }
                }
            """.trimIndent()

            val findings = rule.lint(code)
            assertEquals(0, findings.size, "TAG '$tag' should be valid")
        }
    }

    @Test
    fun `invalid patterns are rejected`() {
        val invalidTags = listOf(
            "BleScanner",                      // No prefix
            "Columba:Kotlin:BleScanner",       // Old pattern
            "Columba:BLE:Py:Driver",           // Python pattern (K expected)
            "Columba:BLE:K:",                  // Missing component
            "Columba:BLE:K:Scan:Extra",        // Too many segments
            "columba:ble:k:scan",              // Wrong case
        )

        for (tag in invalidTags) {
            val code = """
                package com.lxmf.messenger.reticulum.ble.service

                class TestComponent {
                    companion object {
                        private const val TAG = "$tag"
                    }
                }
            """.trimIndent()

            val findings = rule.lint(code)
            assertEquals(1, findings.size, "TAG '$tag' should be invalid")
        }
    }
}
