# Stub jnius module for Chaquopy
#
# The Android RNodeInterface checks for this module's existence before
# allowing interface creation. TCP mode doesn't actually use jnius,
# but the check happens unconditionally.
#
# This stub satisfies the import check so TCP RNode connections work.
# Bluetooth RNode connections use our custom ColumbaRNodeInterface instead.


def autoclass(name):
    """Stub autoclass - raises error if actually called."""
    raise NotImplementedError(
        f"jnius.autoclass('{name}') called but jnius is not available. "
        "Use ColumbaRNodeInterface for Bluetooth RNode connections."
    )
