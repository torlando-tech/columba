# Columba Location Sharing Feature - UX Specification

## Overview

This document specifies the UX design for adding real-time peer-to-peer location sharing to Columba. The feature enables users to share their location with specific LXMF contacts, with configurable expiration times, and view shared locations on an interactive map.

### Primary Use Case

Friends and family at crowded events (concerts, festivals, conventions) where cellular infrastructure is overloaded. Users need to quickly locate separated companions—ideally within 2 seconds of opening the app.

### Design Principles

1. **Speed of access**: Map view should be immediately visible from app launch
2. **Privacy by default**: Location sharing is always opt-in, per-contact, time-limited
3. **Clarity of state**: Users must always know who can see them and who they can see
4. **Offline-first**: Must work over mesh networking without internet connectivity
5. **Battery conscious**: Optimize for all-day event usage

---

## Navigation Architecture Changes

### Current Navigation (4 destinations)

```
💬 Chats  |  📡 Announces  |  👥 Contacts  |  ⚙️ Settings
```

### New Navigation (4 destinations)

```
💬 Chats  |  🗺️ Map  |  👥 Contacts  |  ⚙️ Settings
```

### Change Summary

| Before | After | Rationale |
|--------|-------|-----------|
| Announces (top-level) | Merged into Contacts | Frees nav slot for Map; Announces and Contacts are conceptually related (peer discovery → saved peers) |
| No Map | Map (top-level) | Core feature for festival use case; needs instant access |

### Screen Enum Updates

Update `Screen.kt` to reflect new navigation:

```kotlin
sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    object Chats : Screen("chats", "Chats", Icons.Default.Chat)
    object Map : Screen("map", "Map", Icons.Default.Map)
    object Contacts : Screen("contacts", "Contacts", Icons.Default.Contacts)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    
    // Announces becomes a nested route under Contacts, not a top-level destination
    object Announces : Screen("contacts/network", "Network", Icons.Default.Sensors)
}
```

---

## Contacts Screen Redesign

The Contacts screen now contains two segments: saved contacts and network announces.

### Layout Structure

```
┌─────────────────────────────────────┐
│  Contacts                        +  │  ← TopAppBar with add contact action
├─────────────────────────────────────┤
│                                     │
│  ┌──────────────┬─────────────────┐ │
│  │  My Contacts │    Network      │ │  ← SegmentedButton (M3)
│  └──────────────┴─────────────────┘ │
│                                     │
│  ┌─────────────────────────────────┐│
│  │ 🔍 Search contacts              ││  ← SearchBar (collapsible)
│  └─────────────────────────────────┘│
│                                     │
│  ─────────────────────────────────  │
│                                     │
│  (Content based on selected segment)│
│                                     │
│                                     │
│                                     │
│                                     │
├─────────────────────────────────────┤
│  💬        🗺️        👥       ⚙️    │  ← NavigationBar
│  Chats     Map    Contacts  Settings│
└─────────────────────────────────────┘
```

### "My Contacts" Segment (Default)

Displays saved contacts with location sharing status indicators.

```
┌─────────────────────────────────────┐
│  ┌───┐                              │
│  │ A │  Alice Chen                  │
│  │   │  📍 Sharing location         │  ← Location indicator if sharing
│  └───┘                              │
│  ─────────────────────────────────  │
│  ┌───┐                              │
│  │ B │  Bob Smith                   │
│  │   │  Last seen 2 hours ago       │
│  └───┘                              │
│  ─────────────────────────────────  │
│  ┌───┐                              │
│  │ C │  Carol Davis             📍  │  ← Small icon if they're sharing with you
│  │   │  Can see your location       │
│  └───┘                              │
└─────────────────────────────────────┘
```

#### Contact List Item Component

