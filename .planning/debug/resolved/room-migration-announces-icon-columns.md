---
status: resolved
trigger: "room-migration-announces-icon-columns"
created: 2026-01-24T00:00:00Z
updated: 2026-01-24T00:06:00Z
---

## Current Focus

hypothesis: CONFIRMED - Fix implemented and verified
test: Code review of migration logic
expecting: Migration correctly drops icon columns while preserving all other data
next_action: Archive debug session and commit fix

## Symptoms

expected: App should open database successfully after upgrade/migration
actual: App crashes with IllegalStateException during database migration validation
errors: java.lang.IllegalStateException: Migration didn't properly handle: announces(com.lxmf.messenger.data.db.entity.AnnounceEntity)
- Found schema has extra columns: iconName, iconBackgroundColor, iconForegroundColor (all TEXT, nullable, defaultValue='NULL')
- Found schema has propagationTransferLimitKb with defaultValue='NULL' vs Expected 'undefined'
reproduction: User upgraded from a version that had icon columns in AnnounceEntity to current version that doesn't have them
started: User reported this on v0.6.10-beta release

## Eliminated

## Evidence

- timestamp: 2026-01-24T00:01:00Z
  checked: DatabaseModule.kt migrations
  found: MIGRATION_30_31 (lines 1260-1289) creates peer_icons table and migrates icon data FROM announces table, but does NOT drop the icon columns from announces table
  implication: Icon columns remain in database schema but are removed from AnnounceEntity, causing validation failure

- timestamp: 2026-01-24T00:02:00Z
  checked: MIGRATION_26_27 (lines 1158-1171)
  found: This migration added iconName, iconForegroundColor, iconBackgroundColor columns to announces table
  implication: These columns were added in v27, then entity fields were removed but columns not dropped in v31

- timestamp: 2026-01-24T00:03:00Z
  checked: AnnounceEntity.kt comment (line 32-33)
  found: Comment says "old columns remain in the DB but are no longer used (Room ignores extra columns)"
  implication: This comment is INCORRECT - Room does NOT ignore extra columns during schema validation

## Resolution

root_cause: Migration 30→31 creates peer_icons table and migrates icon data from announces table, but fails to drop the icon columns (iconName, iconForegroundColor, iconBackgroundColor) from the announces table. Room's schema validation detects these orphaned columns and throws IllegalStateException.

fix: Created MIGRATION_31_32 that:
1. Renames announces table to announces_old
2. Creates new announces table with correct schema (without icon columns)
3. Copies all data except icon columns (16 columns preserved)
4. Drops old table
5. Recreates indices
6. Incremented database version from 31 to 32
7. Updated misleading comment in AnnounceEntity.kt

verification: Code review confirms:
- All 16 columns in AnnounceEntity are present in new table schema
- Icon columns (iconName, iconForegroundColor, iconBackgroundColor) are correctly excluded
- All data is preserved via SELECT statement matching INSERT column order
- Indices are recreated exactly as they existed (3 indices)
- Migration follows established pattern from previous recreate-style migrations (15_16, 14_15, etc.)

files_changed:
  - data/src/main/java/com/lxmf/messenger/data/db/ColumbaDatabase.kt (version 31→32)
  - data/src/main/java/com/lxmf/messenger/data/di/DatabaseModule.kt (added MIGRATION_31_32)
  - data/src/main/java/com/lxmf/messenger/data/db/entity/AnnounceEntity.kt (corrected comment)
