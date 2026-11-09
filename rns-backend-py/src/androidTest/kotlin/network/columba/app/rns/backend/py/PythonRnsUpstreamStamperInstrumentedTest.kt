package network.columba.app.rns.backend.py

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.PyObject
import network.columba.app.rns.api.model.ReticulumConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Runs the bundled LXMF/RNS dispatcher in Chaquopy, with no native callback. */
@RunWith(AndroidJUnit4::class)
class PythonRnsUpstreamStamperInstrumentedTest {
    @Test
    fun fractionalProofUsesStockAndroidWorkersAndReapsChildren() =
        withRuntime { runtime, _, scope ->
            execute(runtime, scope, VERIFY_FRACTIONAL_PROOF)
            execute(
                runtime,
                scope,
                """
                for invalid_cost in (-1, RNS.Transport.STAMP_COST_CAP + 1):
                    try:
                        LXMF.LXStamper.generate_stamp(os.urandom(32), invalid_cost)
                    except ValueError:
                        pass
                    else:
                        raise AssertionError("Invalid stamp cost was accepted")
                """.trimIndent(),
            )
        }

    @Test
    fun deferredStampCancellationAndHostStopAllowRuntimeReuse() =
        withRuntime { runtime, config, scope ->
            execute(runtime, scope, START_DEFERRED_STAMP)
            execute(
                runtime,
                scope,
                """
                router.cancel_outbound(message.message_id)
                worker.join(15)
                assert not worker.is_alive(), "Cancelled deferred worker did not finish"
                assert message.state == LXMF.LXMessage.CANCELLED
                assert message.stamp is None, "Cancellation produced a stamp"
                assert not RNS.Stamps.active_jobs, "Cancelled RNS jobs remain registered"
                assert not multiprocessing.active_children(), "Cancelled child processes remain"
                """.trimIndent(),
            )
            execute(runtime, scope, VERIFY_FRACTIONAL_PROOF)

            execute(runtime, scope, START_DEFERRED_STAMP)
            runtime.stop()
            assertFalse(runtime.isRunning)
            execute(
                runtime,
                scope,
                """
                worker.join(15)
                assert not worker.is_alive(), "Host stop left a deferred worker running"
                assert message.state == LXMF.LXMessage.CANCELLED
                assert not RNS.Stamps.active_jobs, "Host stop left active RNS jobs"
                assert not multiprocessing.active_children(), "Host stop leaked child processes"
                """.trimIndent(),
            )

            runtime.start(config)
            assertTrue(runtime.isRunning)
            scope.callAttr("__setitem__", "router", runtime.lxmRouter)
            scope.callAttr("__setitem__", "source", runtime.localDestination)
            execute(runtime, scope, VERIFY_FRACTIONAL_PROOF)
        }

    private fun withRuntime(block: (PythonRnsRuntime, ReticulumConfig, PyObject) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = File(context.cacheDir, "upstream-stamper-${UUID.randomUUID()}")
        check(storage.mkdirs())
        val runtime = PythonRnsRuntime(context)
        val config =
            ReticulumConfig(
                storagePath = storage.absolutePath,
                enabledInterfaces = emptyList(),
                preferOwnInstance = true,
                enableTransport = false,
            )
        try {
            runtime.start(config)
            assertTrue(runtime.isRunning)
            val scope = runtime.python.builtins.callAttr("dict")
            scope.callAttr("__setitem__", "router", runtime.lxmRouter)
            scope.callAttr("__setitem__", "source", runtime.localDestination)
            execute(
                runtime,
                scope,
                """
                import hashlib
                import multiprocessing
                import os
                import sys
                import threading
                import time
                from decimal import Decimal, localcontext
                import LXMF
                import LXMF.LXStamper
                import RNS
                import RNS.Stamps
                assert RNS.vendor.platformutils.is_android(), "Not the Android dispatcher"
                """.trimIndent(),
            )
            block(runtime, config, scope)
        } finally {
            runtime.stop()
            storage.deleteRecursively()
        }
    }

    private fun execute(runtime: PythonRnsRuntime, scope: PyObject, code: String) {
        runtime.python.builtins.callAttr("exec", code, scope)
    }

    private companion object {
        val VERIFY_FRACTIONAL_PROOF =
            """
            material = os.urandom(32)
            selected_android_worker = []
            def observe_dispatch(frame, event, arg):
                if event == "call" and frame.f_code is RNS.Stamps.job_android_persistent.__code__:
                    selected_android_worker.append(True)
                    sys.setprofile(None)
            sys.setprofile(observe_dispatch)
            try:
                stamp, value = LXMF.LXStamper.generate_stamp(material, 8.5)
            finally:
                sys.setprofile(None)
            assert selected_android_worker, "Stock Android persistent worker was not selected"
            assert isinstance(stamp, bytes) and len(stamp) == 32
            workblock = LXMF.LXStamper.stamp_workblock(material)
            # Independent SHA-256 target, not the generator's own validator.
            with localcontext() as decimal_context:
                decimal_context.prec = 100
                target = int(Decimal(2) ** Decimal("247.5"))
            digest = int.from_bytes(hashlib.sha256(workblock + stamp).digest(), "big")
            assert digest <= target, "Generated proof does not meet fractional cost 8.5"
            assert value >= 8.5
            assert LXMF.LXStamper.stamp_valid(stamp, 8.5, workblock)
            assert not RNS.Stamps.active_jobs, "Completed stamp job was not released"
            assert not multiprocessing.active_children(), "Completed stamp leaked child processes"
            """.trimIndent()

        val START_DEFERRED_STAMP =
            """
            destination = RNS.Destination(
                RNS.Identity(), RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery"
            )
            message = LXMF.LXMessage(
                destination, source, "Cancellation fixture", "Upstream stamp",
                desired_method=LXMF.LXMessage.DIRECT, stamp_cost=64
            )
            router.handle_outbound(message)
            worker = threading.Thread(target=router.process_deferred_stamps, daemon=True)
            worker.start()
            deadline = time.monotonic() + 30
            while message.message_id not in RNS.Stamps.active_jobs:
                assert worker.is_alive(), "Deferred stamp worker exited before starting"
                assert time.monotonic() < deadline, "Deferred stamp job did not become active"
                time.sleep(0.01)
            """.trimIndent()
    }
}
