"""
AutoInterface Hot-Add Manager for Columba

Handles dynamically adding new network interfaces to an existing RNS AutoInterface
at runtime. This solves the problem where AutoInterface only scans for network
interfaces once during __init__ — if WiFi connects after startup, the new interface
is invisible to peer discovery until this module adds it.

The hot-add approach is surgical: it adds only NEW interfaces to the existing
AutoInterface without tearing down existing sockets, threads, or peer connections.
"""
import json
import socket
import socketserver
import struct
import threading
import time
from typing import Dict, Optional

from logging_utils import log_debug, log_error, log_info

# Tag for logging
TAG = "AutoInterfaceManager"


class _IPv6UDPServer(socketserver.UDPServer):
    """UDPServer subclass bound to IPv6, avoiding global class mutation."""
    address_family = socket.AF_INET6


# Serializes concurrent hot_add_interfaces() calls.  Rapid network changes
# (e.g. WiFi toggling) fire multiple serviceScope.launch coroutines on the
# Kotlin side, and the Python GIL is released during socket syscalls, so
# without this lock two calls could both see the same interface as unadopted
# and race to bind the same ports.
_hot_add_lock = threading.Lock()


def hot_add_interfaces() -> str:
    """
    Scan for network interfaces not yet adopted by the AutoInterface and add them.

    Creates multicast discovery sockets, unicast discovery sockets, discovery
    threads, and data port UDP servers for each new interface — mirroring the
    setup that AutoInterface.__init__ and final_init perform at startup.

    This is idempotent: if no new interfaces are found, it returns immediately.

    Returns:
        JSON string: {"success": true/false, "action": "...", "count": N}
    """
    with _hot_add_lock:
        try:
            import RNS
            from RNS.Interfaces.AutoInterface import AutoInterface as AutoInterfaceClass
            from RNS.Interfaces import netinfo
        except ImportError:
            return json.dumps({"success": False, "error": "RNS not available"})

        # Find the existing AutoInterface in Transport
        auto_iface = _find_auto_interface(RNS)
        if auto_iface is None:
            return json.dumps({"success": False, "error": "no AutoInterface in Transport"})

        # Scan for interfaces not yet adopted
        new_adopted = _scan_new_interfaces(auto_iface, AutoInterfaceClass, netinfo)
        if not new_adopted:
            log_debug(TAG, "hot_add_interfaces",
                      f"No new interfaces (adopted: {list(auto_iface.adopted_interfaces.keys())})")
            return json.dumps({"success": True, "action": "no_new_interfaces"})

        # Add each new interface
        added_count = 0
        for ifname, link_local_addr in new_adopted.items():
            try:
                _add_interface(auto_iface, AutoInterfaceClass, ifname, link_local_addr)
                added_count += 1
                RNS.log(
                    f"{auto_iface} Hot-added interface {ifname} "
                    f"with address {link_local_addr}",
                    RNS.LOG_NOTICE
                )
            except Exception as e:
                log_error(TAG, "hot_add_interfaces", f"Failed to hot-add {ifname}: {e}")
                RNS.log(
                    f"Could not hot-add interface {ifname} to {auto_iface}: {e}",
                    RNS.LOG_ERROR
                )

        # Ensure AutoInterface is marked as active
        if added_count > 0:
            if not auto_iface.receives:
                auto_iface.receives = True
            if not auto_iface.OUT:
                auto_iface.OUT = True
            auto_iface.carrier_changed = True

        log_info(TAG, "hot_add_interfaces", f"Hot-added {added_count} interface(s)")
        return json.dumps({
            "success": True,
            "action": "added_interfaces",
            "count": added_count
        })


def _find_auto_interface(rns_module) -> Optional[object]:
    """Find the first AutoInterface instance in RNS Transport."""
    from RNS.Interfaces.AutoInterface import AutoInterface as AutoInterfaceClass
    for iface in rns_module.Transport.interfaces:
        if isinstance(iface, AutoInterfaceClass):
            return iface
    return None


