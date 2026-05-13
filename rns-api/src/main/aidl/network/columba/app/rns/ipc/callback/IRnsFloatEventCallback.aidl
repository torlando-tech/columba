// Observer callback for StateFlow<Float> (RnsNomadnet.nomadnetDownloadProgressFlow).
package network.columba.app.rns.ipc.callback;

oneway interface IRnsFloatEventCallback {
    void onFloat(float value);
}
