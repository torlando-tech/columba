package com.lxmf.messenger.startup

import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.reticulum.model.Identity
import com.lxmf.messenger.util.HexUtils.toHexString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies that the identity in the Reticulum service matches the active identity in the database.
 *
 * This catches mismatches that can occur from:
 * - Interrupted identity switches
 * - Data imports that changed the active identity
 * - Service process surviving an app restart with different identity
 */
@Singleton
class ServiceIdentityVerifier
    @Inject
    constructor(
        private val identityRepository: IdentityRepository,
    ) {
        /**
         * Result of identity verification.
         *
         * @property isMatch true if identities match or can't be verified
         * @property serviceIdentityHash The identity hash from the service (null if unavailable)
         * @property dbIdentityHash The active identity hash from the database (null if unavailable)
         */
        data class VerificationResult(
            val isMatch: Boolean,
            val serviceIdentityHash: String?,
            val dbIdentityHash: String?,
        )

        /**
         * Verify that the service identity matches the database active identity.
         *
         * If either identity is unavailable (null), the verification returns true
         * (assumes match) since we can't verify. This allows the app to proceed
         * rather than blocking on edge cases.
         *
         * @param serviceIdentity The identity from the Reticulum service
         * @return VerificationResult indicating whether identities match
         */
        suspend fun verify(serviceIdentity: Identity?): VerificationResult {
            val serviceHash = serviceIdentity?.hash?.toHexString()
            val dbIdentity = identityRepository.getActiveIdentitySync()
            val dbHash = dbIdentity?.identityHash

            val isMatch =
                when {
                    serviceHash == null || dbHash == null -> true // Can't verify, assume OK
                    else -> serviceHash == dbHash
                }

            return VerificationResult(
                isMatch = isMatch,
                serviceIdentityHash = serviceHash,
                dbIdentityHash = dbHash,
            )
        }
    }
