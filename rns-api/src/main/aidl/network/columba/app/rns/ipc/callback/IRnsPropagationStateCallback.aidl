// Observer callback for SharedFlow<PropagationState> (RnsLxmf.propagationStateFlow).
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.PropagationState;

oneway interface IRnsPropagationStateCallback {
    void onPropagationState(in PropagationState state);
}
