---
status: fixing
trigger: "Investigate why Columba's RNode flasher fails to restart the device and leaves it showing 'Missing Config', while rnodeconf's --autoinstall works correctly."
created: 2026-01-28T12:00:00Z
updated: 2026-01-28T12:30:00Z
---

## Current Focus

hypothesis: "Missing Config" is EXPECTED AND CORRECT behavior for Reticulum apps. The real issue is firmware hash calculation mismatch.
test: Compare how rnodeconf calculates partition hash vs how Columba calculates firmware binary hash
expecting: The hash methods are different - rnodeconf extracts from embedded hash in .bin, Columba calculates full SHA256
next_action: Verify the hash calculation difference and ensure Columba uses the correct method

## Symptoms

expected: Device restarts after flashing and shows proper status (not "Missing Config")
actual: Device doesn't auto-restart and shows "Missing Config" after Columba flashing
errors: None - just wrong behavior
reproduction: Flash a Heltec LoRa32 v4 with Columba's flasher vs rnodeconf --autoinstall
started: Discovered during manual testing of PR #301

## Eliminated

- hypothesis: Missing EEPROM provisioning
  evidence: Columba's RNodeDetector.provisionDevice() writes all required fields (product, model, hwrev, serial, timestamp, checksum, signature, lock byte)
  timestamp: 2026-01-28T12:10:00Z

- hypothesis: "Missing Config" means device is broken
  evidence: "Missing Config" is the NORMAL display for devices in "host-controlled mode" used by Reticulum apps (Columba, Sideband, MeshChat). TNC mode (saved radio config) is NOT needed.
  timestamp: 2026-01-28T12:12:00Z

## Evidence

- timestamp: 2026-01-28T12:05:00Z
  checked: rnodeconf autoinstall flow
  found: The complete flow is:
    1. Flash firmware using esptool (with --after hard_reset flag)
    2. Wait 7-8 seconds for device to boot
    3. Reconnect to device at serial port
    4. Write EEPROM (product, model, hwrev, serial, timestamp, checksum, signature, lock byte)
    5. Calculate partition_hash from firmware binary file
    6. Call set_firmware_hash() with partition_hash
    7. Call hard_reset() (sends CMD_RESET 0xF8)
    8. Wait for device to reboot
    9. Verify provisioning by reading EEPROM back
  implication: Columba has all these steps implemented but may have a hash calculation mismatch

- timestamp: 2026-01-28T12:08:00Z
  checked: rnodeconf partition hash calculation (line 2731-2750)
  found: get_partition_hash() for ESP32 does NOT calculate SHA256 of entire binary. Instead:
    ```python
    firmware_data = open(partition_file, "rb").read()
    calc_hash = hashlib.sha256(firmware_data[0:-32]).digest()  # Hash of binary MINUS last 32 bytes
    part_hash = firmware_data[-32:]  # Last 32 bytes is embedded hash
    if calc_hash == part_hash:
        return part_hash  # Return the embedded hash
    ```
  implication: ESP32 firmware has SHA256 hash embedded in last 32 bytes! rnodeconf extracts this, Columba calculates from scratch.

- timestamp: 2026-01-28T12:10:00Z
  checked: Columba FirmwarePackage.calculateFirmwareBinaryHash()
  found: Calculates SHA256 of entire binary: `MessageDigest.getInstance("SHA-256").digest(firmwareData)`
  implication: This produces DIFFERENT hash than what rnodeconf uses (hash embedded in binary vs hash of whole binary)

- timestamp: 2026-01-28T12:12:00Z
  checked: Columba RNodeDetector.provisionAndSetFirmwareHash()
  found: Comments in code (lines 524-527) explicitly state:
    "Note: This does NOT configure TNC mode (radio parameters saved to EEPROM).
    The device will show 'Missing Config' after provisioning, which is EXPECTED
    and CORRECT for devices used with Reticulum apps"
  implication: "Missing Config" display is intentional and correct. Real issue is likely hash mismatch.

- timestamp: 2026-01-28T12:14:00Z
  checked: esptool invocation differences
  found: rnodeconf uses `--after hard_reset` flag which triggers automatic reset after flashing. Columba's ESPToolFlasher does send FLASH_END with reboot=true and calls hardReset(), but for ESP32-S3 native USB devices the reset may not work reliably.
  implication: For ESP32-S3 devices, Columba correctly detects this and shows FlashState.NeedsManualReset

## Resolution

root_cause: TWO ISSUES FOUND:
1. Firmware hash calculation was incorrect for ESP32 devices. The RNode firmware embeds the SHA256 hash in the last 32 bytes of the application binary. rnodeconf extracts this embedded hash, while Columba was calculating SHA256 of the ENTIRE binary (including the embedded hash), producing a wrong value.

2. The standard flashing flow was NOT calling provisionDevice() after flashing. Only ESP32-S3 native USB devices went through provisioning (after manual reset). Non-native USB devices (e.g., devices with USB-UART bridges like CP2102) were just detecting the device and completing without writing EEPROM or setting firmware hash.

fix:
1. Modified FirmwarePackage.calculateFirmwareBinaryHash() to:
   - For ESP32: Extract the embedded hash from last 32 bytes of the binary
   - Validate by checking SHA256(firmwareData.dropLast(32)) == embeddedHash
   - For nRF52: Keep existing behavior (calculate SHA256 of entire binary)
   - Added additional file exclusions (boot_app0, console) to ensure only application binary is processed

2. Modified RNodeFlasher.flashFirmware() to:
   - Wait 5 seconds after flash for device reboot (instead of 2 seconds)
   - Call provisionDevice() with the pre-calculated firmware hash
   - Handle provisioning failure gracefully (flash was successful)

3. Modified RNodeFlasher.flashFirmwareAutoDetect() similarly:
   - Added provisioning step after successful flash
   - For native USB devices, passes null hash (obtained from device later)

verification:
- Build compiles successfully (assembleDebug)
- Unit tests pass (:reticulum:testDebugUnitTest)
- Device testing required to fully verify

files_changed:
- reticulum/src/main/java/com/lxmf/messenger/reticulum/flasher/FirmwarePackage.kt
- reticulum/src/main/java/com/lxmf/messenger/reticulum/flasher/RNodeFlasher.kt
