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

## Reporting Vulnerabilities

Report security issues via [GitHub Issues](https://github.com/torlando-tech/columba/issues) with the "security" label. Include steps to reproduce and potential impact. We aim to respond within 48 hours.

## Supported Versions

Only the latest release receives security updates.
