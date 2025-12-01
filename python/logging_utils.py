"""
Standardized logging utilities for Python code in the Columba project.

Logging Format: Columba:Python:ClassName: method(): message

This provides consistent, easily grep-able log output across all Python components,
matching the format used in Kotlin code (Columba:Kotlin:ClassName).

Usage:
    from logging_utils import log_debug, log_info, log_error

    class ReticulumWrapper:
        def initialize(self, config_json: str):
            log_info("ReticulumWrapper", "initialize", "Starting initialization")
            log_debug("ReticulumWrapper", "initialize", f"Config length: {len(config_json)}")

Grep patterns for filtering Columba Python logs:

    All Columba Python logs:
        adb logcat | grep "Columba:Python:"

    Specific component:
        adb logcat | grep "Columba:Python:ReticulumWrapper"

    All Columba logs (Kotlin + Python):
        adb logcat | grep "Columba:"

    With method context:
        adb logcat | grep "Columba:Python:ReticulumWrapper" | grep "initialize()"
"""

from typing import Optional


def columba_tag(class_name: str) -> str:
    """
    Generate standardized Columba log TAG for a Python class.

    Args:
        class_name: Simple class name (e.g., "ReticulumWrapper")

    Returns:
        Standardized TAG string: "Columba:Python:ClassName"
    """
    return f"Columba:Python:{class_name}"


def _format_message(class_name: str, method_name: str, message: str, level: Optional[str] = None) -> str:
    """
    Format a standardized log message.

    Args:
        class_name: Name of the class
        method_name: Name of the method
        message: Log message
        level: Optional log level (DEBUG, INFO, WARNING, ERROR)

    Returns:
        Formatted message: "Columba:Python:ClassName: method(): [LEVEL] message"
    """
    tag = columba_tag(class_name)
    level_prefix = f"[{level}] " if level else ""
    return f"{tag}: {method_name}(): {level_prefix}{message}"


def log_debug(class_name: str, method_name: str, message: str):
    """
    Log a DEBUG level message.

    Args:
        class_name: Name of the class
        method_name: Name of the method
        message: Debug message
    """
    print(_format_message(class_name, method_name, message, "DEBUG"))


def log_info(class_name: str, method_name: str, message: str):
    """
    Log an INFO level message.

    Args:
        class_name: Name of the class
        method_name: Name of the method
        message: Info message
    """
    print(_format_message(class_name, method_name, message, "INFO"))


def log_warning(class_name: str, method_name: str, message: str):
    """
    Log a WARNING level message.

    Args:
        class_name: Name of the class
        method_name: Name of the method
        message: Warning message
    """
    print(_format_message(class_name, method_name, message, "WARNING"))


def log_error(class_name: str, method_name: str, message: str):
    """
    Log an ERROR level message.

    Args:
        class_name: Name of the class
        method_name: Name of the method
        message: Error message
    """
    print(_format_message(class_name, method_name, message, "ERROR"))


def log_critical(class_name: str, method_name: str, message: str):
    """
    Log a CRITICAL level message.

    Args:
        class_name: Name of the class
        method_name: Name of the method
        message: Critical message
    """
    print(_format_message(class_name, method_name, message, "CRITICAL"))


def log_separator(class_name: str, method_name: str, char: str = "=", length: int = 60):
    """
    Log a separator line for visual organization.

    Args:
        class_name: Name of the class
        method_name: Name of the method
        char: Character to use for separator (default: "=")
        length: Length of separator line (default: 60)
    """
    print(_format_message(class_name, method_name, char * length))
