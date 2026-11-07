package network.columba.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [ColumbaDatabase.MIGRATIONS] as a complete, ordered upgrade chain.
 *
 * Room can only open a database when every step between the stored version and
 * the current one is installed. A missing, duplicated or out-of-order step is
 * invisible at compile time and only surfaces on real user data — or, for the
 * `:reticulum` service process, as an "missing migration path" crash when it
 * starts before the app process migrated. Both DI sites now install exactly
 * [ColumbaDatabase.MIGRATIONS], so chain integrity is the only thing left to
 * assert here.
 *
 * Schema-version agreement (the chain ending at `@Database(version = N)`) is
 * covered by the exported-schema validation tests next to this file.
 */
class MigrationChainTest {
    @Test
    fun `migrations form one ascending contiguous chain starting at version 1`() {
        val steps = ColumbaDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }

        assertEquals("the chain must start at version 1", 1, steps.first().first)
        steps.forEachIndexed { index, (startVersion, endVersion) ->
            assertEquals(
                "step $index must start where the previous step ended",
                index + 1,
                startVersion,
            )
            assertEquals(
                "step $index ($startVersion -> $endVersion) must advance exactly one version",
                startVersion + 1,
                endVersion,
            )
        }
    }

    @Test
    fun `the chain has no duplicate steps`() {
        val steps = ColumbaDatabase.MIGRATIONS.map { it.startVersion }

        assertEquals(steps.size, steps.toSet().size)
    }
}