```kotlin
@Composable
fun ContactListItem(
    contact: Contact,
    locationSharingState: LocationSharingState, // NONE, SHARING_WITH_THEM, THEY_SHARE_WITH_ME, MUTUAL
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

**Location sharing state indicators:**
- `NONE`: No indicator
- `SHARING_WITH_THEM`: Subtitle text "Sharing location · Xh left"
- `THEY_SHARE_WITH_ME`: Trailing `Icons.Default.LocationOn` icon (small, tinted `colorPrimary`)
- `MUTUAL`: Both subtitle AND trailing icon

### "Network" Segment

Contains the existing AnnounceStreamScreen content with minimal changes.

**Behavioral changes:**
- Filter chips remain at top (Node types, Audio announces toggle)
- "Add to Contacts" action on each announce item (existing functionality)
- FAB for manual announce if applicable

---

## Map Screen

### Primary Map View

The Map screen shows a full-screen interactive map with contact avatars as markers.

```
┌─────────────────────────────────────┐
│ ≡  Map                          👤  │  ← TopAppBar (transparent/overlay)
├─────────────────────────────────────┤
│ ┌─────────────────────────────────┐ │
│ │ Sharing with 2 people      ✕   │ │  ← Status chip (only if actively sharing)
│ └─────────────────────────────────┘ │
│                                     │
│              [MAP VIEW]             │
│                                     │
│         📍                          │
│        Alice                        │  ← Contact avatar marker with label
│        200m                         │
│                                     │
│    🔵 ← You                         │  ← User's location (blue dot)
│                                     │
│                   📍                │
│                  Bob                │
│                  450m               │
│                                     │
│                                     │
│                            ┌───┐    │
│                            │ ◎ │    │  ← My Location FAB (small)
│                            └───┘    │
│                    ┌─────────────┐  │
│                    │ 📍 Share    │  │  ← Extended FAB
│                    │   Location  │  │
│                    └─────────────┘  │
├─────────────────────────────────────┤
│  💬        🗺️        👥       ⚙️    │
│  Chats     Map    Contacts  Settings│
└─────────────────────────────────────┘
```

### Map Screen Components

#### TopAppBar

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapTopAppBar(
    onMenuClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = { Text("Map") },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
        },
        actions = {
            IconButton(onClick = onProfileClick) {
                Icon(Icons.Default.AccountCircle, contentDescription = "Profile")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ),
        modifier = modifier,
    )
}
```

#### Sharing Status Chip

Appears at top of map when user is actively sharing location.

```kotlin
@Composable
fun SharingStatusChip(
    sharingWithCount: Int,
    onDismiss: () -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onManageClick,
        label = { 
            Text("Sharing with $sharingWithCount ${if (sharingWithCount == 1) "person" else "people"}") 
        },
        leadingIcon = {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(18.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        },
        modifier = modifier,
    )
}
```

#### Contact Map Marker

Custom composable for rendering contact avatars on the map.

