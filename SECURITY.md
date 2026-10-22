# Security Policy

## APK Verification

All official Columba releases are signed with our release certificate:

**SHA-256:**
```
02:2B:12:20:48:63:A3:1F:BF:07:5B:C9:F9:34:1E:33:52:78:80:2E:80:C9:27:A4:75:46:E4:7E:2F:4A:0C:5F
```

**SHA-1:**
```
0A:6B:AE:58:4E:D7:B5:D0:35:8B:3C:7B:65:11:D6:3A:81:21:0D:CE
```

### Verifying Before Installation

With Android SDK tools installed:

```bash
apksigner verify --print-certs columba-x.x.x.apk
```

Compare the SHA-256 digest in the output with the fingerprint above.

### SHA256 Checksums

Release APKs include a `.sha256` file:

```bash
sha256sum -c columba-x.x.x.apk.sha256
```

### If Verification Fails

Do not install. Delete the APK and download only from [GitHub Releases](https://github.com/torlando-tech/columba/releases). Report suspicious APKs via [GitHub Issues](https://github.com/torlando-tech/columba/issues).

**Note:** Android automatically verifies that app updates are signed with the same certificate, so subsequent updates are protected after you've verified your first installation.

## Optional crash reporting and memory diagnostics

The `sentry` build offers an opt-in during onboarding and under Settings → Advanced.
Reporting is off by default. The `noSentry` build does not include the Sentry SDK or
show those controls. No debug build, database migration, or transfer of personal data
is needed to use diagnostics in an ordinary signed release.

### Outgoing data policy

Reports use an explicit output allowlist rather than attempting to redact arbitrary
text. The policy reconstructs both events and their envelopes before caching and
before transmission. It permits:

- A per-event identifier and timestamp, Java platform, app/build version, fixed
  environment classification, and severity.
- Managed exception types and code locations: class/module names, method names,
  line numbers, in-app classification, and handled/unhandled state.
- The packaged build's ProGuard mapping UUID, for release stack-trace symbolication.
- The bounded aggregate memory diagnostics described below.

Exception messages, log text, breadcrumbs, source text, file paths, stack locals,
requests/URLs, headers, cookies, user/installation identifiers, device identifiers,
peer identifiers, arbitrary tags/contexts, and attachments are excluded. No message
content, contacts, private keys, databases, screenshots, or heap dumps are uploaded.
Unknown envelope item types are rejected, including sessions, transactions, profiles,
replay, feedback, logs and native dumps.

The core Java SDK replaces the Android auto-collection SDK. Automatic performance,
UI/system/logcat collection, native crashes and ANR reporting are disabled; this
deliberately reduces diagnostic coverage. Build-time tracing/logcat instrumentation
and source-context uploads are disabled; ProGuard mapping uploads remain available.

### Bounded memory history

Only the main app process reports. While consent is enabled, a sampler nominally runs
once per minute and retains at most 16 numeric samples in memory. Samples contain:

- Java heap used, committed and limit, rounded up to power-of-two MiB buckets;
  utilization in deciles.
- Power-of-two buckets for the processed-message-ID cache, peer-name cache and
  pending relay retry queue, read from their current sizes without copying contents.
- Bucketed counts of import/export operations observed in the current consent
  session, including import preview; completions from older sessions are ignored.
- Bucketed elapsed minutes since consent-enabled observation began, and fixed
  process-role, foreground/background and memory-pressure classifications.

Missing or unreadable counters are omitted, not represented as zero. Counter
providers are never polled while consent is disabled. No database scans, forced GC,
heap profiling, disk snapshot history or keep-alive service are used. High-memory
warnings require an observed crossing of 80% heap utilization, recovery below 70%
before another crossing, and a 15-minute warning interval.

These samples may be stale or unavailable during a crash. Capturing an OOM report
is best-effort; the history is not a heap dump and cannot identify retained objects
or establish the root cause of COLUMBA-EE by itself.

### Consent, pending reports and privacy limits

Startup resolves both the authoritative preference and durable startup permission
before enabling the SDK or replaying cached reports. Opt-out durably denies startup
permission before SDK shutdown and before any suspending preference write, stops
sampling, clears memory history, and removes pending SDK telemetry. Old consent
epochs cannot become authorized by a later opt-in. An HTTP request already in flight
cannot be recalled.

At most 10 sanitized envelopes are retained in a dedicated app-private SDK cache
for same-build retry. The old unrestricted SDK cache is deleted before activation.
Unsent reports from a different release/mapping are discarded rather than relabeled
with the current build. Cache cleanup is confined to SDK directories; identity,
message, attachment and local manual-crash-report storage are not deleted.

This is privacy minimization, not a promise of anonymity: counts/timing remain usage
metadata, and Sentry and network intermediaries necessarily see connection metadata,
including the source IP. IP retention/enrichment and other server-side policies are
separate deployment settings, not guaranteed by the client filter. Previously
uploaded reports are not retroactively removed by changing the client.

Local crash reports and user-initiated clipboard/share bug reports are separate
from Sentry. They can contain logs and free-form exception text; review them before
sharing. They are not automatically attached to Sentry reports.

## Reporting Vulnerabilities

Report security issues via [GitHub Issues](https://github.com/torlando-tech/columba/issues) with the "security" label. Include steps to reproduce and potential impact.

## Supported Versions

Only the latest release receives security updates.
