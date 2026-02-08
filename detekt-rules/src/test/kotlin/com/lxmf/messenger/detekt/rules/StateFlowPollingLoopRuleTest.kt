package com.lxmf.messenger.detekt.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateFlowPollingLoopRuleTest {
    private val rule = StateFlowPollingLoopRule(Config.empty)

    @Test
    fun `detects while loop with StateFlow value and delay`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.delay
            import kotlinx.coroutines.flow.MutableStateFlow

            class Timer {
                private val callState = MutableStateFlow<State>(State.Idle)

                suspend fun startTimer() {
                    while (callState.value is State.Active) {
                        delay(1000)
                    }
                }

                sealed class State {
                    object Idle : State()
                    object Active : State()
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "Should detect StateFlow polling loop with delay")
        assertTrue(findings[0].message.contains("Polling loop with StateFlow"))
    }

    @Test
    fun `detects while loop with equality check`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.delay
            import kotlinx.coroutines.flow.MutableStateFlow

            class Poller {
                private val _running = MutableStateFlow(true)

                suspend fun poll() {
                    while (_running.value == true) {
                        delay(500)
                        doWork()
                    }
                }

                fun doWork() {}
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "Should detect StateFlow polling loop with equality check")
    }

    @Test
    fun `ignores while loop without delay`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.flow.MutableStateFlow

            class Processor {
                private val queue = MutableStateFlow<List<Int>>(emptyList())

                fun process() {
                    while (queue.value.isNotEmpty()) {
                        // Process without delay - this is fine
                        println(queue.value.first())
                    }
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Should not flag while loop without delay")
    }

    @Test
    fun `ignores while loop without StateFlow value`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.delay

            class SimpleLoop {
                var running = true

                suspend fun run() {
                    while (running) {
                        delay(1000)
                    }
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Should not flag simple boolean while loop")
    }

    @Test
    fun `ignores regular for loops`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.delay

            class Batch {
                suspend fun process(items: List<Int>) {
                    for (item in items) {
                        delay(100)
                        println(item)
                    }
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "Should not flag for loops")
    }

    @Test
    fun `message includes helpful suggestions`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.delay
            import kotlinx.coroutines.flow.MutableStateFlow

            class Example {
                val state = MutableStateFlow(true)

                suspend fun loop() {
                    while (state.value) {
                        delay(1000)
                    }
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)

        val message = findings[0].message
        assertTrue(message.contains("UnconfinedTestDispatcher"), "Should mention test dispatcher")
        assertTrue(message.contains("just Runs"), "Should mention just Runs pattern")
        assertTrue(message.contains("collectLatest"), "Should suggest collectLatest alternative")
        assertTrue(message.contains("answers"), "Should suggest answers pattern")
    }

    @Test
    fun `detects nested delay in block`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.delay
            import kotlinx.coroutines.flow.MutableStateFlow

            class Nested {
                val state = MutableStateFlow(true)

                suspend fun run() {
                    while (state.value) {
                        if (someCondition()) {
                            delay(1000)
                        }
                    }
                }

                fun someCondition() = true
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "Should detect delay nested in if block")
    }

    @Test
    fun `detects pattern from CallViewModel - exact match`() {
        val code =
            """
            package com.lxmf.messenger.viewmodel

            import kotlinx.coroutines.delay
            import kotlinx.coroutines.launch

            class CallViewModel {
                val callState: Any = TODO()
                val _callDuration: Any = TODO()
                val viewModelScope: Any = TODO()

                private fun startDurationTimer() {
                    viewModelScope.launch {
                        _callDuration.value = 0L
                        while (callState.value is CallState.Active) {
                            delay(1000)
                            _callDuration.value += 1
                        }
                    }
                }

                sealed class CallState {
                    object Active : CallState()
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "Should detect the exact CallViewModel pattern")
    }
}
