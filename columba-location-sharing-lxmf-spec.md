# Columba Location Sharing - LXMF Technical Specification

## Overview

This document specifies the technical implementation for transmitting location data between Columba peers using LXMF (Lightweight Extensible Message Format) telemetry fields. The design prioritizes Sideband interoperability while optimizing for Columba's continuous location sharing use case.

---

## LXMF Field Constants

LXMF defines standard fields for telemetry in `LXMF/LXMF.py`:

```python
FIELD_TELEMETRY        = 0x02  # Single telemetry snapshot
FIELD_TELEMETRY_STREAM = 0x03  # Stream of telemetry updates
```

Columba will use `FIELD_TELEMETRY` (0x02) for location sharing messages, as each message represents a discrete location update rather than a continuous stream within a single message.

---

## Telemetry Data Format

### Sideband-Compatible Sensor Structure

Sideband's telemetry system uses a sensor-based architecture where each sensor type has a unique identifier (SID). For interoperability, Columba should use the same sensor IDs and data format.

**Location Sensor ID**: The location sensor in Sideband uses a specific SID. Based on the Reticulum-Telemetry-Hub patterns, location data includes:

```python
# Sensor type identifiers (Sideband convention)
SID_LOCATION = 0x01  # Physical location sensor

# Location sensor data structure
location_sensor = {
    "type": SID_LOCATION,
    "latitude": float,      # Decimal degrees, WGS84
    "longitude": float,     # Decimal degrees, WGS84
    "altitude": float,      # Meters above sea level (optional)
    "accuracy": float,      # Horizontal accuracy in meters
    "speed": float,         # Speed in m/s (optional)
    "bearing": float,       # Heading in degrees (optional)
    "timestamp": float,     # Unix timestamp of fix
}
```

### Columba Extended Location Data

For the festival/friend-finding use case, Columba extends the basic location with additional metadata:

```python
# Columba location telemetry structure
columba_location = {
    # Core location (Sideband-compatible)
    "type": SID_LOCATION,
    "latitude": 37.7749,
    "longitude": -122.4194,
    "altitude": 10.0,
    "accuracy": 5.0,
    "timestamp": 1703001234.567,
    
    # Columba extensions
    "battery_percent": 67,           # Device battery level
    "sharing_expires_at": 1703004834.0,  # When sharing auto-stops (Unix timestamp)
    "speed": 1.2,                    # Walking speed in m/s
    "bearing": 45.0,                 # Direction of travel
}
```

### MessagePack Encoding

LXMF fields are msgpack-encoded. The telemetry payload structure:

```python
import msgpack

def pack_location_telemetry(location_data: dict) -> bytes:
    """
    Pack location data for LXMF FIELD_TELEMETRY.
    
    Returns msgpack-encoded bytes suitable for LXMF fields dict.
    """
    # Telemetry is a list of sensors (even if just one)
    sensors = [location_data]
    return msgpack.packb(sensors, use_bin_type=True)

def unpack_location_telemetry(data: bytes) -> list:
    """
    Unpack location telemetry from LXMF field.
    
    Returns list of sensor dictionaries.
    """
    return msgpack.unpackb(data, raw=False)
```

---

## LXMF Message Structure

### Sending Location Updates

Location updates are sent as LXMF messages with the `FIELD_TELEMETRY` field populated:

