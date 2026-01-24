# Architecture

**Analysis Date:** 2026-01-23

## Pattern Overview

**Overall:** Multi-process Android application with clean layered architecture (UI → ViewModel → Repository → Database + Service IPC).

**Key Characteristics:**
- Clean Architecture with clear separation of concerns across modules
- Dependency Injection (Hilt) for testability and flexibility
- Service-based background processing for Python Reticulum networking stack
- Protocol abstraction layer enabling future Rust/UniFFI migration
- Cross-process communication via AIDL for reliable message passing
- Reactive data flow using Kotlin Coroutines and StateFlow

## Layers

**UI Layer:**
- Purpose: Compose-based user interface, Material Design 3 themes
- Location: `app/src/main/java/com/lxmf/messenger/ui/`
- Contains: Screens, Components, Theme definitions, UI models, Utilities
- Depends on: ViewModels, Repositories, Domain models, Utilities
- Used by: MainActivity (entry point)

**ViewModel Layer:**
- Purpose: State management, business logic orchestration, screen-specific state
- Location: `app/src/main/java/com/lxmf/messenger/viewmodel/`
- Contains: ViewModel classes for each major screen/feature
- Depends on: Repositories, use case logic, Coroutines
- Used by: UI Layer (Composables)

**Repository Layer:**
- Purpose: Data access abstraction, combining database and service sources
- Location: `data/src/main/java/com/lxmf/messenger/data/repository/` and `app/src/main/java/com/lxmf/messenger/repository/`
- Contains: Repository implementations for conversations, contacts, settings, identities
- Depends on: DAOs, Services, DataStore, External protocols
- Used by: ViewModels, Service managers

**Service Layer (Background Process):**
- Purpose: Long-running Reticulum networking service in separate `:reticulum` process
- Location: `app/src/main/java/com/lxmf/messenger/service/`
- Contains: ReticulumService (lifecycle shell), ServiceModule (DI), managers, state
- Depends on: ReticulumProtocol, Database, Python/Chaquopy
- Used by: Main app process via AIDL binder

**Data/Database Layer:**
- Purpose: Persistent data storage using Room ORM
- Location: `data/src/main/java/com/lxmf/messenger/data/db/`
- Contains: Database schema, DAOs, Entities, Migrations
- Depends on: Room, SQLite
- Used by: Repositories, Service managers

**Reticulum Protocol Layer:**
- Purpose: Network stack abstraction for mesh networking protocol
- Location: `reticulum/src/main/java/com/lxmf/messenger/reticulum/`
- Contains: Protocol interface, ServiceReticulumProtocol (service-based), BLE bridge, RNode USB
- Depends on: Python/Chaquopy (via Chaquopy), Android APIs, Coroutines
- Used by: Service managers, ViewModels

**Domain/Shared Models:**
- Purpose: Core data models and business logic
- Location: `reticulum/src/main/java/com/lxmf/messenger/reticulum/model/` and `app/src/main/java/com/lxmf/messenger/data/model/`
- Contains: Identity, Destination, Link, Message, AnnounceEvent, configuration models
- Depends on: Nothing (pure data)
- Used by: All layers

## Data Flow

**Initialization Flow (Cold Start):**

1. `ColumbaApplication.onCreate()` initializes DI and starts core managers
2. `MainActivity.onCreate()` sets up UI, navigates based on onboarding state
3. `ReticulumService.onCreate()` in background process initializes:
   - ServiceModule creates all managers (BLE, Messaging, Health Check, etc.)
   - PythonWrapperManager initializes Chaquopy and Python Reticulum instance
   - HealthCheckManager starts heartbeat polling (~5s intervals)
4. `ServiceReticulumProtocol` in main process establishes AIDL connection to service
5. UI ViewModels query repositories which delegate to service via IPC

**Message Reception Flow:**

1. Python Reticulum instance (in service process) receives packets
2. EventHandler in service parses packet to domain models (ReceivedPacket, LinkEvent, etc.)
3. CallbackBroadcaster sends broadcasts to main app process
4. Main app broadcasts received via BroadcastReceiver
5. Repository updates database (Message, Conversation, Announce tables)
6. StateFlow<> in repository notifies ViewModels
7. ViewModel recomposes UI

**Message Sending Flow:**

1. User composes message in ConversationScreen
2. ChatsViewModel calls MessagingRepository.sendMessage()
3. Repository delegates to service via AIDL (ReticulumServiceBinder)
4. Service's MessagingManager hands to Python for LXMF delivery
5. PacketReceipt returned via AIDL response
6. ViewModel updates conversation with delivery status

**State Management:**

- **Service State:** `ServiceState` (atomic thread-safe holder) in service process
- **UI State:** StateFlow-based repositories in main app process
- **Cross-Process Sync:** DataStore (protocol buffers) for persistent state
- **Network Status:** StateFlow from ReticulumProtocol, updated by HealthCheckManager

