package network.columba.app.nomadnet

import android.content.Context
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.model.NomadnetLinkStats
import network.columba.app.rns.api.model.NomadnetMediaResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for [PageImageLoader] — the page-image orchestration: cache-hit fast
 * path, the auto bandwidth gate, manual/never/explicit-load semantics, denial
 * surfacing, the WebP-only cache rule, and the per-image size cap.
 *
 * The loader's fetch queue runs on Dispatchers.IO, so assertions wait on a
 * short polling loop (deterministic: every state transition is driven by the
 * mocked [RnsNomadnet], which returns immediately).
 */
@RunWith(RobolectricTestRunner::class)
class PageImageLoaderTest {
    private lateinit var context: Context
    private lateinit var cache: NomadNetImageCache
    private lateinit var scope: CoroutineScope
    private lateinit var nomadnet: RnsNomadnet
    private lateinit var loader: PageImageLoader

    private val nodeHash = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
    private var imageMode = ImageLoadingMode.AUTO

    /** Released inside the stats stub so "the gate ran" is provable. */
    private var gateRan = CountDownLatch(1)

    /** Minimal 16-byte WebP payload (RIFF....WEBP). */
    private fun writeWebP(dir: File, name: String): File = File(dir, name).apply {
        writeBytes(
            buildString {
                append("RIFF")
                repeat(4) { append('0') }
                append("WEBP")
                repeat(4) { append(' ') }
            }.toByteArray(),
        )
    }

    private fun mediaResult(file: File, size: Long = file.length()): NomadnetMediaResult =
        NomadnetMediaResult(
            filePath = file.absolutePath,
            fileName = file.name,
            fileSize = size,
            path = file.name,
        )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.cacheDir.deleteRecursively()
        cache = NomadNetImageCache(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        nomadnet = mockk()
        coEvery { nomadnet.getNomadnetRequestStatus() } returns "idle"
        coEvery { nomadnet.getNomadnetLinkStats(any()) } answers {
            gateRan.countDown()
            null
        }
        imageMode = ImageLoadingMode.AUTO
        loader =
            PageImageLoader(
                nomadnet = nomadnet,
                cache = cache,
                scope = scope,
                currentNodeHash = { nodeHash },
                imageLoadingMode = { imageMode },
                lastResponseSpeedBps = { null },
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
        context.cacheDir.deleteRecursively()
        clearAllMocks()
    }

    private fun ref(url: String, key: String): ParsedImageRef = ParsedImageRef(url = url, key = key)

    /** Poll until the state for [key] satisfies [predicate] or time runs out. */
    private fun awaitState(
        key: String,
        timeoutMs: Long = 5_000,
        predicate: (PageImageState) -> Boolean,
    ): PageImageState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            loader.imageStates.value[key]?.let { if (predicate(it)) return it }
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for state of $key; last=${loader.imageStates.value[key]}")
    }

    // ── Cache hit ──────────────────────────────────────────────────────────

    @Test
    fun `cache hit renders immediately with zero requests`() {
        val cached = writeWebP(context.cacheDir, "seed.webp")
        val put = cache.put("$nodeHash:/media/seed.webp", cached)
        assertNotNull(put)

        loader.scan(listOf(ref(":/media/seed.webp", "img")))

        val state = loader.imageStates.value["img"]
        assertNotNull(state)
        assertEquals(PageImageStatus.LOADED, state!!.status)
        assertNotNull(state.file)
        coVerify(exactly = 0) { nomadnet.requestNomadnetMedia(any(), any(), any()) }
    }

    // ── Auto gate ──────────────────────────────────────────────────────────

    @Test
    fun `auto mode with fast link loads the image`() {
        coEvery { nomadnet.getNomadnetLinkStats(any()) } answers {
            gateRan.countDown()
            NomadnetLinkStats(rttSeconds = 0.2, expectedRateBps = 50_000L)
        }
        val stage = writeWebP(context.cacheDir, "auto.webp")
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/auto.webp", 60f) } returns
            Result.success(mediaResult(stage))

        loader.scan(listOf(ref(":/media/auto.webp", "img")))

        val state = awaitState("img") { it.status == PageImageStatus.LOADED }
        assertNotNull(state.file)
        coVerify(exactly = 1) { nomadnet.requestNomadnetMedia(nodeHash, "/media/auto.webp", 60f) }
    }