```python
import LXMF
import msgpack

def send_location_update(
    router: LXMF.LXMRouter,
    destination: RNS.Destination,
    source: RNS.Destination,
    latitude: float,
    longitude: float,
    accuracy: float,
    altitude: float = None,
    battery_percent: int = None,
    sharing_expires_at: float = None,
) -> LXMF.LXMessage:
    """
    Send a location update to a specific peer.
    """
    import time
    
    # Build location sensor data
    location_data = {
        "type": 0x01,  # SID_LOCATION
        "latitude": latitude,
        "longitude": longitude,
        "accuracy": accuracy,
        "timestamp": time.time(),
    }
    
    # Add optional fields
    if altitude is not None:
        location_data["altitude"] = altitude
    if battery_percent is not None:
        location_data["battery_percent"] = battery_percent
    if sharing_expires_at is not None:
        location_data["sharing_expires_at"] = sharing_expires_at
    
    # Pack telemetry (list of sensors)
    telemetry_bytes = msgpack.packb([location_data], use_bin_type=True)
    
    # Create LXMF message with telemetry field
    fields = {
        LXMF.FIELD_TELEMETRY: telemetry_bytes
    }
    
    # Content can be empty or contain a human-readable status
    content = ""  # Or: "Location update"
    
    message = LXMF.LXMessage(
        destination=destination,
        source=source,
        content=content.encode('utf-8'),
        title="",
        fields=fields,
        desired_method=LXMF.LXMessage.DIRECT,  # Prefer direct delivery
    )
    
    # Register callbacks
    message.register_delivery_callback(on_location_delivered)
    message.register_failed_callback(on_location_failed)
    
    # Send via router
    router.handle_outbound(message)
    
    return message
```

### Receiving Location Updates

When receiving an LXMF message, check for the telemetry field:

```python
def handle_incoming_message(message: LXMF.LXMessage):
    """
    Process incoming LXMF message, extracting location if present.
    """
    if message.fields and LXMF.FIELD_TELEMETRY in message.fields:
        telemetry_bytes = message.fields[LXMF.FIELD_TELEMETRY]
        sensors = msgpack.unpackb(telemetry_bytes, raw=False)
        
        for sensor in sensors:
            if sensor.get("type") == 0x01:  # SID_LOCATION
                handle_location_update(
                    source_hash=message.source_hash,
                    latitude=sensor.get("latitude"),
                    longitude=sensor.get("longitude"),
                    accuracy=sensor.get("accuracy"),
                    altitude=sensor.get("altitude"),
                    battery_percent=sensor.get("battery_percent"),
                    sharing_expires_at=sensor.get("sharing_expires_at"),
                    timestamp=sensor.get("timestamp"),
                )
```

---

## Columba Implementation Architecture

### Python Layer (reticulum_wrapper.py)

Add methods to the existing `ReticulumWrapper` class:

