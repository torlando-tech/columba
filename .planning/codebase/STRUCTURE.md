# Codebase Structure

**Analysis Date:** 2026-01-23

## Directory Layout

```
columba/
├── app/                              # Main UI application module (Android app)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/lxmf/messenger/
│   │   │   │   ├── ui/                       # Compose screens, components, themes
│   │   │   │   ├── viewmodel/                # MVVM state holders
│   │   │   │   ├── service/                  # Background service & managers
│   │   │   │   ├── repository/               # Data access patterns
│   │   │   │   ├── data/                     # Local models and config
│   │   │   │   ├── reticulum/                # Protocol implementation & BLE
│   │   │   │   ├── util/                     # Utilities (validation, crypto, etc.)
│   │   │   │   ├── migration/                # Data migration logic
│   │   │   │   ├── startup/                  # Initialization managers
│   │   │   │   ├── notifications/            # Notification & call handling
│   │   │   │   ├── crypto/                   # Cryptographic utilities
│   │   │   │   ├── map/                      # Map feature logic
│   │   │   │   ├── MainActivity.kt           # Main entry point
│   │   │   │   └── ColumbaApplication.kt     # Application initialization
│   │   │   ├── res/                          # Resources (drawables, strings, XML)
│   │   │   ├── aidl/                         # AIDL service interface definitions
│   │   │   └── AndroidManifest.xml           # App manifest
│   │   ├── test/                             # Unit tests (local, in-process)
│   │   └── androidTest/                      # Instrumented tests (device/emulator)
│   └── build.gradle.kts                      # App module build config
│
├── data/                             # Data layer library module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/lxmf/messenger/data/
│   │   │   │   ├── db/                       # Room database schema
│   │   │   │   │   ├── ColumbaDatabase.kt
│   │   │   │   │   ├── dao/                  # Data Access Objects
│   │   │   │   │   └── entity/               # Room entity definitions
│   │   │   │   ├── repository/               # Data repository implementations
│   │   │   │   ├── model/                    # Data models
│   │   │   │   ├── storage/                  # Shared preferences & DataStore
│   │   │   │   └── di/                       # Data layer DI (DatabaseModule)
│   │   └── test/                             # Data layer unit tests
│   └── build.gradle.kts                      # Data module build config
│
├── reticulum/                        # Reticulum library module (networking)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/lxmf/messenger/reticulum/
│   │   │   │   ├── protocol/                 # Protocol abstraction & implementations
│   │   │   │   ├── model/                    # Domain models (Identity, Destination, etc.)
│   │   │   │   ├── ble/                      # Bluetooth Low Energy bridge
│   │   │   │   ├── rnode/                    # RNode radio device support
│   │   │   │   ├── usb/                      # USB serial communication
│   │   │   │   ├── bridge/                   # Python bridge layer
│   │   │   │   ├── audio/                    # Voice call audio handling
│   │   │   │   ├── call/                     # LXST telephony (voice calls)
│   │   │   │   └── util/                     # Protocol utilities
│   │   └── test/                             # Reticulum module tests
│   └── build.gradle.kts                      # Reticulum module build config
│
├── domain/                           # Domain module (empty structure, reserved)
│   └── build.gradle.kts              # Domain module placeholder
│
├── detekt-rules/                     # Custom Detekt linting rules
│   ├── src/
│   │   └── main/kotlin/
│   │       └── com/lxmf/messenger/detekt/
│   └── build.gradle.kts
│
├── gradle/                           # Gradle wrapper scripts
├── build.gradle.kts                  # Root build config (Kotlin DSL)
├── settings.gradle.kts               # Gradle settings (module definitions)
├── gradle.properties                 # Gradle properties (versions, flags)
├── .editorconfig                     # IDE formatter settings
├── detekt-config.yml                 # Detekt static analysis config
├── .codecov.yml                      # Code coverage config
└── CLAUDE.md                         # Project-specific instructions
```

## Directory Purposes

