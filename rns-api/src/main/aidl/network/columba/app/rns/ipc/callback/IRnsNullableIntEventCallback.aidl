// Observer callback for nullable-Int-emitting StateFlow surfaces:
//   - RnsTelephony.activeProfileCode
//
// AIDL has no nullable primitive, so nullness travels as (value, hasValue) —
// the same convention IRnsTelephony.initiateCall uses for profileCode.
package network.columba.app.rns.ipc.callback;

oneway interface IRnsNullableIntEventCallback {
    void onInt(int value, boolean hasValue);
}