## Key Abstractions

**ReticulumProtocol (Interface):**
- Purpose: Network stack contract enabling implementation swapping
- Examples: `reticulum/src/main/java/com/lxmf/messenger/reticulum/protocol/ReticulumProtocol.kt`
- Pattern: Strategy pattern with async/suspend functions for non-blocking ops
- Implementations: `ServiceReticulumProtocol` (production), `MockReticulumProtocol` (testing)

**ReticulumServiceBinder (AIDL):**
- Purpose: IPC interface for main app to call service functions
- Examples: `app/src/main/java/com/lxmf/messenger/service/binder/ReticulumServiceBinder.kt`
- Pattern: Adapter pattern converting AIDL calls to internal manager methods
- Converts Python results to Kotlin types via `PythonResultConverter`

**Repository Pattern:**
- Purpose: Abstract data sources (database, service, DataStore)
- Examples: `ConversationRepository`, `IdentityRepository`, `SettingsRepository`
- Pattern: Combines database queries with service calls, exposes StateFlow<>
- Consistent across app - all repositories follow this structure

**Manager Pattern (Service Layer):**
- Purpose: Orchestrate complex operations, manage lifecycle
- Examples: `PythonWrapperManager`, `HealthCheckManager`, `MessagingManager`, `NetworkChangeManager`
- Pattern: Each manager handles one domain (Python, Health, Messaging, Network)
- Initialized in `ServiceModule.createManagers()`, all share `ServiceState`

**DAO Pattern (Database):**
- Purpose: Type-safe database queries
- Examples: `ConversationDao`, `MessageDao`, `PeerIdentityDao`
- Pattern: Room @Dao annotated interfaces returning Flow<> for reactive queries
- One DAO per entity type in `data/src/main/java/com/lxmf/messenger/data/db/dao/`

## Entry Points

**MainActivity:**
- Location: `app/src/main/java/com/lxmf/messenger/MainActivity.kt`
- Triggers: Intent.ACTION_MAIN, deep links (lxma://), USB device attached, BROADCAST_ACTION
- Responsibilities: Set up Compose UI, handle permissions, route based on onboarding state, manage navigation

**ColumbaApplication:**
- Location: `app/src/main/java/com/lxmf/messenger/ColumbaApplication.kt`
- Triggers: App process startup (before any activity)
- Responsibilities: Hilt DI initialization, start core managers (MessageCollector, AutoAnnounce, Migration), error reporting (Sentry)

**ReticulumService:**
- Location: `app/src/main/java/com/lxmf/messenger/service/ReticulumService.kt`
- Triggers: MainActivity.startService() or system launch in background
- Responsibilities: Foreground service lifecycle, initialize managers via ServiceModule, expose AIDL binder, monitor health

**RNodeCompanionService:**
- Location: `app/src/main/java/com/lxmf/messenger/service/RNodeCompanionService.kt`
- Triggers: Companion Device Manager (Android 12+) when RNode in range
- Responsibilities: Register Columba as companion app for RNode devices

## Error Handling

**Strategy:** Defensive programming with fallbacks, observable error states, crash reporting.

**Patterns:**

- **Result<T> Type:** Service and protocol methods return sealed Result<Success/Failure>
- **StateFlow Error State:** Repositories expose error flows that ViewModels observe
- **Try-Catch in Managers:** Broad exception handling in service managers with logging
- **Timeout Protection:** Service IPC calls use `withTimeoutOrNull()` to prevent ANR
- **Stale Heartbeat Detection:** HealthCheckManager monitors Python responsiveness, triggers restart if stale
- **Generation Tracking:** Race condition prevention - generation counter prevents stale async jobs from overwriting state
- **Crash Reporting:** Sentry integration via `CrashReportManager` for production issues

## Cross-Cutting Concerns

**Logging:**
- Approach: Android Log with debug tag per class (e.g., "ReticulumService", "ChatsViewModel")
- Level: DEBUG for normal flow, WARN/ERROR for issues
- Location: Imports `android.util.Log` throughout codebase

**Validation:**
- Approach: Input validation in ViewModels before repository calls
- Domain validation in repositories (e.g., identity hash format)
- Location: `app/src/main/java/com/lxmf/messenger/util/validation/`

**Authentication:**
- Approach: Identity-based mesh protocol (LXMF/Reticulum), not traditional login
- Identity files stored locally, exported/imported via QR codes or file sharing
- Location: Identity management in `IdentityRepository`, `IdentityManager`

**Permissions:**
- Approach: Runtime permissions via ActivityResultContracts (Camera, Location, Bluetooth, Recording)
- Manifest declared in `AndroidManifest.xml` with detailed rationale comments
- Location: Permission handling in MainActivity intent filters and runtime checks

---

*Architecture analysis: 2026-01-23*