```python
# reticulum_wrapper.py additions

FIELD_TELEMETRY = 0x02
SID_LOCATION = 0x01

def send_location_telemetry(
    self,
    dest_hash: bytes,
    latitude: float,
    longitude: float,
    accuracy: float,
    altitude: float = None,
    battery_percent: int = None,
    sharing_expires_at: float = None,
    source_identity_private_key: bytes = None,
) -> dict:
    """
    Send location telemetry to a peer.
    
    Args:
        dest_hash: 16-byte destination hash
        latitude: Latitude in decimal degrees
        longitude: Longitude in decimal degrees
        accuracy: Horizontal accuracy in meters
        altitude: Altitude in meters (optional)
        battery_percent: Battery level 0-100 (optional)
        sharing_expires_at: Unix timestamp when sharing expires (optional)
        source_identity_private_key: Identity key for signing
    
    Returns:
        dict with 'success' and 'message_hash' or 'error'
    """
    if not self.initialized:
        return {"success": False, "error": "Not initialized"}
    
    try:
        import time
        import msgpack
        
        # Build location sensor
        location_data = {
            "type": SID_LOCATION,
            "latitude": latitude,
            "longitude": longitude,
            "accuracy": accuracy,
            "timestamp": time.time(),
        }
        
        if altitude is not None:
            location_data["altitude"] = altitude
        if battery_percent is not None:
            location_data["battery_percent"] = battery_percent
        if sharing_expires_at is not None:
            location_data["sharing_expires_at"] = sharing_expires_at
        
        # Pack as telemetry field
        telemetry_bytes = msgpack.packb([location_data], use_bin_type=True)
        
        fields = {
            FIELD_TELEMETRY: telemetry_bytes
        }
        
        # Resolve destination identity
        recipient_identity = RNS.Identity.recall(dest_hash)
        if not recipient_identity:
            recipient_identity = self.identities.get(dest_hash.hex())
        
        if not recipient_identity:
            RNS.Transport.request_path(dest_hash)
            return {"success": False, "error": "Identity not found, path requested"}
        
        # Create LXMF destination
        recipient_lxmf_destination = RNS.Destination(
            recipient_identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "lxmf",
            "delivery"
        )
        
        # Create and send message
        lxmf_message = LXMF.LXMessage(
            destination=recipient_lxmf_destination,
            source=self.local_lxmf_destination,
            content=b"",  # Empty content for telemetry-only message
            title="",
            fields=fields,
            desired_method=LXMF.LXMessage.DIRECT,
        )
        
        lxmf_message.register_delivery_callback(self._on_message_delivered)
        lxmf_message.register_failed_callback(self._on_message_failed)
        
        self.router.handle_outbound(lxmf_message)
        
        return {
            "success": True,
            "message_hash": lxmf_message.hash.hex(),
        }
        
    except Exception as e:
        log_error("ReticulumWrapper", "send_location_telemetry", f"Error: {e}")
        return {"success": False, "error": str(e)}


def extract_location_from_fields(self, fields_json: str) -> dict:
    """
    Extract location data from LXMF fields JSON.
    
    Args:
        fields_json: JSON string of LXMF fields (from poll_received_messages)
    
    Returns:
        dict with location data or None if no location present
    """
    try:
        import json
        import msgpack
        
        fields = json.loads(fields_json)
        
        # Field key might be string "2" in JSON
        telemetry_hex = fields.get("2") or fields.get(str(FIELD_TELEMETRY))
        
        if not telemetry_hex:
            return None
        
        # Decode hex to bytes
        telemetry_bytes = bytes.fromhex(telemetry_hex)
        
        # Unpack msgpack
        sensors = msgpack.unpackb(telemetry_bytes, raw=False)
        
        for sensor in sensors:
            if sensor.get("type") == SID_LOCATION:
                return {
                    "latitude": sensor.get("latitude"),
                    "longitude": sensor.get("longitude"),
                    "accuracy": sensor.get("accuracy"),
                    "altitude": sensor.get("altitude"),
                    "battery_percent": sensor.get("battery_percent"),
                    "sharing_expires_at": sensor.get("sharing_expires_at"),
                    "timestamp": sensor.get("timestamp"),
                }
        
        return None
        
    except Exception as e:
        log_error("ReticulumWrapper", "extract_location_from_fields", f"Error: {e}")
        return None
```

### AIDL Service Interface

Add to `IReticulumService.aidl`:

```aidl
/**
 * Send location telemetry to a peer.
 * @param destHash Destination hash bytes (16 bytes)
 * @param latitude Latitude in decimal degrees
 * @param longitude Longitude in decimal degrees
 * @param accuracy Horizontal accuracy in meters
 * @param altitude Altitude in meters (-1 if unavailable)
 * @param batteryPercent Battery level 0-100 (-1 if unavailable)
 * @param sharingExpiresAt Unix timestamp when sharing expires (0 if indefinite)
 * @param sourceIdentityPrivateKey Source identity private key bytes
 * @return JSON string with result: {"success": true, "message_hash": "..."}
 */
String sendLocationTelemetry(
    in byte[] destHash,
    double latitude,
    double longitude,
    float accuracy,
    double altitude,
    int batteryPercent,
    long sharingExpiresAt,
    in byte[] sourceIdentityPrivateKey
);
```

### Kotlin Repository Layer

