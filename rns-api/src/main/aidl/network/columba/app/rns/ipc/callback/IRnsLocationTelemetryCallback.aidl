// Observer callback for `RnsTelemetry.locationTelemetryFlow`
// (SharedFlow<LocationTelemetry>). Replaces the prior
// `IRnsStringEventCallback`-on-this-flow contract — Phase 2 of the
// Sideband-interop refactor moved telemetry off JSON-string marshaling
// onto a Parcelable so consumers don't pay a JSON encode/decode per
// location update.
package network.columba.app.rns.ipc.callback;

import network.columba.app.rns.api.model.LocationTelemetry;

oneway interface IRnsLocationTelemetryCallback {
    void onLocationTelemetry(in LocationTelemetry payload);
}
