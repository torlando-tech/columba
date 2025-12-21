package com.lxmf.messenger.crypto

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native Kotlin implementation of LXMF stamp generation.
 *
 * This replaces Python's multiprocessing-based stamp generation which fails on Android
 * due to lack of sem_open support and aggressive process killing by Android.
 *
 * The algorithm matches LXMF's LXStamper.py exactly:
 * 1. Generate workblock using HKDF expansion
 * 2. Find a stamp (random 32 bytes) where SHA256(workblock + stamp) has enough leading zeros
 */
@Singleton
class StampGenerator
    @Inject
    constructor() {
        companion object {
            private const val TAG = "StampGenerator"
            const val STAMP_SIZE = 32 // 256 bits / 8
            const val WORKBLOCK_EXPAND_ROUNDS = 3000
            const val WORKBLOCK_EXPAND_ROUNDS_PN = 1000
            const val HKDF_OUTPUT_LENGTH = 256
            private const val PROGRESS_LOG_INTERVAL = 8000
        }

        data class StampResult(
            val stamp: ByteArray?,
            val value: Int,
            val rounds: Long,
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as StampResult
                if (stamp != null) {
                    if (other.stamp == null) return false
                    if (!stamp.contentEquals(other.stamp)) return false
                } else if (other.stamp != null) {
                    return false
                }
                if (value != other.value) return false
                if (rounds != other.rounds) return false
                return true
            }

            override fun hashCode(): Int {
                var result = stamp?.contentHashCode() ?: 0
                result = 31 * result + value
                result = 31 * result + rounds.hashCode()
                return result
            }
        }

        /**
         * Generate a workblock for stamp validation.
         *
         * Matches Python's stamp_workblock():
         * ```python
         * for n in range(expand_rounds):
         *     workblock += HKDF(length=256, derive_from=material,
         *                       salt=SHA256(material + msgpack(n)), context=None)
         * ```
         */
        fun generateWorkblock(
            material: ByteArray,
            expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS,
        ): ByteArray {
            val output = ByteArrayOutputStream(expandRounds * HKDF_OUTPUT_LENGTH)

            for (n in 0 until expandRounds) {
                // salt = SHA256(material + msgpack(n))
                val msgpackN = packInt(n)
                val saltInput = material + msgpackN
                val salt = sha256(saltInput)

                // HKDF expand
                val expanded =
                    hkdfExpand(
                        ikm = material,
                        salt = salt,
                        info = ByteArray(0), // context=None
                        length = HKDF_OUTPUT_LENGTH,
                    )
                output.write(expanded)
            }

            return output.toByteArray()
        }

        /**
         * Generate a valid stamp for the given workblock.
         *
         * Uses Kotlin coroutines for parallel stamp search across multiple CPU cores.
         *
         * @param workblock The pre-generated workblock
         * @param stampCost The required stamp cost (number of leading zero bits)
         * @return StampResult containing the stamp, its value, and total rounds tried
         */
        suspend fun generateStamp(
            workblock: ByteArray,
            stampCost: Int,
        ): StampResult =
            coroutineScope {
                val startTime = System.currentTimeMillis()
                val numWorkers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

                Log.d(TAG, "Starting stamp generation with cost $stampCost using $numWorkers workers")

                // Use atomic reference for thread-safe result sharing
                val found = AtomicReference<ByteArray?>(null)
                val roundCounters = LongArray(numWorkers)

                val jobs =
                    (0 until numWorkers).map { workerId ->
                        async(Dispatchers.Default) {
                            val localRandom = SecureRandom()
                            var localRounds = 0L
                            val stamp = ByteArray(STAMP_SIZE)

                            while (found.get() == null && isActive) {
                                localRandom.nextBytes(stamp)
                                localRounds++

                                if (isStampValid(stamp, stampCost, workblock)) {
                                    // Use compareAndSet to ensure only one worker sets the result
                                    found.compareAndSet(null, stamp.copyOf())
                                    break
                                }

                                // Periodically yield to allow cancellation and log progress
                                if (localRounds % 1000 == 0L) {
                                    yield()
                                }

                                if (localRounds % PROGRESS_LOG_INTERVAL == 0L) {
                                    roundCounters[workerId] = localRounds
                                    val totalRounds = roundCounters.sum()
                                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                                    if (elapsed > 0) {
                                        val speed = (totalRounds / elapsed).toLong()
                                        Log.d(TAG, "Stamp generation running. $totalRounds rounds, $speed rounds/sec")
                                    }
                                }
                            }

                            roundCounters[workerId] = localRounds
                            localRounds
                        }
                    }

                // Wait for all workers to complete
                jobs.awaitAll()

                val totalRounds = roundCounters.sum()
                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                val speed = if (duration > 0) (totalRounds / duration).toLong() else 0

                val resultStamp = found.get()
                val value = if (resultStamp != null) stampValue(workblock, resultStamp) else 0

                Log.d(
                    TAG,
                    "Stamp generation complete: value=$value, rounds=$totalRounds, " +
                        "duration=${String.format(java.util.Locale.US, "%.2f", duration)}s, speed=$speed rounds/sec",
                )

                StampResult(resultStamp, value, totalRounds)
            }

        /**
         * Convenience method that generates workblock and stamp in one call.
         */
        suspend fun generateStampWithWorkblock(
            messageId: ByteArray,
            stampCost: Int,
            expandRounds: Int = WORKBLOCK_EXPAND_ROUNDS,
        ): StampResult {
            Log.d(TAG, "Generating workblock with $expandRounds rounds...")
            val workblockStart = System.currentTimeMillis()
            val workblock = generateWorkblock(messageId, expandRounds)
            val workblockTime = System.currentTimeMillis() - workblockStart
            Log.d(TAG, "Workblock generated in ${workblockTime}ms (${workblock.size} bytes)")

            return generateStamp(workblock, stampCost)
        }

        /**
         * Check if a stamp is valid for the given cost and workblock.
         *
         * Matches Python's stamp_valid():
         * ```python
         * target = 0b1 << 256-target_cost
         * result = SHA256(workblock + stamp)
         * return int(result) <= target
         * ```
         */
        fun isStampValid(
            stamp: ByteArray,
            targetCost: Int,
            workblock: ByteArray,
        ): Boolean {
            val target = BigInteger.ONE.shiftLeft(256 - targetCost)
            val hash = sha256(workblock + stamp)
            val result = BigInteger(1, hash) // Positive BigInteger from bytes
            return result <= target
        }

        /**
         * Calculate the value of a stamp (number of leading zero bits).
         *
         * Matches Python's stamp_value():
         * ```python
         * material = SHA256(workblock + stamp)
         * i = int(material)
         * value = 0
         * while ((i & (1 << (bits - 1))) == 0):
         *     i = (i << 1)
         *     value += 1
         * ```
         */
        fun stampValue(
            workblock: ByteArray,
            stamp: ByteArray,
        ): Int {
            val hash = sha256(workblock + stamp)
            val bigInt = BigInteger(1, hash)

            // Count leading zeros
            // 256 - bitLength gives the number of leading zeros
            return 256 - bigInt.bitLength()
        }

        // ==================== Crypto Primitives ====================

        /**
         * SHA-256 hash.
         */
        fun sha256(data: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data)
        }

        /**
         * HKDF (HMAC-based Key Derivation Function) as per RFC 5869.
         *
         * This matches RNS.Cryptography.hkdf() which uses SHA-256.
         */
        fun hkdfExpand(
            ikm: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            // Extract phase: PRK = HMAC-SHA256(salt, IKM)
            val prk = hmacSha256(salt, ikm)

            // Expand phase
            val hashLen = 32 // SHA-256 output length
            val n = (length + hashLen - 1) / hashLen
            val output = ByteArrayOutputStream(length)

            var t = ByteArray(0)
            for (i in 1..n) {
                val input = t + info + byteArrayOf(i.toByte())
                t = hmacSha256(prk, input)
                output.write(t, 0, minOf(t.size, length - output.size()))
            }

            return output.toByteArray()
        }

        /**
         * HMAC-SHA256.
         */
        private fun hmacSha256(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(key, "HmacSHA256")
            mac.init(secretKey)
            return mac.doFinal(data)
        }

        /**
         * Pack an integer using MessagePack format.
         * Matches Python's msgpack.packb(n).
         */
        fun packInt(n: Int): ByteArray {
            val packer = MessagePack.newDefaultBufferPacker()
            packer.packInt(n)
            return packer.toByteArray()
        }
    }
