# Link Speed Probe Design for Adaptive Image Compression

## Overview

Instead of guessing bandwidth based on local interface types, establish a Link to the actual destination (or propagation node) and measure the real end-to-end transfer rate. This accounts for all hops in the path.

## Key Insight

Reticulum Links provide:
- `link.rtt` - Round-trip time across entire path
- `link.get_establishment_rate()` - Bits/sec measured during handshake
- `link.get_expected_rate()` - Bits/sec measured from actual transfers

These metrics reflect the **entire path**, including any slow intermediate hops.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Kotlin Layer                            │
├─────────────────────────────────────────────────────────────────┤
│  LinkSpeedProbe                                                 │
│  ├── probeDestination(destHash, deliveryMethod) -> SpeedEstimate│
│  ├── getTargetHash(destHash, deliveryMethod) -> targetHash      │
│  │   └── If propagated: use currentRelay.hash                   │
│  │   └── If direct: use destHash                                │
│  └── cancelProbe()                                              │
├─────────────────────────────────────────────────────────────────┤
│  MessagingViewModel                                             │
│  ├── startLinkProbe() - called when attachment dialog opens     │
│  ├── linkSpeedEstimate: StateFlow<SpeedEstimate?>               │
│  └── processImageWithCompression() - uses estimate for recommendation│
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Python Layer                            │
├─────────────────────────────────────────────────────────────────┤
│  reticulum_wrapper.py                                           │
│  ├── probe_link_speed(dest_hash) -> dict                        │
│  │   ├── Check for existing active link in router.direct_links  │
│  │   ├── If exists: return establishment_rate, expected_rate    │
│  │   ├── If not: establish new link, measure, return rates      │
│  │   └── Returns: {                                             │
│  │         "status": "success" | "no_path" | "timeout",         │
│  │         "establishment_rate_bps": int | null,                │
│  │         "expected_rate_bps": int | null,                     │
│  │         "rtt_seconds": float | null,                         │
│  │         "hops": int | null                                   │
│  │       }                                                      │
│  └── cancel_link_probe()                                        │
└─────────────────────────────────────────────────────────────────┘
```

## Data Flow

### When User Opens Attachment Dialog

```
1. User taps attachment button in MessagingScreen
   │
   ▼
2. MessagingViewModel.startLinkProbe()
   │
   ├── Get delivery method from SettingsRepository
   │   └── "direct" or "propagated"
   │
   ├── Get target hash:
   │   ├── If "direct": use conversation's peer hash
   │   └── If "propagated": use PropagationNodeManager.currentRelay.hash
   │
   └── Call Python: probe_link_speed(target_hash)
       │
       ▼
3. Python establishes Link (if needed) and measures speed
   │
   ▼
4. Result flows back to Kotlin
   │
   ▼
5. _linkSpeedEstimate.value = SpeedEstimate(...)
```

### When User Selects Image

```
1. User picks image from gallery
   │
   ▼
2. MessagingViewModel.processImageWithCompression()
   │
   ├── Check linkSpeedEstimate.value
   │   │
   │   ├── If available: use measured rate to recommend preset
   │   │   └── Calculate transfer times for each preset
   │   │
   │   └── If null (probe still running or failed):
   │       └── Fall back to heuristics (hop count, announce interface)
   │
   └── Show quality selection dialog with recommendation
```

## Python Implementation

```python
# In reticulum_wrapper.py