```kotlin
// LocationSharingRepository.kt

interface LocationSharingRepository {
    /**
     * Start sharing location with a contact.
     */
    suspend fun startSharing(
        contact: Contact,
        duration: SharingDuration,
    ): Result<Unit>
    
    /**
     * Stop sharing location with a contact.
     */
    suspend fun stopSharing(contact: Contact): Result<Unit>
    
    /**
     * Stop sharing with all contacts.
     */
    suspend fun stopAllSharing(): Result<Unit>
    
    /**
     * Send a location update to all contacts we're sharing with.
     */
    suspend fun broadcastLocationUpdate(location: Location): Result<Unit>
    
    /**
     * Flow of incoming location updates from contacts.
     */
    val incomingLocations: Flow<ContactLocation>
    
    /**
     * Flow of currently active shares.
     */
    val activeShares: Flow<List<LocationShare>>
}

@Singleton
class LocationSharingRepositoryImpl @Inject constructor(
    private val reticulumService: ReticulumServiceConnection,
    private val locationShareDao: LocationShareDao,
    private val contactDao: ContactDao,
    private val messageProcessor: MessageProcessor,
) : LocationSharingRepository {

    private val _incomingLocations = MutableSharedFlow<ContactLocation>()
    override val incomingLocations: Flow<ContactLocation> = _incomingLocations.asSharedFlow()
    
    override val activeShares: Flow<List<LocationShare>> = 
        locationShareDao.getActiveShares()

    override suspend fun startSharing(
        contact: Contact,
        duration: SharingDuration,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val expiresAt = when (duration) {
                SharingDuration.FIFTEEN_MINUTES -> System.currentTimeMillis() + 15 * 60 * 1000
                SharingDuration.ONE_HOUR -> System.currentTimeMillis() + 60 * 60 * 1000
                SharingDuration.FOUR_HOURS -> System.currentTimeMillis() + 4 * 60 * 60 * 1000
                SharingDuration.UNTIL_MIDNIGHT -> calculateMidnightTimestamp()
                SharingDuration.INDEFINITE -> null
            }
            
            val share = LocationShare(
                contactDestinationHash = contact.destinationHash,
                startedAt = System.currentTimeMillis(),
                expiresAt = expiresAt,
                isActive = true,
            )
            
            locationShareDao.insert(share)
            
            // Start the location update service if not already running
            LocationSharingService.start(context)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun broadcastLocationUpdate(location: Location): Result<Unit> = 
        withContext(Dispatchers.IO) {
            try {
                val activeShares = locationShareDao.getActiveSharesSync()
                val batteryPercent = getBatteryPercent()
                
                for (share in activeShares) {
                    // Check expiration
                    if (share.expiresAt != null && share.expiresAt < System.currentTimeMillis()) {
                        locationShareDao.deactivate(share.contactDestinationHash)
                        continue
                    }
                    
                    val result = reticulumService.sendLocationTelemetry(
                        destHash = share.contactDestinationHash.hexToByteArray(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        altitude = if (location.hasAltitude()) location.altitude else -1.0,
                        batteryPercent = batteryPercent,
                        sharingExpiresAt = share.expiresAt ?: 0L,
                        sourceIdentityPrivateKey = getIdentityPrivateKey(),
                    )
                    
                    if (!result.success) {
                        Log.w(TAG, "Failed to send location to ${share.contactDestinationHash}: ${result.error}")
                    }
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Called by MessageProcessor when a message with telemetry field is received.
     */
    suspend fun handleIncomingTelemetry(
        sourceHash: ByteArray,
        fieldsJson: String,
        timestamp: Long,
    ) {
        try {
            val locationData = reticulumService.extractLocationFromFields(fieldsJson)
                ?: return
            
            val contactLocation = ContactLocation(
                contactDestinationHash = sourceHash.toHexString(),
                latitude = locationData.latitude,
                longitude = locationData.longitude,
                accuracy = locationData.accuracy,
                altitude = locationData.altitude,
                batteryPercent = locationData.batteryPercent,
                timestamp = locationData.timestamp ?: timestamp,
            )
            
            // Emit to flow
            _incomingLocations.emit(contactLocation)
            
            // Persist latest location
            contactLocationDao.upsert(contactLocation)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming telemetry", e)
        }
    }
}
```

---

## Location Update Service

A foreground service manages continuous location updates:

