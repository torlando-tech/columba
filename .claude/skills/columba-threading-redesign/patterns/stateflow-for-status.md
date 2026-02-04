# Pattern: StateFlow for Status Distribution

## Problem

Need to distribute current status across processes and layers with instant updates.

## Solution

Use StateFlow - always has current value, thread-safe, reactive.

---

## Implementation

### Service Side

```kotlin
class ReticulumService : Service() {
    // StateFlow always has a value
    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()
    
    private fun updateStatus(newStatus: NetworkStatus) {
        _networkStatus.value = newStatus  // Instant propagation
    }
}
```

### Client Side

```kotlin
class ServiceReticulumProtocol : ReticulumProtocol {
    override val networkStatus: StateFlow<NetworkStatus>
        get() = service.networkStatus
    
    // Wait for specific status
    suspend fun waitForReady(): Boolean {
        return withTimeoutOrNull(10_000) {
            networkStatus.first { it is NetworkStatus.READY }
            true
        } ?: false
    }
}
```

### ViewModel

```kotlin
class StatusViewModel : ViewModel() {
    val statusText: StateFlow<String> = protocol.networkStatus
        .map { status ->
            when (status) {
                NetworkStatus.READY -> "Connected"
                NetworkStatus.INITIALIZING -> "Connecting..."
                NetworkStatus.SHUTDOWN -> "Disconnected"
                is NetworkStatus.ERROR -> "Error: ${status.message}"
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "Unknown"
        )
}
```

### Compose UI

```kotlin
@Composable
fun StatusIndicator(viewModel: StatusViewModel = hiltViewModel()) {
    val status by viewModel.statusText.collectAsState()
    
    Text(text = status)  // Updates automatically
}
```

## When to Use

| Use StateFlow | Use SharedFlow |
|--------------|---------------|
| Single current value (status, config) | Events that happen (messages, clicks) |
| Need to know latest immediately | Don't need history |
| State that changes | Actions/events |

---

*StateFlow = Current state always available*