def probe_link_speed(self, dest_hash_hex: str) -> dict:
    """
    Probe the link speed to a destination by establishing a Link
    and measuring the establishment rate.
    
    Args:
        dest_hash_hex: Destination hash as hex string
        
    Returns:
        dict with speed metrics or error status
    """
    try:
        dest_hash = bytes.fromhex(dest_hash_hex)
        
        # Check if we already have an active link
        if dest_hash in self.router.direct_links:
            link = self.router.direct_links[dest_hash]
            if link.status == RNS.Link.ACTIVE:
                return {
                    "status": "success",
                    "establishment_rate_bps": link.get_establishment_rate(),
                    "expected_rate_bps": link.get_expected_rate(),
                    "rtt_seconds": link.rtt,
                    "hops": RNS.Transport.hops_to(dest_hash),
                    "link_reused": True
                }
        
        # Check if path exists
        if not RNS.Transport.has_path(dest_hash):
            # Request path and wait briefly
            RNS.Transport.request_path(dest_hash)
            # Could wait here or return immediately
            return {
                "status": "no_path",
                "establishment_rate_bps": None,
                "expected_rate_bps": None,
                "rtt_seconds": None,
                "hops": None
            }
        
        # Get the identity for this destination
        identity = RNS.Identity.recall(dest_hash)
        if identity is None:
            return {
                "status": "no_identity",
                "establishment_rate_bps": None,
                "expected_rate_bps": None,
                "rtt_seconds": None,
                "hops": None
            }
        
        # Create destination and establish link
        destination = RNS.Destination(
            identity,
            RNS.Destination.OUT,
            RNS.Destination.SINGLE,
            "lxmf",
            "delivery"
        )
        
        # Track establishment
        probe_result = {"status": "pending"}
        probe_complete = threading.Event()
        
        def link_established(link):
            probe_result["status"] = "success"
            probe_result["establishment_rate_bps"] = link.get_establishment_rate()
            probe_result["expected_rate_bps"] = link.get_expected_rate()
            probe_result["rtt_seconds"] = link.rtt
            probe_result["hops"] = RNS.Transport.hops_to(dest_hash)
            probe_result["link_reused"] = False
            probe_complete.set()
        
        def link_closed(link):
            if probe_result["status"] == "pending":
                probe_result["status"] = "failed"
            probe_complete.set()
        
        link = RNS.Link(destination)
        link.set_link_established_callback(link_established)
        link.set_link_closed_callback(link_closed)
        
        # Wait for establishment (with timeout)
        timeout_seconds = 30  # Configurable
        if probe_complete.wait(timeout=timeout_seconds):
            return probe_result
        else:
            link.teardown()
            return {
                "status": "timeout",
                "establishment_rate_bps": None,
                "expected_rate_bps": None,
                "rtt_seconds": None,
                "hops": None
            }
            
    except Exception as e:
        log_error("ReticulumWrapper", "probe_link_speed", f"Error: {e}")
        return {
            "status": "error",
            "error": str(e),
            "establishment_rate_bps": None,
            "expected_rate_bps": None,
            "rtt_seconds": None,
            "hops": None
        }
```

## Kotlin Implementation

### SpeedEstimate Data Class

```kotlin
// In data/model/SpeedEstimate.kt

data class SpeedEstimate(
    val establishmentRateBps: Long?,      // Bits per second from link establishment
    val expectedRateBps: Long?,            // Bits per second from actual transfers
    val rttSeconds: Double?,               // Round-trip time
    val hops: Int?,                         // Number of hops
    val linkReused: Boolean,               // True if existing link was used
    val targetType: TargetType,            // DIRECT or PROPAGATION_NODE
) {
    enum class TargetType { DIRECT, PROPAGATION_NODE }
    
    /**
     * Get the best available rate estimate in bits per second.
     * Prefers expected_rate (from actual transfers) over establishment_rate.
     */
    val bestRateBps: Long?
        get() = expectedRateBps ?: establishmentRateBps
    
    /**
     * Calculate estimated transfer time for a given size.
     */
    fun estimateTransferTime(sizeBytes: Long): Duration? {
        val rateBps = bestRateBps ?: return null
        if (rateBps <= 0) return null
        val sizeBits = sizeBytes * 8
        val seconds = sizeBits.toDouble() / rateBps
        return seconds.seconds
    }
    
    /**
     * Recommend a compression preset based on measured speed.
     */
    fun recommendPreset(): ImageCompressionPreset {
        val rateBps = bestRateBps ?: return ImageCompressionPreset.MEDIUM
        
        return when {
            rateBps < 5_000 -> ImageCompressionPreset.LOW        // < 5 kbps (LoRa)
            rateBps < 50_000 -> ImageCompressionPreset.MEDIUM    // < 50 kbps
            rateBps < 500_000 -> ImageCompressionPreset.HIGH     // < 500 kbps
            else -> ImageCompressionPreset.ORIGINAL               // Fast connection
        }
    }
}
```

### LinkSpeedProbe Service

```kotlin
// In service/LinkSpeedProbe.kt