```kotlin
// LocationSharingService.kt

@AndroidEntryPoint
class LocationSharingService : Service() {

    @Inject
    lateinit var locationSharingRepository: LocationSharingRepository
    
    private val locationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    
    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        UPDATE_INTERVAL_MS
    ).apply {
        setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
        setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MS)
    }.build()
    
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                serviceScope.launch {
                    locationSharingRepository.broadcastLocationUpdate(location)
                }
            }
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLocationUpdates()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            locationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
            stopSelf()
        }
    }

    private fun createNotification(): Notification {
        val channelId = createNotificationChannel()
        
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, LocationSharingService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sharing your location")
            .setContentText("Tap to manage")
            .setSmallIcon(R.drawable.ic_location_sharing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop Sharing", stopIntent)
            .setContentIntent(createMapPendingIntent())
            .build()
    }

    override fun onDestroy() {
        locationClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LocationSharingService"
        private const val NOTIFICATION_ID = 2001
        private const val UPDATE_INTERVAL_MS = 30_000L  // 30 seconds
        private const val MIN_UPDATE_INTERVAL_MS = 15_000L
        private const val MAX_UPDATE_DELAY_MS = 60_000L
        
        const val ACTION_START = "com.lxmf.messenger.START_LOCATION_SHARING"
        const val ACTION_STOP = "com.lxmf.messenger.STOP_LOCATION_SHARING"
        
        fun start(context: Context) {
            val intent = Intent(context, LocationSharingService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, LocationSharingService::class.java))
        }
    }
}
```

---

## Database Schema

### Room Entities

```kotlin
// LocationShare.kt - Tracks who we're sharing with
@Entity(tableName = "location_shares")
data class LocationShare(
    @PrimaryKey
    val contactDestinationHash: String,
    val startedAt: Long,
    val expiresAt: Long?,  // null = indefinite
    val isActive: Boolean,
)

// ContactLocation.kt - Latest known location of contacts sharing with us
@Entity(tableName = "contact_locations")
data class ContactLocation(
    @PrimaryKey
    val contactDestinationHash: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val batteryPercent: Int?,
    val timestamp: Long,
)
```

### DAOs

```kotlin
@Dao
interface LocationShareDao {
    @Query("SELECT * FROM location_shares WHERE isActive = 1")
    fun getActiveShares(): Flow<List<LocationShare>>
    
    @Query("SELECT * FROM location_shares WHERE isActive = 1")
    suspend fun getActiveSharesSync(): List<LocationShare>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(share: LocationShare)
    
    @Query("UPDATE location_shares SET isActive = 0 WHERE contactDestinationHash = :hash")
    suspend fun deactivate(hash: String)
    
    @Query("UPDATE location_shares SET isActive = 0")
    suspend fun deactivateAll()
}

@Dao
interface ContactLocationDao {
    @Query("SELECT * FROM contact_locations")
    fun getAllLocations(): Flow<List<ContactLocation>>
    
    @Query("SELECT * FROM contact_locations WHERE contactDestinationHash = :hash")
    suspend fun getLocation(hash: String): ContactLocation?
    
    @Upsert
    suspend fun upsert(location: ContactLocation)
    
    @Query("DELETE FROM contact_locations WHERE contactDestinationHash = :hash")
    suspend fun delete(hash: String)
}
```

---

## Message Processing Integration

Update the existing `PollingManager` or message processing code to detect telemetry:

```kotlin
// In PollingManager.kt or MessageProcessor.kt

private fun handleMessageEvent(event: PyObject) {
    // ... existing message handling ...
    
    // Check for telemetry fields
    val fieldsObj = event.getDictValue("fields")
    val fieldsJson = fieldsObj?.let {
        try {
            JSONObject(it.toString()).toString()
        } catch (e: Exception) {
            null
        }
    }
    
    // If message has telemetry field (key "2"), route to location handler
    if (fieldsJson != null) {
        val fields = JSONObject(fieldsJson)
        if (fields.has("2")) {  // FIELD_TELEMETRY
            val sourceHash = event.getDictValue("source_hash")
                ?.toJava(ByteArray::class.java) as? ByteArray
            val timestamp = event.getDictValue("timestamp")?.toLong() 
                ?: System.currentTimeMillis()
            
            if (sourceHash != null) {
                serviceScope.launch {
                    locationSharingRepository.handleIncomingTelemetry(
                        sourceHash = sourceHash,
                        fieldsJson = fieldsJson,
                        timestamp = timestamp,
                    )
                }
            }
        }
    }
    
    // ... continue with regular message handling ...
}
```

---

## Wire Format Summary

### Outgoing Location Message

