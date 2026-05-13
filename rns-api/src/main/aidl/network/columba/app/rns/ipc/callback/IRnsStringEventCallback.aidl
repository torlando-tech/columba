// Observer callback for String-emitting Flow/StateFlow surfaces:
//   - RnsTelemetry.locationTelemetryFlow (SharedFlow<String>)
//   - RnsNomadnet.nomadnetRequestStatusFlow (StateFlow<String>)
//   - RnsTransportAdmin.bleConnectionsFlow (SharedFlow<String>)
//   - RnsTransportAdmin.debugInfoFlow (SharedFlow<String>)
//   - RnsTransportAdmin.interfaceStatusFlow (SharedFlow<String>)
//   - RnsTransportAdmin.reactionReceivedFlow (SharedFlow<String>)
package network.columba.app.rns.ipc.callback;

oneway interface IRnsStringEventCallback {
    void onString(String value);
}