def _scan_new_interfaces(auto_iface, auto_cls, netinfo) -> Dict[str, str]:
    """
    Scan for network interfaces with link-local IPv6 that aren't yet adopted.

    Returns:
        Dict mapping interface name -> link-local address for new interfaces.
    """
    new_adopted = {}
    for ifname in auto_iface.list_interfaces():
        if ifname in auto_iface.adopted_interfaces:
            continue
        if ifname in auto_cls.ANDROID_IGNORE_IFS:
            continue
        if ifname in auto_iface.ignored_interfaces:
            continue
        if ifname in auto_cls.ALL_IGNORE_IFS:
            continue
        if len(auto_iface.allowed_interfaces) > 0 and ifname not in auto_iface.allowed_interfaces:
            continue

        addresses = auto_iface.list_addresses(ifname)
        if netinfo.AF_INET6 in addresses:
            for address in addresses[netinfo.AF_INET6]:
                if "addr" in address and address["addr"].startswith("fe80:"):
                    link_local_addr = auto_iface.descope_linklocal(address["addr"])
                    new_adopted[ifname] = link_local_addr
                    break

    return new_adopted


def _add_interface(auto_iface, auto_cls, ifname: str, link_local_addr: str):
    """
    Add a single network interface to the AutoInterface.

    Sets up:
    1. Unicast discovery socket (for reverse peering)
    2. Multicast discovery socket (joins the multicast group)
    3. Discovery + announce threads
    4. UDP data server for incoming packets
    """
    log_info(TAG, "_add_interface", f"Hot-adding {ifname} ({link_local_addr})")

    if_index = auto_iface.interface_name_to_index(ifname)
    if_struct = struct.pack("I", if_index)

    uds = None
    ds = None
    udp_server = None
    try:
        # --- Unicast discovery socket ---
        uds = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        uds.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            uds.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        addr_info = socket.getaddrinfo(
            link_local_addr + "%" + ifname,
            auto_iface.unicast_discovery_port,
            socket.AF_INET6, socket.SOCK_DGRAM
        )
        uds.bind(addr_info[0][4])

        # --- Multicast discovery socket ---
        mcast_addr = auto_iface.mcast_discovery_address
        ds = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        ds.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            ds.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        ds.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, if_struct)

        # Join multicast group
        mcast_group = socket.inet_pton(socket.AF_INET6, mcast_addr) + if_struct
        ds.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_JOIN_GROUP, mcast_group)

        # Bind multicast socket
        if auto_iface.discovery_scope == auto_cls.SCOPE_LINK:
            addr_info = socket.getaddrinfo(
                mcast_addr + "%" + ifname,
                auto_iface.discovery_port,
                socket.AF_INET6, socket.SOCK_DGRAM
            )
        else:
            addr_info = socket.getaddrinfo(
                mcast_addr,
                auto_iface.discovery_port,
                socket.AF_INET6, socket.SOCK_DGRAM
            )
        ds.bind(addr_info[0][4])

        # --- Factory functions for discovery threads ---
        # Capture loop variables properly (avoids late-binding closure bug)
        def _make_discovery_loop(sock, name):
            def loop():
                auto_iface.discovery_handler(sock, name)
            return loop

        def _make_unicast_loop(sock, name):
            def loop():
                auto_iface.discovery_handler(sock, name, announce=False)
            return loop

        # --- Create UDP data server BEFORE starting any threads ---
        # If this fails, no threads are running yet, so socket cleanup in
        # the except block is clean (no threads blocking on recvfrom).
        local_addr = link_local_addr + "%" + str(if_index)
        addr_info = socket.getaddrinfo(
            local_addr, auto_iface.data_port,
            socket.AF_INET6, socket.SOCK_DGRAM
        )
        udp_server = _IPv6UDPServer(
            addr_info[0][4],
            auto_iface.handler_factory(auto_iface.process_incoming)
        )

        # --- Start ALL threads only after all resources are created ---
        threading.Thread(target=_make_discovery_loop(ds, ifname), daemon=True).start()
        threading.Thread(target=_make_unicast_loop(uds, ifname), daemon=True).start()
        thread = threading.Thread(target=udp_server.serve_forever)
        thread.daemon = True
        thread.start()

        # Register in AutoInterface state ONLY after successful setup
        auto_iface.link_local_addresses.append(link_local_addr)
        auto_iface.adopted_interfaces[ifname] = link_local_addr
        auto_iface.multicast_echoes[ifname] = time.time()
        auto_iface.interface_servers[ifname] = udp_server
    except Exception:
        for s in (uds, ds):
            if s:
                try:
                    s.close()
                except OSError:
                    pass
        if udp_server:
            try:
                udp_server.server_close()
            except OSError:
                pass
        raise