**app/**
- Purpose: Main application UI and orchestration
- Contains: Kotlin Android code (Activities, ViewModels, Composables), Resources, Manifests
- Key files: `MainActivity.kt`, `ColumbaApplication.kt`, `AndroidManifest.xml`

**data/**
- Purpose: Data layer abstraction and persistence
- Contains: Room database, DAOs, repositories, data models, configuration storage
- Key files: `ColumbaDatabase.kt`, entity classes, repository implementations

**reticulum/**
- Purpose: Network protocol implementation and radio device integration
- Contains: Reticulum protocol contract, BLE/RNode/USB bridges, networking models
- Key files: `ReticulumProtocol.kt`, `ServiceReticulumProtocol.kt`, device-specific bridges

**detekt-rules/**
- Purpose: Custom static analysis rules (pure JVM, not Android)
- Contains: Detekt rule implementations for project-specific linting
- Key files: Custom rule classes in Kotlin

## Key File Locations

**Entry Points:**
- `app/src/main/java/com/lxmf/messenger/MainActivity.kt`: Main activity launcher
- `app/src/main/java/com/lxmf/messenger/ColumbaApplication.kt`: Application initialization
- `app/src/main/java/com/lxmf/messenger/service/ReticulumService.kt`: Background service

**Configuration:**
- `app/src/main/AndroidManifest.xml`: Permissions, intent filters, service definitions
- `build.gradle.kts`: Root build script (plugin versions, detekt config)
- `gradle.properties`: Version constants, project flags
- `detekt-config.yml`: Code quality rules (advisory, not enforced)

**Core Logic:**
- `data/src/main/java/com/lxmf/messenger/data/db/ColumbaDatabase.kt`: Database schema
- `app/src/main/java/com/lxmf/messenger/repository/`: Repository layer (conversations, identities, settings)
- `app/src/main/java/com/lxmf/messenger/service/manager/`: Service managers (messaging, health, BLE)
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/protocol/ReticulumProtocol.kt`: Protocol contract

**Testing:**
- `app/src/test/`: Unit tests (JVM, mocked Android, no device needed)
- `app/src/androidTest/`: Instrumented tests (require device/emulator)
- `app/src/test/java/com/lxmf/messenger/test/`: Test fixtures and helpers
- Test fixture location: `app/src/test/java/com/lxmf/messenger/test/` (e.g., `BleTestFixtures.kt`)

## Naming Conventions

**Files:**
- Activities: `*Activity.kt` (e.g., `MainActivity.kt`)
- ViewModels: `*ViewModel.kt` (e.g., `ChatsViewModel.kt`)
- Repositories: `*Repository.kt` (e.g., `ConversationRepository.kt`)
- Services: `*Service.kt` (e.g., `ReticulumService.kt`)
- Managers: `*Manager.kt` (e.g., `HealthCheckManager.kt`, `PythonWrapperManager.kt`)
- DAOs: `*Dao.kt` (e.g., `MessageDao.kt`, `ConversationDao.kt`)
- Entities: `*Entity.kt` (e.g., `MessageEntity.kt`, `ConversationEntity.kt`)
- Models: `*.kt` with domain name (e.g., `Identity.kt`, `Destination.kt`)
- Screens/Composables: `*Screen.kt` or `*Composable.kt` (e.g., `ChatsScreen.kt`, `IdentityCard.kt`)
- Tests: `*Test.kt` for unit tests (e.g., `BleConnectionInfoTest.kt`)
- Test fixtures: `*TestFixtures.kt` or `*Fixtures.kt` (e.g., `BleTestFixtures.kt`)

**Directories:**
- Package structure mirrors domain: `com.lxmf.messenger.[feature]`
- Feature-specific packages group related code (e.g., `ui.screens.rnode`, `service.manager`)
- Test directories mirror source structure: `app/src/test/java/com/lxmf/messenger/[mirror-of-main]`

## Where to Add New Code

**New Feature (UI + Logic):**
- Primary code: `app/src/main/java/com/lxmf/messenger/ui/screens/[feature]/` for screens
- ViewModel: `app/src/main/java/com/lxmf/messenger/viewmodel/[Feature]ViewModel.kt`
- Repository: `data/src/main/java/com/lxmf/messenger/data/repository/[Feature]Repository.kt`
- Tests: Mirror structure under `app/src/test/java/` and `app/src/androidTest/java/`

**New Network Protocol Feature:**
- Implementation: `reticulum/src/main/java/com/lxmf/messenger/reticulum/[feature]/`
- Models: `reticulum/src/main/java/com/lxmf/messenger/reticulum/model/[Model].kt`
- Tests: `reticulum/src/test/java/com/lxmf/messenger/reticulum/[feature]/`

**New Component/Composable:**
- Implementation: `app/src/main/java/com/lxmf/messenger/ui/components/[ComponentName].kt`
- Models: `app/src/main/java/com/lxmf/messenger/ui/model/[ComponentModel].kt`

**Utilities:**
- Shared helpers: `app/src/main/java/com/lxmf/messenger/util/`
- Validation logic: `app/src/main/java/com/lxmf/messenger/util/validation/`
- Reticulum utilities: `reticulum/src/main/java/com/lxmf/messenger/reticulum/util/`

**Database Changes:**
- Entity: Add to `data/src/main/java/com/lxmf/messenger/data/db/entity/[EntityName].kt`
- DAO: Add to `data/src/main/java/com/lxmf/messenger/data/db/dao/[EntityName]Dao.kt`
- Migration: Add `MIGRATION_X_Y` in `data/src/main/java/com/lxmf/messenger/data/di/DatabaseModule.kt`
- Update: `ColumbaDatabase.version++`, add new entity to @Database annotation

**Service Managers (Background Process):**
- Manager: `app/src/main/java/com/lxmf/messenger/service/manager/[Feature]Manager.kt`
- Register: Add to `ServiceModule.createManagers()` in `app/src/main/java/com/lxmf/messenger/service/di/ServiceModule.kt`

## Special Directories

**res/ Directory:**
- Purpose: Android resources (strings, drawables, layouts, colors, fonts)
- Generated: No, all committed
- Committed: Yes

**build/ Directory:**
- Purpose: Build output and intermediate files
- Generated: Yes, created by Gradle
- Committed: No (in .gitignore)

**src/test/resources/**
- Purpose: Test-specific resource files
- Generated: No, manually created for tests
- Committed: Yes

**src/androidTest/**
- Purpose: Instrumented tests requiring device/emulator
- Contents: Integration tests, UI tests, database tests
- Example: `ServiceProcessInitializationTest.kt`, `PythonThreadSafetyTest.kt`

---

*Structure analysis: 2026-01-23*
