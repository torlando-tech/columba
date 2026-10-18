package network.columba.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Provides Columba-specific detekt rules.
 *
 * Rules receive the ruleset config (`columba`); detekt's `ConfigAware`
 * internally resolves each rule's own options under `columba.<RuleId>` —
 * do NOT pre-subConfig here or rules silently read a doubled path and
 * self-disable (`active` defaults to false).
 */
class ColumbaRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "columba"

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                BleLoggingTagRule(config),
                DiscardedConcurrencyReturnRule(config),
                NoCallCoordinatorGetInstanceOutsideHostRule(config),
                NoHardcodedUiString(config),
                NoRnsFacadeInPythonBackend(config),
                ReflectivelyKeptRequiredRule(config),
                NoRelaxedMocksRule(config),
                NoVerifyOnlyTestsRule(config),
                StateFlowPollingLoopRule(config),
            ),
        )
}