```
LXMF Message:
├── destination: [16-byte peer LXMF destination hash]
├── source: [16-byte our LXMF destination hash]
├── signature: [64-byte Ed25519 signature]
├── payload:
│   ├── timestamp: [double, Unix epoch seconds]
│   ├── content: "" (empty for telemetry-only)
│   ├── title: ""
│   └── fields: {
│       0x02 (FIELD_TELEMETRY): msgpack([
│           {
│               "type": 0x01,
│               "latitude": 37.7749,
│               "longitude": -122.4194,
│               "accuracy": 5.0,
│               "altitude": 10.0,
│               "timestamp": 1703001234.567,
│               "battery_percent": 67,
│               "sharing_expires_at": 1703004834.0
│           }
│       ])
│   }
```

### Approximate Message Size

| Component | Bytes |
|-----------|-------|
| Destination hash | 16 |
| Source hash | 16 |
| Signature | 64 |
| Timestamp | 8 |
| Telemetry field (typical) | ~80-120 |
| Overhead (msgpack, structure) | ~30 |
| **Total** | **~200-250 bytes** |

This fits comfortably within LXMF's opportunistic delivery threshold (~295 bytes), enabling single-packet delivery over constrained links like LoRa.

---

## Update Frequency Recommendations

| Scenario | Update Interval | Rationale |
|----------|-----------------|-----------|
| Map visible, moving | 15 seconds | Responsive tracking |
| Map visible, stationary | 60 seconds | Battery preservation |
| App backgrounded | 30-60 seconds | Balance freshness vs battery |
| Low battery (<20%) | 120 seconds | Extend battery life |
| High precision mode | 5 seconds | User-activated for active searching |

Implement adaptive update frequency based on:
- Activity recognition (walking vs stationary)
- Battery level
- Whether the map screen is currently visible
- User-selected "high precision" mode

---

## Error Handling

### Delivery Failures

```kotlin
sealed class LocationDeliveryResult {
    object Success : LocationDeliveryResult()
    data class Failed(val error: String, val retryable: Boolean) : LocationDeliveryResult()
    object PathNotFound : LocationDeliveryResult()
    object RecipientOffline : LocationDeliveryResult()
}
```

**Retry strategy:**
- On `PathNotFound`: Request path, retry after 5 seconds
- On `RecipientOffline`: Queue for propagated delivery if propagation node available
- On network error: Exponential backoff (5s, 10s, 20s, max 60s)

### Stale Location Handling

Consider a location stale if:
- `timestamp` is older than 5 minutes AND no new updates received
- Show "Last seen X minutes ago" in UI
- Gray out marker or show warning indicator

---

## Privacy Considerations

1. **End-to-end encryption**: All LXMF messages are encrypted; telemetry is only readable by the intended recipient
2. **No server storage**: Location data travels peer-to-peer; propagation nodes only store encrypted blobs
3. **Explicit consent**: Location sharing requires deliberate user action per-contact
4. **Time limits**: All sharing has explicit duration with auto-expiration
5. **Battery indicator**: Helps friends know if your phone might die, not for surveillance
6. **Local-only history**: Location history stored only on receiving device, not broadcast

---

## Testing Checklist

### Unit Tests
- [ ] `pack_location_telemetry` produces valid msgpack
- [ ] `unpack_location_telemetry` handles all sensor fields
- [ ] Expiration calculation for all `SharingDuration` values
- [ ] Field extraction from various JSON formats

### Integration Tests
- [ ] Round-trip: send location → receive → extract → verify coordinates match
- [ ] Delivery callback fires on successful send
- [ ] Failed callback fires when peer unreachable
- [ ] Service starts/stops correctly with share count changes

### Interoperability Tests
- [ ] Columba → Sideband: Sideband displays location on map
- [ ] Sideband → Columba: Columba parses Sideband telemetry correctly
- [ ] Field format matches Reticulum-Telemetry-Hub expectations

### Manual Testing
- [ ] Send location over BLE mesh (no internet)
- [ ] Send location over LoRa via RNode
- [ ] Verify battery optimization doesn't kill service
- [ ] Test expiration notification appears at correct time