```kotlin
@Composable
fun ContactMapMarker(
    contact: Contact,
    distanceMeters: Int?,
    lastUpdated: Instant,
    batteryPercent: Int?, // Optional: show battery level
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        // Avatar with location pin shape
        Box {
            // Pin background shape
            Surface(
                shape = MarkerShape, // Custom pin shape
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
            ) {
                // Contact avatar or initials
                ContactAvatar(
                    contact = contact,
                    size = 40.dp,
                    modifier = Modifier.padding(4.dp),
                )
            }
            
            // Battery warning indicator (if low)
            if (batteryPercent != null && batteryPercent < 20) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Default.BatteryAlert,
                        contentDescription = "Low battery",
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        
        // Name label
        Text(
            text = contact.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        
        // Distance label
        if (distanceMeters != null) {
            Text(
                text = formatDistance(distanceMeters),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

#### Floating Action Buttons

```kotlin
@Composable
fun MapFloatingButtons(
    onShareLocationClick: () -> Unit,
    onMyLocationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.padding(16.dp),
    ) {
        // My Location button (small FAB)
        SmallFloatingActionButton(
            onClick = onMyLocationClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "My location")
        }
        
        // Share Location button (extended FAB)
        ExtendedFloatingActionButton(
            onClick = onShareLocationClick,
            icon = { Icon(Icons.Default.ShareLocation, contentDescription = null) },
            text = { Text("Share Location") },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
```

### Empty State (No Shared Locations)

When no contacts are sharing their location with the user:

```
┌─────────────────────────────────────┐
│                                     │
│              [MAP VIEW]             │
│                                     │
│    🔵 ← You                         │
│                                     │
│    ┌─────────────────────────────┐  │
│    │                             │  │
│    │    👥 No shared locations   │  │  ← Empty state card
│    │                             │  │
│    │  Your contacts' locations   │  │
│    │  will appear here when      │  │
│    │  they share with you.       │  │
│    │                             │  │
│    │  [  Invite to Share  ]      │  │  ← Optional action
│    │                             │  │
│    └─────────────────────────────┘  │
│                                     │
│                    ┌─────────────┐  │
│                    │ 📍 Share    │  │
│                    │   Location  │  │
│                    └─────────────┘  │
├─────────────────────────────────────┤
```

---

## Contact Detail Bottom Sheet

Tapping a contact's marker on the map opens a bottom sheet with details and actions.

### Layout

```
┌─────────────────────────────────────┐
│              ━━━━━━                 │  ← Drag handle
├─────────────────────────────────────┤
│                                     │
│   ┌────┐                            │
│   │    │   Alice Chen               │  ← Avatar + Name
│   │ AC │   🔋 67%  •  Updated 30s   │  ← Battery + Recency
│   └────┘                            │
│                                     │
│   📍 200m northwest                 │  ← Distance + Direction
│   🚶 4 min walk                     │  ← Travel time estimate
│                                     │
│   ┌─────────────┐  ┌─────────────┐  │
│   │ 🧭 Directions│  │ 💬 Message  │  │  ← Primary actions
│   └─────────────┘  └─────────────┘  │
│                                     │
│   ─────────────────────────────────  │  ← Divider
│                                     │
│   Your sharing with Alice           │
│   ┌────────────────────────────┐    │
│   │ Share my location          🔘│  │  ← Switch toggle
│   │ Expires in 47 minutes      │    │  ← Expiration info (if on)
│   └────────────────────────────┘    │
│                                     │
│   ─────────────────────────────────  │
│                                     │
│   🔔 Notify when nearby             │
│   ┌────────────────────────────┐    │
│   │ Alert within 50 meters     🔘│  │  ← Proximity alert toggle
│   └────────────────────────────┘    │
│                                     │
└─────────────────────────────────────┘
```

### Implementation

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactLocationBottomSheet(
    contact: Contact,
    locationState: ContactLocationState,
    sharingState: SharingWithContactState,
    onDismiss: () -> Unit,
    onGetDirections: () -> Unit,
    onSendMessage: () -> Unit,
    onToggleSharing: (Boolean) -> Unit,
    onChangeDuration: () -> Unit,
    onToggleProximityAlert: (Boolean) -> Unit,
    onChangeProximityDistance: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: Avatar, name, status
            ContactHeader(
                contact = contact,
                batteryPercent = locationState.batteryPercent,
                lastUpdated = locationState.lastUpdated,
            )
            
            // Location info
            LocationInfo(
                distanceMeters = locationState.distanceMeters,
                bearing = locationState.bearing,
                walkTimeMinutes = locationState.estimatedWalkMinutes,
            )
            
            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onGetDirections,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Directions, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Directions")
                }
                
                Button(
                    onClick = onSendMessage,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Message, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Message")
                }
            }
            
            HorizontalDivider()
            
            // Sharing toggle
            SharingToggleSection(
                isSharing = sharingState.isSharing,
                expiresAt = sharingState.expiresAt,
                onToggle = onToggleSharing,
                onChangeDuration = onChangeDuration,
            )
            
            HorizontalDivider()
            
            // Proximity alert toggle
            ProximityAlertSection(
                isEnabled = sharingState.proximityAlertEnabled,
                distanceMeters = sharingState.proximityAlertDistance,
                onToggle = onToggleProximityAlert,
                onChangeDistance = onChangeProximityDistance,
            )
        }
    }
}
```

---

## Share Location Flow

### Entry Point 1: Map Screen FAB

Tapping "Share Location" opens a bottom sheet for selecting contacts and duration.

```
┌─────────────────────────────────────┐
│              ━━━━━━                 │
├─────────────────────────────────────┤
│                                     │
│   Share your location               │  ← Title
│                                     │
│   With:                             │
│   ┌─────────────────────────────┐   │
│   │ 🔍 Search contacts          │   │  ← Search field
│   └─────────────────────────────┘   │
│                                     │
│   ┌──────────┐ ┌──────────┐         │
│   │ ✓ Alice ✕│ │ ✓ Bob   ✕│         │  ← Selected contacts (InputChips)
│   └──────────┘ └──────────┘         │
│                                     │
│   Recent contacts:                  │
│   ○ Carol Davis                     │  ← Selectable contact rows
│   ○ David Lee                       │
│   ○ Emma Wilson                     │
│                                     │
│   ─────────────────────────────────  │
│                                     │
│   Duration:                         │
│   ┌───────┐┌───────┐┌────────┐      │
│   │15 min ││1 hour ││4 hours │      │  ← FilterChips (single select)
│   └───────┘└───────┘└────────┘      │
│   ┌─────────────┐┌───────────────┐  │
│   │Until midnite││ Until I stop  │  │
│   └─────────────┘└───────────────┘  │
│                         ↑ selected  │
│                                     │
│   ┌─────────────────────────────┐   │
│   │       Start Sharing         │   │  ← Primary button (disabled until selection)
│   └─────────────────────────────┘   │
│                                     │
└─────────────────────────────────────┘
```

### Implementation

```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareLocationBottomSheet(
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onStartSharing: (selectedContacts: List<Contact>, duration: SharingDuration) -> Unit,
) {
    var selectedContacts by remember { mutableStateOf<Set<Contact>>(emptySet()) }
    var selectedDuration by remember { mutableStateOf(SharingDuration.ONE_HOUR) }
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) contacts
        else contacts.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
    }
    
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Share your location",
                style = MaterialTheme.typography.titleLarge,
            )
            
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search contacts") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            
            // Selected contacts as chips
            if (selectedContacts.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    selectedContacts.forEach { contact ->
                        InputChip(
                            selected = true,
                            onClick = { selectedContacts = selectedContacts - contact },
                            label = { Text(contact.displayName) },
                            avatar = {
                                ContactAvatar(contact = contact, size = 24.dp)
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
            
            // Contact list (scrollable, limited height)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filteredContacts) { contact ->
                    ContactSelectionRow(
                        contact = contact,
                        isSelected = contact in selectedContacts,
                        onToggle = {
                            selectedContacts = if (contact in selectedContacts) {
                                selectedContacts - contact
                            } else {
                                selectedContacts + contact
                            }
                        },
                    )
                }
            }
            
            HorizontalDivider()
            
            // Duration selection
            Text(
                text = "Duration",
                style = MaterialTheme.typography.titleMedium,
            )
            
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SharingDuration.entries.forEach { duration ->
                    FilterChip(
                        selected = selectedDuration == duration,
                        onClick = { selectedDuration = duration },
                        label = { Text(duration.displayText) },
                    )
                }
            }
            
            // Start sharing button
            Button(
                onClick = { 
                    onStartSharing(selectedContacts.toList(), selectedDuration) 
                },
                enabled = selectedContacts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start Sharing")
            }
        }
    }
}

enum class SharingDuration(val displayText: String, val durationMillis: Long?) {
    FIFTEEN_MINUTES("15 min", 15 * 60 * 1000L),
    ONE_HOUR("1 hour", 60 * 60 * 1000L),
    FOUR_HOURS("4 hours", 4 * 60 * 60 * 1000L),
    UNTIL_MIDNIGHT("Until midnight", null), // Special handling: calculate to midnight
    INDEFINITE("Until I stop", null),
}
```

### Entry Point 2: Conversation Screen

Add a location action to the conversation header and message input area.

#### Conversation TopAppBar Update

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationTopAppBar(
    contact: Contact,
    locationSharingState: LocationSharingState,
    onBackClick: () -> Unit,
    onContactClick: () -> Unit,
    onLocationClick: () -> Unit, // NEW
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
        },
        title = {
            Column(
                modifier = Modifier.clickable(onClick = onContactClick),
            ) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                // Show sharing status as subtitle
                when (locationSharingState) {
                    LocationSharingState.MUTUAL -> {
                        Text(
                            text = "Sharing location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    LocationSharingState.SHARING_WITH_THEM -> {
                        Text(
                            text = "Sharing your location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LocationSharingState.THEY_SHARE_WITH_ME -> {
                        Text(
                            text = "Can see their location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {}
                }
            }
        },
        actions = {
            // Location button (shows filled icon if any sharing active)
            IconButton(onClick = onLocationClick) {
                Icon(
                    imageVector = if (locationSharingState != LocationSharingState.NONE) {
                        Icons.Default.LocationOn
                    } else {
                        Icons.Outlined.LocationOn
                    },
                    contentDescription = "Location sharing",
                    tint = if (locationSharingState != LocationSharingState.NONE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
    )
}
```

#### Message Input Location Action

Add a location button to the message input row:

```kotlin
@Composable
fun MessageInputBar(
    // ... existing parameters ...
    onLocationClick: () -> Unit, // NEW
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Attachment button (existing)
        IconButton(onClick = onAttachmentClick) {
            Icon(Icons.Default.Add, contentDescription = "Attach")
        }
        
        // Location button (NEW)
        IconButton(onClick = onLocationClick) {
            Icon(Icons.Outlined.LocationOn, contentDescription = "Share location")
        }
        
        // ... rest of input bar ...
    }
}
```

---

## Location Sharing State Management

### Data Models

```kotlin
// Sharing direction enum
enum class LocationSharingState {
    NONE,
    SHARING_WITH_THEM,    // I share with them, they don't share with me
    THEY_SHARE_WITH_ME,   // They share with me, I don't share with them
    MUTUAL,               // Both directions active
}

// Active share record (stored locally)
@Entity(tableName = "location_shares")
data class LocationShare(
    @PrimaryKey
    val contactDestinationHash: String,
    val startedAt: Long,
    val expiresAt: Long?, // null = indefinite
    val isActive: Boolean,
)

// Received location update
data class ContactLocation(
    val contactDestinationHash: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val bearing: Float?,
    val speed: Float?,
    val batteryPercent: Int?,
    val timestamp: Long,
)

// UI state for map screen
data class MapScreenState(
    val userLocation: Location?,
    val contactLocations: List<ContactLocationUi>,
    val activeShares: List<ActiveShareUi>,
    val isLocationPermissionGranted: Boolean,
    val isLoading: Boolean,
)

data class ContactLocationUi(
    val contact: Contact,
    val location: ContactLocation,
    val distanceMeters: Int?,
    val bearingDegrees: Float?,
    val estimatedWalkMinutes: Int?,
)

data class ActiveShareUi(
    val contact: Contact,
    val expiresAt: Instant?,
    val remainingMinutes: Int?,
)
```

### ViewModel

```kotlin
@HiltViewModel
class MapViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val locationSharingRepository: LocationSharingRepository,
    private val locationManager: LocationManager,
) : ViewModel() {

    private val _state = MutableStateFlow(MapScreenState())
    val state: StateFlow<MapScreenState> = _state.asStateFlow()
    
    init {
        observeUserLocation()
        observeContactLocations()
        observeActiveShares()
    }
    
    fun startSharingWith(contacts: List<Contact>, duration: SharingDuration) {
        viewModelScope.launch {
            locationSharingRepository.startSharing(contacts, duration)
        }
    }
    
    fun stopSharingWith(contact: Contact) {
        viewModelScope.launch {
            locationSharingRepository.stopSharing(contact)
        }
    }
    
    fun stopAllSharing() {
        viewModelScope.launch {
            locationSharingRepository.stopAllSharing()
        }
    }
    
    fun setProximityAlert(contact: Contact, enabled: Boolean, distanceMeters: Int) {
        viewModelScope.launch {
            locationSharingRepository.setProximityAlert(contact, enabled, distanceMeters)
        }
    }
    
    private fun observeUserLocation() {
        viewModelScope.launch {
            locationManager.locationUpdates.collect { location ->
                _state.update { it.copy(userLocation = location) }
            }
        }
    }
    
    private fun observeContactLocations() {
        viewModelScope.launch {
            locationSharingRepository.incomingLocations.collect { locations ->
                val contactLocationsUi = locations.mapNotNull { location ->
                    val contact = contactRepository.getByDestinationHash(location.contactDestinationHash)
                    contact?.let {
                        ContactLocationUi(
                            contact = it,
                            location = location,
                            distanceMeters = calculateDistance(state.value.userLocation, location),
                            bearingDegrees = calculateBearing(state.value.userLocation, location),
                            estimatedWalkMinutes = estimateWalkTime(state.value.userLocation, location),
                        )
                    }
                }
                _state.update { it.copy(contactLocations = contactLocationsUi) }
            }
        }
    }
    
    private fun observeActiveShares() {
        viewModelScope.launch {
            locationSharingRepository.activeShares.collect { shares ->
                val activeSharesUi = shares.mapNotNull { share ->
                    val contact = contactRepository.getByDestinationHash(share.contactDestinationHash)
                    contact?.let {
                        ActiveShareUi(
                            contact = it,
                            expiresAt = share.expiresAt?.let { Instant.ofEpochMilli(it) },
                            remainingMinutes = share.expiresAt?.let { 
                                ((it - System.currentTimeMillis()) / 60000).toInt() 
                            },
                        )
                    }
                }
                _state.update { it.copy(activeShares = activeSharesUi) }
            }
        }
    }
}
```

---

## Privacy & Permissions

### Permission Request Flow

1. **Pre-permission education screen** (before system dialog)
2. **System permission request** (FINE_LOCATION + COARSE_LOCATION)
3. **Background location** (if needed, requested separately per Android guidelines)

```kotlin
@Composable
fun LocationPermissionRationale(
    onEnableClick: () -> Unit,
    onNotNowClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Illustration
        Image(
            painter = painterResource(R.drawable.illustration_location_sharing),
            contentDescription = null,
            modifier = Modifier.size(200.dp),
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "Find your friends on a map",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Columba uses your location to help friends find you at events. " +
                   "You control exactly who can see your location and for how long.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onEnableClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enable Location")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(
            onClick = onNotNowClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Not Now")
        }
    }
}
```

### Settings: Location Sharing Section

Add to the Settings screen:

```
┌─────────────────────────────────────┐
│   Location Sharing                  │
│   ─────────────────────────────────  │
│                                     │
│   Allow location sharing        🔘  │  ← Master toggle
│                                     │
│   Currently sharing with:           │
│   • Alice Chen      47 min    [Stop]│
│   • Bob Smith       2 hrs     [Stop]│
│                                     │
│   [  Stop All Sharing  ]            │
│                                     │
│   ─────────────────────────────────  │
│                                     │
│   Default duration              >   │  ← Opens picker
│   1 hour                            │
│                                     │
│   Location precision            >   │
│   Precise                           │
│                                     │
└─────────────────────────────────────┘
```

---

## Foreground Service & Notifications

When sharing location, a foreground service is required. The notification should be informative but not intrusive.

### Notification Design

```kotlin
private fun createSharingNotification(sharingWith: List<Contact>): Notification {
    val names = sharingWith.joinToString(", ") { it.displayName }
    
    return NotificationCompat.Builder(context, LOCATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_location_sharing)
        .setContentTitle("Sharing your location")
        .setContentText("With $names")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(createMapPendingIntent())
        .addAction(
            R.drawable.ic_stop,
            "Stop Sharing",
            createStopSharingPendingIntent(),
        )
        .build()
}
```

---

## M3 Component Reference

| UI Element | M3 Component | Import |
|------------|--------------|--------|
| Bottom navigation | `NavigationBar` + `NavigationBarItem` | `androidx.compose.material3.NavigationBar` |
| Contacts segments | `SegmentedButton` | `androidx.compose.material3.SegmentedButton` |
| Share location button | `ExtendedFloatingActionButton` | `androidx.compose.material3.ExtendedFloatingActionButton` |
| My location button | `SmallFloatingActionButton` | `androidx.compose.material3.SmallFloatingActionButton` |
| Contact details | `ModalBottomSheet` | `androidx.compose.material3.ModalBottomSheet` |
| Sharing toggle | `Switch` | `androidx.compose.material3.Switch` |
| Duration chips | `FilterChip` | `androidx.compose.material3.FilterChip` |
| Selected contacts | `InputChip` | `androidx.compose.material3.InputChip` |
| Status indicator | `AssistChip` | `androidx.compose.material3.AssistChip` |
| Contact list | `ListItem` | `androidx.compose.material3.ListItem` |
| Search field | `OutlinedTextField` | `androidx.compose.material3.OutlinedTextField` |
| Action buttons | `Button`, `OutlinedButton` | `androidx.compose.material3.Button` |
| Dividers | `HorizontalDivider` | `androidx.compose.material3.HorizontalDivider` |
| Cards | `ElevatedCard` | `androidx.compose.material3.ElevatedCard` |

---

## Map Integration

### Recommended: MapLibre with OpenStreetMap

Use MapLibre for offline-capable maps that work over mesh networking.

**Dependencies:**

```kotlin
// build.gradle.kts (app module)
implementation("org.maplibre.gl:android-sdk:10.2.0")
implementation("org.maplibre.gl:android-plugin-annotation-v9:2.0.0")
```

**Compose wrapper:**

```kotlin
@Composable
fun ColumbaMap(
    userLocation: Location?,
    contactLocations: List<ContactLocationUi>,
    onContactClick: (Contact) -> Unit,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Use AndroidView to wrap MapLibre MapView
    // Or use maplibre-compose if available
    
    AndroidView(
        factory = { context ->
            MapView(context).apply {
                getMapAsync { map ->
                    map.setStyle(Style.Builder().fromUri("asset://map_style.json"))
                    // Configure map settings
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isZoomGesturesEnabled = true
                }
            }
        },
        update = { mapView ->
            // Update markers when contactLocations changes
        },
        modifier = modifier,
    )
}
```

### Offline Tile Support

For festival/mesh scenarios, pre-bundle or download offline tiles:

```kotlin
class OfflineMapManager(private val context: Context) {
    
    fun downloadRegion(bounds: LatLngBounds, name: String) {
        // Use MapLibre offline manager to download tiles
    }
    
    fun getAvailableRegions(): List<OfflineRegion> {
        // Return cached regions
    }
}
```

---

## Implementation Phases

### Phase 1: Foundation (MVP)

- [ ] Update navigation: Add Map destination, remove Announces from nav bar
- [ ] Create Contacts screen with SegmentedButton (My Contacts / Network)
- [ ] Move AnnounceStreamScreen content to Network segment
- [ ] Create basic MapScreen with user location
- [ ] Implement location permission flow
- [ ] Add contact markers on map (static positions for testing)

### Phase 2: Core Sharing

- [ ] Implement LocationShare data model and Room persistence
- [ ] Create ShareLocationBottomSheet with contact selection and duration
- [ ] Implement share toggle in conversation header
- [ ] Create foreground service for location updates
- [ ] Implement LXMF field for location data transmission
- [ ] Parse incoming location updates and display on map

### Phase 3: Enhanced UX

- [ ] Contact detail bottom sheet from map markers
- [ ] Distance and direction calculations
- [ ] Expiration countdown and notifications
- [ ] Sharing status indicators in conversation list
- [ ] Settings section for location sharing management

### Phase 4: Advanced Features

- [ ] Proximity alerts (geofence-based)
- [ ] Battery level sharing
- [ ] Offline map tile downloading
- [ ] "Find Me" beacon feature
- [ ] Home screen widget

---

## Testing Checklist

### Unit Tests

- [ ] SharingDuration expiration calculations
- [ ] Distance and bearing calculations
- [ ] Location state transitions
- [ ] Expiration handling (auto-stop when expired)

### Integration Tests

- [ ] Location permission grant/deny flows
- [ ] Start/stop sharing persistence
- [ ] LXMF location message encoding/decoding
- [ ] Foreground service lifecycle

### UI Tests

- [ ] Navigation to Map screen
- [ ] Contacts SegmentedButton switching
- [ ] Share location flow completion
- [ ] Bottom sheet interactions
- [ ] Map marker tap behavior

### Manual Testing Scenarios

- [ ] Share location with one contact, verify they receive updates
- [ ] Set 15-minute expiration, verify auto-stop
- [ ] Background the app, verify sharing continues
- [ ] Kill the app, verify sharing stops gracefully
- [ ] Test with location permission denied
- [ ] Test offline map display (airplane mode)