    @Test
    fun `auto mode with slow link leaves placeholders and makes no request`() {
        coEvery { nomadnet.getNomadnetLinkStats(any()) } answers {
            gateRan.countDown()
            NomadnetLinkStats(rttSeconds = 2.0, expectedRateBps = 50_000L)
        }

        loader.scan(listOf(ref(":/media/slow.webp", "img")))

        // The gate must have run before we assert (deterministic hand-off).
        assertTrue("gate did not run", gateRan.await(2, TimeUnit.SECONDS))
        assertEquals(PageImageStatus.PLACEHOLDER, loader.imageStates.value["img"]!!.status)
        coVerify(exactly = 0) { nomadnet.requestNomadnetMedia(any(), any(), any()) }
    }

    @Test
    fun `auto mode with no link stats does not load`() {
        // getNomadnetLinkStats returns null (default stub); gate fails closed.
        loader.scan(listOf(ref(":/media/noStats.webp", "img")))
        assertTrue("gate did not run", gateRan.await(2, TimeUnit.SECONDS))
        assertEquals(PageImageStatus.PLACEHOLDER, loader.imageStates.value["img"]!!.status)
        coVerify(exactly = 0) { nomadnet.requestNomadnetMedia(any(), any(), any()) }
    }

    // ── Manual / never / explicit ──────────────────────────────────────────

    @Test
    fun `manual mode waits for explicit load`() {
        imageMode = ImageLoadingMode.MANUAL
        val stage = writeWebP(context.cacheDir, "manual.webp")
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/manual.webp", 60f) } returns
            Result.success(mediaResult(stage))

        loader.scan(listOf(ref(":/media/manual.webp", "img")))
        // Give any (wrongly started) queue a beat to run.
        Thread.sleep(100)
        assertEquals(PageImageStatus.PLACEHOLDER, loader.imageStates.value["img"]!!.status)
        coVerify(exactly = 0) { nomadnet.requestNomadnetMedia(any(), any(), any()) }

        loader.loadImages(forceReload = false)
        awaitState("img") { it.status == PageImageStatus.LOADED }
        coVerify(exactly = 1) { nomadnet.requestNomadnetMedia(nodeHash, "/media/manual.webp", 60f) }
    }

    @Test
    fun `never mode blocks even explicit loads`() {
        imageMode = ImageLoadingMode.NEVER
        loader.scan(listOf(ref(":/media/never.webp", "img")))
        loader.loadImages(forceReload = false)
        loader.retryImage("img")
        Thread.sleep(100)
        assertEquals(PageImageStatus.PLACEHOLDER, loader.imageStates.value["img"]!!.status)
        coVerify(exactly = 0) { nomadnet.requestNomadnetMedia(any(), any(), any()) }
    }

    @Test
    fun `always mode loads without link stats`() {
        imageMode = ImageLoadingMode.ALWAYS
        val stage = writeWebP(context.cacheDir, "always.webp")
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/always.webp", 60f) } returns
            Result.success(mediaResult(stage))

        loader.scan(listOf(ref(":/media/always.webp", "img")))
        val state = awaitState("img") { it.status == PageImageStatus.LOADED }
        assertNotNull(state.file)
        coVerify(exactly = 1) { nomadnet.requestNomadnetMedia(nodeHash, "/media/always.webp", 60f) }
    }

    // ── Failure kinds ──────────────────────────────────────────────────────

    @Test
    fun `denied media surfaces DENIED not FAILED`() {
        imageMode = ImageLoadingMode.ALWAYS
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/gated.webp", 60f) } returns
            Result.failure(
                RnsException(RnsError.NomadnetRequestDenied(nodeHash, "/media/gated.webp")),
            )

        loader.scan(listOf(ref(":/media/gated.webp", "img")))

        val state = awaitState("img") { it.status == PageImageStatus.DENIED || it.status == PageImageStatus.FAILED }
        assertEquals(PageImageStatus.DENIED, state.status)
    }

    @Test
    fun `generic failure surfaces FAILED`() {
        imageMode = ImageLoadingMode.ALWAYS
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/broken.webp", 60f) } returns
            Result.failure(RuntimeException("link reset"))

        loader.scan(listOf(ref(":/media/broken.webp", "img")))

        val state = awaitState("img") { it.status == PageImageStatus.FAILED }
        assertEquals("link reset", state.error)
    }

