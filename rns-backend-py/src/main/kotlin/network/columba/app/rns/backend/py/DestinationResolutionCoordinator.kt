package network.columba.app.rns.backend.py

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Coordinates destination construction without holding map locks during blocking Python work. */
internal class DestinationResolutionCoordinator<T : Any>(
    internal var onJoinInFlight: ((String) -> Unit)? = null,
) {
    private val inFlight = ConcurrentHashMap<String, FutureTask<T>>()

    fun resolve(
        key: String,
        cache: ConcurrentHashMap<String, T>,
        create: () -> T,
    ): T {
        cache[key]?.let { return it }

        val candidate = FutureTask(create)
        val existing = inFlight.putIfAbsent(key, candidate)
        val task = existing ?: candidate
        if (existing == null) {
            task.run()
        } else {
            onJoinInFlight?.invoke(key)
        }
        return await(key, cache, task, ownsTask = existing == null)
    }

    private fun await(
        key: String,
        cache: ConcurrentHashMap<String, T>,
        task: FutureTask<T>,
        ownsTask: Boolean,
    ): T = try {
        task.get().also { cache.putIfAbsent(key, it) }
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    } finally {
        if (ownsTask) inFlight.remove(key, task)
    }
}
