package com.lxmf.messenger.detekt.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Provides Columba-specific detekt rules.
 */
class ColumbaRuleSetProvider : RuleSetProvider {

    override val ruleSetId: String = "columba"

    override fun instance(config: Config): RuleSet {
        return RuleSet(
            ruleSetId,
            listOf(
                BleLoggingTagRule(config),
            ),
        )
    }
}
