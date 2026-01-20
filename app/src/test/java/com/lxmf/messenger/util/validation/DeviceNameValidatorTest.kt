package com.lxmf.messenger.util.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNameValidatorTest {

    // ==================== Valid Names ====================

    @Test
    fun `validate returns Valid for empty name`() {
        val result = DeviceNameValidator.validate("")
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    @Test
    fun `validate returns Valid for blank name`() {
        val result = DeviceNameValidator.validate("   ")
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    @Test
    fun `validate returns Valid for RNode name`() {
        val result = DeviceNameValidator.validate("RNode T3")
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    @Test
    fun `validate returns Valid for rnode name (lowercase)`() {
        val result = DeviceNameValidator.validate("rnode device")
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    @Test
    fun `validate returns Valid for RNODE name (uppercase)`() {
        val result = DeviceNameValidator.validate("RNODE DEVICE")
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    @Test
    fun `validate returns Valid for mixed case RNode name`() {
        val result = DeviceNameValidator.validate("RnOdE Test")
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    @Test
    fun `validate returns Valid for RNode name at max length`() {
        // 32 characters starting with "RNode"
        val name = "RNode" + "X".repeat(27) // 5 + 27 = 32
        val result = DeviceNameValidator.validate(name)
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    // ==================== Warning Cases ====================

    @Test
    fun `validate returns Warning for non-RNode device name`() {
        val result = DeviceNameValidator.validate("HC-05")
        assertTrue(result is DeviceNameValidator.ValidationResult.Warning)
        val warning = result as DeviceNameValidator.ValidationResult.Warning
        assertEquals("Device may not be an RNode. Proceed with caution.", warning.message)
    }

    @Test
    fun `validate returns Warning for name that contains RNode but does not start with it`() {
        val result = DeviceNameValidator.validate("My RNode Device")
        assertTrue(result is DeviceNameValidator.ValidationResult.Warning)
    }

    @Test
    fun `validate returns Warning for name starting with R but not RNode`() {
        val result = DeviceNameValidator.validate("Radio Device")
        assertTrue(result is DeviceNameValidator.ValidationResult.Warning)
    }

    @Test
    fun `validate returns Warning for device name at max length not starting with RNode`() {
        // 32 characters NOT starting with "RNode"
        val name = "X".repeat(32)
        val result = DeviceNameValidator.validate(name)
        assertTrue(result is DeviceNameValidator.ValidationResult.Warning)
    }

    // ==================== Error Cases ====================

    @Test
    fun `validate returns Error for name exceeding max length`() {
        val name = "X".repeat(33) // 33 characters
        val result = DeviceNameValidator.validate(name)
        assertTrue(result is DeviceNameValidator.ValidationResult.Error)
        val error = result as DeviceNameValidator.ValidationResult.Error
        assertEquals("Device name must be 32 characters or less", error.message)
    }

    @Test
    fun `validate returns Error for RNode name exceeding max length`() {
        // Even if it starts with RNode, length limit is enforced first
        val name = "RNode" + "X".repeat(28) // 5 + 28 = 33 characters
        val result = DeviceNameValidator.validate(name)
        assertTrue(result is DeviceNameValidator.ValidationResult.Error)
    }

    @Test
    fun `validate returns Error for very long device name`() {
        val name = "X".repeat(100)
        val result = DeviceNameValidator.validate(name)
        assertTrue(result is DeviceNameValidator.ValidationResult.Error)
    }

    // ==================== Boundary Tests ====================

    @Test
    fun `validate accepts name at exact max length 32`() {
        val name = "RNode" + "X".repeat(27) // Exactly 32 characters
        assertEquals(32, name.length)
        val result = DeviceNameValidator.validate(name)
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    @Test
    fun `validate rejects name at 33 characters`() {
        val name = "X".repeat(33)
        assertEquals(33, name.length)
        val result = DeviceNameValidator.validate(name)
        assertTrue(result is DeviceNameValidator.ValidationResult.Error)
    }

    @Test
    fun `validate accepts single character RNode prefix`() {
        val result = DeviceNameValidator.validate("R")
        // Single "R" doesn't start with "RNode" (full word check)
        assertTrue(result is DeviceNameValidator.ValidationResult.Warning)
    }

    @Test
    fun `validate accepts exactly RNode with no suffix`() {
        val result = DeviceNameValidator.validate("RNode")
        assertTrue(result is DeviceNameValidator.ValidationResult.Valid)
    }

    // ==================== Constant Verification ====================

    @Test
    fun `MAX_DEVICE_NAME_LENGTH is 32`() {
        assertEquals(32, DeviceNameValidator.MAX_DEVICE_NAME_LENGTH)
    }
}
