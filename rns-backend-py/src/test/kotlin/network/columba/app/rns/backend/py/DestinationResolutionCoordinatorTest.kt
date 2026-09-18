package network.columba.app.rns.backend.py

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DestinationResolutionCoordinatorTest {
    @Test
    fun `concurrent callers for one hash construct exactly one destination`() {
        val joined = CountDownLatch(1)
        val subject = DestinationResolutionCoordinator<Any> { joined.countDown() }
        val cache = ConcurrentHashMap<String, Any>()
        val callersReady = CountDownLatch(2)
        val start = CountDownLatch(1)
        val constructionStarted = CountDownLatch(1)
        val allowConstructionToFinish = CountDownLatch(1)
        val constructionCount = AtomicInteger()
        val destination = Any()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val calls = List(2) {
                executor.submit<Any> {
                    callersReady.countDown()
                    check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    subject.resolve("same-hash", cache) {
                        constructionCount.incrementAndGet()
                        constructionStarted.countDown()
                        check(allowConstructionToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        destination
                    }
                }
            }

            assertTrue(callersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(constructionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue("the second caller must join the in-flight task", joined.awaitTimeout())
            allowConstructionToFinish.countDown()

            assertTrue(calls.all { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === destination })
            assertEquals(1, constructionCount.get())
            assertTrue(cache["same-hash"] === destination)
        } finally {
            allowConstructionToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent callers share one construction failure and a later caller can retry`() {
        val joined = CountDownLatch(1)
        val subject = DestinationResolutionCoordinator<Any> { joined.countDown() }
        val cache = ConcurrentHashMap<String, Any>()
        val callersReady = CountDownLatch(2)
        val start = CountDownLatch(1)
        val constructionStarted = CountDownLatch(1)
        val allowFailure = CountDownLatch(1)
        val constructionCount = AtomicInteger()
        val failure = IllegalStateException("registration failed")
        val executor = Executors.newFixedThreadPool(2)

        try {
            val calls = List(2) {
                executor.submit<Throwable?> {
                    callersReady.countDown()
                    check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    runCatching {
                        subject.resolve("same-hash", cache) {
                            constructionCount.incrementAndGet()
                            constructionStarted.countDown()
                            check(allowFailure.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            throw failure
                        }
                    }.exceptionOrNull()
                }
            }

            assertTrue(callersReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(constructionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue("the second caller must join before failure", joined.awaitTimeout())
            allowFailure.countDown()

            val observed = calls.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            assertTrue(observed.all { it === failure })
            assertEquals(1, constructionCount.get())
            assertFalse(cache.containsKey("same-hash"))

            val recovered = Any()
            assertTrue(subject.resolve("same-hash", cache) { recovered } === recovered)
        } finally {
            allowFailure.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted waiter cannot admit a second construction`() {
        val firstWaiterJoined = CountDownLatch(1)
        val secondWaiterJoined = CountDownLatch(1)
        val joinCount = AtomicInteger()
        val subject = DestinationResolutionCoordinator<Any> {
            if (joinCount.incrementAndGet() == 1) {
                firstWaiterJoined.countDown()
            } else {
                secondWaiterJoined.countDown()
            }
        }
        val cache = ConcurrentHashMap<String, Any>()
        val constructionStarted = CountDownLatch(1)
        val allowConstructionToFinish = CountDownLatch(1)
        val constructionCount = AtomicInteger()
        val destination = Any()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val owner = executor.submit<Any> {
                subject.resolve("same-hash", cache) {
                    constructionCount.incrementAndGet()
                    constructionStarted.countDown()
                    check(allowConstructionToFinish.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    destination
                }
            }
            assertTrue(constructionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val waiterFailure = AtomicReference<Throwable?>()
            val waiter = Thread {
                waiterFailure.set(
                    runCatching {
                        subject.resolve("same-hash", cache) {
                            constructionCount.incrementAndGet()
                            destination
                        }
                    }.exceptionOrNull(),
                )
            }
            waiter.start()
            assertTrue(firstWaiterJoined.awaitTimeout())
            waiter.interrupt()
            waiter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertTrue(waiterFailure.get() is InterruptedException)

            val third = executor.submit<Any> {
                subject.resolve("same-hash", cache) {
                    constructionCount.incrementAndGet()
                    destination
                }
            }
            assertTrue(
                "a third caller must still join the original construction",
                secondWaiterJoined.awaitTimeout(),
            )
            allowConstructionToFinish.countDown()

            assertTrue(owner.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === destination)
            assertTrue(third.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) === destination)
            assertEquals(1, constructionCount.get())
        } finally {
            allowConstructionToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `blocked construction for one hash does not block a different hash`() {
        val subject = DestinationResolutionCoordinator<Any>()
        val cache = ConcurrentHashMap<String, Any>()
        val slowStarted = CountDownLatch(1)
        val allowSlow = CountDownLatch(1)
        val fastResult = AtomicReference<Any>()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val slow = executor.submit<Any> {
                subject.resolve("slow-hash", cache) {
                    slowStarted.countDown()
                    check(allowSlow.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    Any()
                }
            }
            assertTrue(slowStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val fast = executor.submit<Any> {
                subject.resolve("fast-hash", cache) { Any() }.also(fastResult::set)
            }
            val completedFast = fast.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(completedFast === fastResult.get())
            assertFalse("the slow construction must still be blocked", slow.isDone)

            allowSlow.countDown()
            slow.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            allowSlow.countDown()
            executor.shutdownNow()
        }
    }

    private fun CountDownLatch.awaitTimeout(): Boolean =
        await(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private companion object {
        const val TIMEOUT_SECONDS = 2L
    }
}
