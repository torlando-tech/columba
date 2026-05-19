package network.columba.app.rns.host.process

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Which Columba process this Hilt graph is being instantiated in.
 *
 * The dual-build runs the UI in `network.columba.app[.debug]` and the RNS stack in
 * `network.columba.app[.debug]:reticulum`. Both processes resolve the same
 * `@InstallIn(SingletonComponent::class)` modules — Hilt's SingletonComponent is
 * per-process, not per-app, so each process gets its own graph and each provider
 * runs once per process. That means a naive `RnsBackend` provider would
 * accidentally construct `ChaquopyRnsBackend` (or `NativeRnsBackend`) in both
 * processes; the UI's copy would then load CPython / bind sockets in the wrong
 * process.
 *
 * The process-aware backend module reads this enum at provider time and
 * branches: `RETICULUM` gets the flavor-local backend, `UI` / `TEST` gets the
 * AIDL-proxy [BoundRnsBackend].
 */
enum class ProcessType {
    /** Foreground UI process (`network.columba.app[.debug]`). */
    UI,

    /** `:reticulum` foreground service process where the live RNS stack lives. */
    RETICULUM,

    /** Robolectric / instrumented test environments — no real process boundary. */
    TEST,
}

/**
 * Resolves the [ProcessType] of the current process.
 *
 * Lifts the process-name detection logic that previously lived inline in
 * `ColumbaApplication.getCurrentProcessName()`. Pure utility: no Android
 * dependency beyond [Context], and the result is stable for the lifetime of
 * the process so callers can cache the value.
 */
object ProcessDetector {
    /**
     * Detect the [ProcessType] for the running process.
     *
     * @param context any [Context] (typically the application context). Used to
     *   resolve the legacy [ActivityManager] fallback on API < 28; not retained.
     */
    fun detect(context: Context): ProcessType {
        if (isRunningInTest()) return ProcessType.TEST
        val name = currentProcessName(context) ?: return ProcessType.UI
        return if (name.contains(":reticulum")) ProcessType.RETICULUM else ProcessType.UI
    }

    /**
     * Best-effort lookup of the current process name. Returns null on the
     * legacy fallback path when [ActivityManager] is unavailable — callers
     * treat null as UI (the dominant non-`:reticulum` case).
     */
    fun currentProcessName(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                val mypid = Process.myPid()
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.runningAppProcesses?.find { it.pid == mypid }?.processName
            }.getOrNull()
        }

    /**
     * True when the loader can find an AndroidX Espresso class — the signal
     * we already use elsewhere to identify instrumented-test launches.
     */
    private fun isRunningInTest(): Boolean =
        runCatching { Class.forName("androidx.test.espresso.Espresso") }.isSuccess
}
