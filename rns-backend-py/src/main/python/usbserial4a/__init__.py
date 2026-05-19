# Stub usbserial4a module for Chaquopy
#
# The Android RNodeInterface checks for this module's existence before
# allowing interface creation. TCP mode doesn't actually use USB serial,
# but the check happens unconditionally.
#
# This stub satisfies the import check so TCP RNode connections work.
# USB serial connections are not supported on Chaquopy.


class serial4a:
    """Stub serial4a class - not functional, just satisfies import."""
    pass
