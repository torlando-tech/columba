package network.columba.app.rns.host.call

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Stable ownership identity shared by lifecycle recorders for this service process. */
@Singleton
class ServiceProcessIdentity
    @Inject
    constructor() {
        val value: String = UUID.randomUUID().toString()
    }
