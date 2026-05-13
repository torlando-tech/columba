// Fire-once callback for AIDL methods returning a String (nullable).
// Used for getNomadnetRequestStatus, getOutboundPropagationNode (Result<String?>),
// getNextHopInterfaceName, getBleConnectionDetails.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.RnsError;

oneway interface IRnsStringCallback {
    void onSuccess(@nullable String value);
    void onError(in RnsError error);
}
