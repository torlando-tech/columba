package com.lxmf.messenger.data.repository

import app.cash.turbine.test
import com.lxmf.messenger.data.db.dao.ReceivedLocationDao
import com.lxmf.messenger.data.db.entity.ReceivedLocationEntity
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ReceivedLocationRepository.
 * Tests hash normalization, expiry filtering, and DAO delegation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceivedLocationRepositoryTest {
    private lateinit var receivedLocationDao: ReceivedLocationDao
    private lateinit var repository: ReceivedLocationRepository

    @Before
    fun setup() {
        receivedLocationDao = mockk()
        repository = ReceivedLocationRepository(receivedLocationDao)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== getContactLocation Tests ==========

    @Test
    fun `getContactLocation returns coordinates for valid non-expired location`() =
        runTest {
            val entity =
                ReceivedLocationEntity(
                    id = "loc-1",
                    senderHash = "peer1",
                    latitude = 48.8566,
                    longitude = 2.3522,
                    accuracy = 10f,
                    timestamp = System.currentTimeMillis(),
                    expiresAt = null,
                    receivedAt = System.currentTimeMillis(),
                )
            coEvery { receivedLocationDao.getLatestLocationForSender("peer1") } returns entity

            val result = repository.getContactLocation("PEER1")

            assertEquals(Pair(48.8566, 2.3522), result)
        }

    @Test
    fun `getContactLocation returns null when no location exists`() =
        runTest {
            coEvery { receivedLocationDao.getLatestLocationForSender("unknown") } returns null

            val result = repository.getContactLocation("unknown")

            assertNull(result)
        }

    @Test
    fun `getContactLocation returns null when location has expired`() =
        runTest {
            val entity =
                ReceivedLocationEntity(
                    id = "loc-2",
                    senderHash = "peer1",
                    latitude = 48.8566,
                    longitude = 2.3522,
                    accuracy = 10f,
                    timestamp = System.currentTimeMillis() - 60_000,
                    expiresAt = System.currentTimeMillis() - 1_000,
                    receivedAt = System.currentTimeMillis() - 60_000,
                )
            coEvery { receivedLocationDao.getLatestLocationForSender("peer1") } returns entity

            val result = repository.getContactLocation("peer1")

            assertNull(result)
        }

    @Test
    fun `getContactLocation normalizes hash to lowercase`() =
        runTest {
            coEvery { receivedLocationDao.getLatestLocationForSender("abcdef") } returns null

            repository.getContactLocation("ABCDEF")

            // If this didn't throw, the lowercase hash was used correctly
        }

    // ========== observeHasLocation Tests ==========

    @Test
    fun `observeHasLocation emits true for valid non-expired location`() =
        runTest {
            val entity =
                ReceivedLocationEntity(
                    id = "loc-1",
                    senderHash = "peer1",
                    latitude = 48.8566,
                    longitude = 2.3522,
                    accuracy = 10f,
                    timestamp = System.currentTimeMillis(),
                    expiresAt = null,
                    receivedAt = System.currentTimeMillis(),
                )
            every { receivedLocationDao.observeLatestLocationForSender("peer1") } returns flowOf(entity)

            repository.observeHasLocation("PEER1").test {
                assertTrue(awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `observeHasLocation emits false when no location exists`() =
        runTest {
            every { receivedLocationDao.observeLatestLocationForSender("unknown") } returns flowOf(null)

            repository.observeHasLocation("unknown").test {
                assertFalse(awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `observeHasLocation emits false when location has expired`() =
        runTest {
            val entity =
                ReceivedLocationEntity(
                    id = "loc-2",
                    senderHash = "peer1",
                    latitude = 48.8566,
                    longitude = 2.3522,
                    accuracy = 10f,
                    timestamp = System.currentTimeMillis() - 60_000,
                    expiresAt = System.currentTimeMillis() - 1_000,
                    receivedAt = System.currentTimeMillis() - 60_000,
                )
            every { receivedLocationDao.observeLatestLocationForSender("peer1") } returns flowOf(entity)

            repository.observeHasLocation("peer1").test {
                assertFalse(awaitItem())
                awaitComplete()
            }
        }
}