@Singleton
class LinkSpeedProbe @Inject constructor(
    private val reticulumProtocol: ReticulumProtocol,
    private val settingsRepository: SettingsRepository,
    private val propagationNodeManager: PropagationNodeManager,
) {
    private val _probeState = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probeState: StateFlow<ProbeState> = _probeState.asStateFlow()
    
    sealed class ProbeState {
        object Idle : ProbeState()
        data class Probing(val targetHash: String) : ProbeState()
        data class Complete(val estimate: SpeedEstimate) : ProbeState()
        data class Failed(val reason: String) : ProbeState()
    }
    
    /**
     * Start probing link speed to a destination.
     * Uses propagation node if delivery method is "propagated".
     */
    suspend fun probe(conversationPeerHash: String): SpeedEstimate? {
        val deliveryMethod = settingsRepository.getDefaultDeliveryMethod()
        
        val targetHash = when (deliveryMethod) {
            "propagated" -> {
                val relay = propagationNodeManager.currentRelay.value
                if (relay == null) {
                    _probeState.value = ProbeState.Failed("No propagation node selected")
                    return null
                }
                relay.destinationHash
            }
            else -> conversationPeerHash
        }
        
        val targetType = if (deliveryMethod == "propagated") {
            SpeedEstimate.TargetType.PROPAGATION_NODE
        } else {
            SpeedEstimate.TargetType.DIRECT
        }
        
        _probeState.value = ProbeState.Probing(targetHash)
        
        return try {
            val result = withContext(Dispatchers.IO) {
                reticulumProtocol.probeLinkSpeed(targetHash)
            }
            
            if (result != null && result.status == "success") {
                val estimate = SpeedEstimate(
                    establishmentRateBps = result.establishmentRateBps,
                    expectedRateBps = result.expectedRateBps,
                    rttSeconds = result.rttSeconds,
                    hops = result.hops,
                    linkReused = result.linkReused,
                    targetType = targetType,
                )
                _probeState.value = ProbeState.Complete(estimate)
                estimate
            } else {
                _probeState.value = ProbeState.Failed(result?.status ?: "unknown")
                null
            }
        } catch (e: Exception) {
            _probeState.value = ProbeState.Failed(e.message ?: "Unknown error")
            null
        }
    }
    
    fun reset() {
        _probeState.value = ProbeState.Idle
    }
}
```

### MessagingViewModel Integration

```kotlin
// In MessagingViewModel.kt - additions

@Inject
private val linkSpeedProbe: LinkSpeedProbe

private val _speedEstimate = MutableStateFlow<SpeedEstimate?>(null)
val speedEstimate: StateFlow<SpeedEstimate?> = _speedEstimate.asStateFlow()

/**
 * Start probing link speed when user opens attachment dialog.
 * Should be called early to give the probe time to complete.
 */
fun startLinkProbe() {
    viewModelScope.launch {
        val peerHash = _state.value.peerHash ?: return@launch
        _speedEstimate.value = linkSpeedProbe.probe(peerHash)
    }
}

/**
 * Process image with compression, using measured speed for recommendation.
 */
fun processImageWithCompression(context: Context, uri: Uri) {
    viewModelScope.launch {
        _isProcessingImage.value = true
        try {
            // Get speed estimate (may be null if probe not complete)
            val estimate = _speedEstimate.value
            
            // Determine recommended preset
            val recommendedPreset = estimate?.recommendPreset() 
                ?: getHeuristicPreset()  // Fallback
            
            // Compress the image
            val result = withContext(Dispatchers.IO) {
                ImageUtils.compressImageWithPreset(context, uri, recommendedPreset)
            } ?: return@launch
            
            // Calculate transfer times for each preset
            val transferTimes = ImageCompressionPreset.entries
                .filter { it != ImageCompressionPreset.AUTO }
                .associate { preset ->
                    val size = estimateCompressedSize(uri, preset)
                    preset to estimate?.estimateTransferTime(size)
                }
            
            // Show quality selection dialog
            _compressionChoice.value = CompressionChoice(
                compressedResult = result,
                recommendedPreset = recommendedPreset,
                speedEstimate = estimate,
                transferTimeEstimates = transferTimes,
            )
            
        } finally {
            _isProcessingImage.value = false
        }
    }
}