    @Test
    fun `non-webp response is rejected as FAILED`() {
        imageMode = ImageLoadingMode.ALWAYS
        val png = File(context.cacheDir, "fake.webp").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 1, 2, 3, 4, 5, 6, 7, 8)) }
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/fake.webp", 60f) } returns
            Result.success(mediaResult(png))

        loader.scan(listOf(ref(":/media/fake.webp", "img")))

        val state = awaitState("img") { it.status == PageImageStatus.FAILED }
        assertTrue(state.error!!.contains("WebP"))
    }

    @Test
    fun `oversized media exceeds the cap and fails in always mode`() {
        // Torlando 2026-09-07: "always mode still hits the per-image size cap."
        imageMode = ImageLoadingMode.ALWAYS
        val small = writeWebP(context.cacheDir, "big.webp")
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/big.webp", 60f) } returns
            Result.success(mediaResult(small, size = NomadNetImagePolicy.MAX_IMAGE_BYTES + 1))

        loader.scan(listOf(ref(":/media/big.webp", "img")))

        val state = awaitState("img") { it.status == PageImageStatus.FAILED }
        assertTrue(state.error!!.contains("too large"))
    }

    // ── URL resolution / reload ────────────────────────────────────────────

    @Test
    fun `malformed image url yields an error placeholder`() {
        loader.scan(listOf(ref("not-a-valid-url", "img")))
        val state = loader.imageStates.value["img"]
        assertNotNull(state)
        assertNotNull(state!!.error)
        assertTrue(state.error!!.startsWith("Malformed image URL"))
        coVerify(exactly = 0) { nomadnet.requestNomadnetMedia(any(), any(), any()) }
    }

    @Test
    fun `cross-node url resolves to the explicit hash`() {
        val other = "deadbeefdeadbeefdeadbeefdeadbeef"
        imageMode = ImageLoadingMode.ALWAYS
        val stage = writeWebP(context.cacheDir, "xnode.webp")
        coEvery { nomadnet.requestNomadnetMedia(other, "/media/xnode.webp", 60f) } returns
            Result.success(mediaResult(stage))

        loader.scan(listOf(ref("$other:/media/xnode.webp", "img")))
        val state = awaitState("img") { it.status == PageImageStatus.LOADED }
        assertNotNull(state.file)
        coVerify(exactly = 1) { nomadnet.requestNomadnetMedia(other, "/media/xnode.webp", 60f) }
    }

    @Test
    fun `force reload drops the cache and re-downloads`() {
        imageMode = ImageLoadingMode.ALWAYS
        val cached = writeWebP(context.cacheDir, "reload.webp")
        cache.put("$nodeHash:/media/reload.webp", cached)
        loader.scan(listOf(ref(":/media/reload.webp", "img")))
        assertEquals(PageImageStatus.LOADED, loader.imageStates.value["img"]!!.status)

        val stage = writeWebP(context.cacheDir, "reload2.webp")
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/reload.webp", 60f) } returns
            Result.success(mediaResult(stage))

        loader.scan(listOf(ref(":/media/reload.webp", "img")), forceReload = true)
        awaitState("img") { it.status == PageImageStatus.LOADED && it.file != cached }
        coVerify(exactly = 1) { nomadnet.requestNomadnetMedia(nodeHash, "/media/reload.webp", 60f) }
    }

    @Test
    fun `second scan of a cached page is instant with zero requests`() {
        imageMode = ImageLoadingMode.ALWAYS
        val stage = writeWebP(context.cacheDir, "twice.webp")
        coEvery { nomadnet.requestNomadnetMedia(nodeHash, "/media/twice.webp", 60f) } returns
            Result.success(mediaResult(stage))

        loader.scan(listOf(ref(":/media/twice.webp", "img")))
        awaitState("img") { it.status == PageImageStatus.LOADED }

        // Navigate away and back (same page, image now cached).
        loader.clear()
        loader.scan(listOf(ref(":/media/twice.webp", "img")))
        assertEquals(PageImageStatus.LOADED, loader.imageStates.value["img"]!!.status)
        coVerify(exactly = 1) { nomadnet.requestNomadnetMedia(nodeHash, "/media/twice.webp", 60f) }
    }

    // ── Cache primitives ───────────────────────────────────────────────────

    @Test
    fun `image cache clear removes all entries`() {
        val a = writeWebP(context.cacheDir, "a.webp")
        val b = writeWebP(context.cacheDir, "b.webp")
        cache.put("$nodeHash:/media/a.webp", a)
        cache.put("$nodeHash:/media/b.webp", b)
        assertNotNull(cache.get("$nodeHash:/media/a.webp"))

        cache.clear()

        assertNull(cache.get("$nodeHash:/media/a.webp"))
        assertEquals(0L, cache.totalBytes())
    }
}
