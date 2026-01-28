# Requirements: Columba 0.7.2 Bug Fixes

**Defined:** 2026-01-24
**Core Value:** Fix the performance degradation and relay selection loop bugs so users have a stable, responsive app experience.

## v1 Requirements

Requirements for this bug fix milestone. Each maps to roadmap phases.

### Performance (#340)

- [ ] **PERF-01**: App maintains responsive UI regardless of background operations
- [ ] **PERF-02**: No progressive performance degradation over app runtime
- [ ] **PERF-03**: Interface Discovery screen scrolls smoothly

### Relay Selection (#343)

- [ ] **RELAY-01**: Relay auto-selection does not loop (add/remove/add cycle)
- [ ] **RELAY-02**: Root cause of automatic relay unset identified and fixed

### Clear Announces (#365)

- [x] **ANNOUNCE-01**: Clear All Announces preserves contacts in My Contacts

## v2 Requirements

Deferred bug fixes to address in a future milestone.

### Notifications (#338)

- **NOTF-01**: No duplicate notifications after service restart for already-read messages

### Permissions (#342)

- **PERM-01**: Location permission dialog stays dismissed until app restart

## Out of Scope

Explicitly excluded from this milestone.

| Feature | Reason |
|---------|--------|
| New features | Bug fix milestone only |
| Architecture changes | Minimize risk, fix bugs with targeted changes |

## Traceability

Which phases cover which requirements.

| Requirement | Phase | Status |
|-------------|-------|--------|
| PERF-01 | Phase 1 | Pending |
| PERF-02 | Phase 1 | Pending |
| PERF-03 | Phase 1 | Pending |
| RELAY-01 | Phase 2 | Pending |
| RELAY-02 | Phase 2 | Pending |
| ANNOUNCE-01 | Phase 2.1 | Complete |

**Coverage:**
- v1 requirements: 6 total
- Mapped to phases: 6
- Unmapped: 0

---
*Requirements defined: 2026-01-24*
*Last updated: 2026-01-27 after phase 2.1 completion*