private fun getHeuristicPreset(): ImageCompressionPreset {
    // Fallback when probe not available
    // Use hop count and announce interface as heuristics
    // ...
}
```

## UI Changes

### Quality Selection Dialog (Sideband-style)

```kotlin
@Composable
fun ImageQualitySelectionDialog(
    choice: CompressionChoice,
    onSelect: (ImageCompressionPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Image Quality") },
        text = {
            Column {
                // Show path info if available
                choice.speedEstimate?.let { estimate ->
                    Text(
                        text = buildString {
                            append("Path: ${estimate.hops ?: "?"} hops")
                            estimate.bestRateBps?.let { rate ->
                                append(" • ${formatBitrate(rate)}")
                            }
                            if (estimate.targetType == SpeedEstimate.TargetType.PROPAGATION_NODE) {
                                append(" (via relay)")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Quality options
                ImageCompressionPreset.entries
                    .filter { it != ImageCompressionPreset.AUTO }
                    .forEach { preset ->
                        val isRecommended = preset == choice.recommendedPreset
                        val transferTime = choice.transferTimeEstimates[preset]
                        
                        QualityOption(
                            preset = preset,
                            isRecommended = isRecommended,
                            transferTime = transferTime,
                            onClick = { onSelect(preset) }
                        )
                    }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun QualityOption(
    preset: ImageCompressionPreset,
    isRecommended: Boolean,
    transferTime: Duration?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (isRecommended) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Recommended",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            
            transferTime?.let { time ->
                Text(
                    text = "~${formatDuration(time)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

## Fallback Heuristics

When link probe is not available (timeout, no path, etc.), use these heuristics:

```kotlin
private fun getHeuristicPreset(): ImageCompressionPreset {
    // 1. Check hop count
    val hops = reticulumProtocol.getHopsTo(peerHash)
    
    // 2. Check which interface we received their announce on
    val announceInterface = announceRepository.getAnnounce(peerHash)?.receivingInterfaceType
    
    return when {
        // Direct path via slow interface
        hops == 1 && announceInterface in listOf("RNode", "AndroidBLE") -> 
            ImageCompressionPreset.LOW
        
        // Direct path via fast interface
        hops == 1 && announceInterface in listOf("TCPClient", "AutoInterface") ->
            ImageCompressionPreset.HIGH
        
        // Multi-hop - assume potential slow link somewhere
        hops != null && hops > 1 ->
            ImageCompressionPreset.MEDIUM
        
        // Unknown - be conservative
        else -> ImageCompressionPreset.MEDIUM
    }
}
```

## Implementation Order

1. **Python layer**: Add `probe_link_speed()` to `reticulum_wrapper.py`
2. **AIDL/Bridge**: Add method to `ReticulumProtocol` interface
3. **Kotlin service**: Create `LinkSpeedProbe` class
4. **Data models**: Add `SpeedEstimate` data class
5. **ViewModel**: Integrate probing into `MessagingViewModel`
6. **UI**: Create quality selection dialog
7. **Testing**: Unit tests for probe logic, UI tests for dialog

## Open Questions

1. **Link lifecycle**: Should we keep the probe link open for the actual transfer, or let LXMF establish its own?
   - Recommendation: Let LXMF handle it - the probe is just for measurement

2. **Probe timeout**: How long to wait before falling back to heuristics?
   - Recommendation: 10 seconds for fast networks, configurable

3. **Caching**: Should we cache speed estimates per destination?
   - Recommendation: Yes, with 5-minute TTL

4. **Background probing**: Should we probe when conversation opens, not just attachment dialog?
   - Recommendation: Yes, this gives more time for slow links
