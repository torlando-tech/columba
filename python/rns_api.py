# rns_api.py — Thin pass-through to RNS/LXMF. NO business logic.
#
# Strangler Fig: This module gives Kotlin (via Chaquopy) a stable, narrow
# surface to call into. Every method receives primitives and returns either
# a raw Python object (for live-object interfaces like Identity, Destination,
# Link, LXMessage) or a dict/primitive.
#
# NO BUSINESS LOGIC HERE. If you're tempted to add a conditional or loop,
# it belongs in a Kotlin manager.
#
# Loaded alongside reticulum_wrapper.py — never modify the wrapper.

import RNS
import LXMF
import logging
import traceback

from interface_lookup import format_interface_name
from logging_utils import log_debug

log = logging.getLogger("rns_api")


class RnsApi:
    """Thin Python bridge for Kotlin binding interfaces."""

    def __init__(self):
        self.reticulum = None
        self.lxmf_router = None

    # ─── RnsReticulum ─────────────────────────────────────────────

    def start(self, config_dir, enable_transport=False, loglevel=None):
        """Start Reticulum. Returns True on success."""
        if loglevel is None:
            loglevel = RNS.LOG_WARNING
        try:
            self.reticulum = RNS.Reticulum(
                configdir=config_dir,
                loglevel=loglevel,
            )
            if enable_transport:
                RNS.Transport.start()
            return True
        except Exception:
            log.error("Failed to start Reticulum: %s", traceback.format_exc())
            return False

    def stop(self):
        """Shutdown Reticulum."""
        try:
            if self.reticulum:
                RNS.Transport.exit_handler()
                RNS.Reticulum.exit_handler(self.reticulum)
                self.reticulum = None
        except Exception:
            log.error("Error during shutdown: %s", traceback.format_exc())

    def is_started(self):
        return self.reticulum is not None

    def is_transport_enabled(self):
        try:
            return RNS.Transport.transport_enabled() if self.reticulum else False
        except Exception:
            return False

    def get_rns_version(self):
        try:
            return RNS.__version__
        except Exception:
            return None

    # ─── RnsIdentityProvider ──────────────────────────────────────

    def create_identity(self):
        """Create a new identity. Returns live RNS.Identity object."""
        return RNS.Identity()

    def load_identity(self, path):
        """Load identity from file. Returns live RNS.Identity object."""
        return RNS.Identity.from_file(path)

    def identity_from_bytes(self, private_key_bytes):
        """Create identity from private key bytes. Returns live RNS.Identity object."""
        identity = RNS.Identity(create_keys=False)
        identity.load_private_key(bytes(private_key_bytes))
        return identity

    def recall_identity(self, dest_hash_bytes):
        """Recall a known identity by destination hash. Returns live object or None."""
        return RNS.Identity.recall(bytes(dest_hash_bytes))

    def recall_app_data(self, dest_hash_bytes):
        """Recall app data for a destination hash. Returns bytes or None."""
        return RNS.Identity.recall_app_data(bytes(dest_hash_bytes))

    def full_hash(self, data):
        """Compute full SHA-256 hash. Returns bytes."""
        return RNS.Identity.full_hash(bytes(data))

    def truncated_hash(self, data):
        """Compute truncated hash (16 bytes). Returns bytes."""
        return RNS.Identity.truncated_hash(bytes(data))

    # ─── RnsIdentity (live object methods) ────────────────────────

    @staticmethod
    def identity_get_hash(identity):
        """Get identity hash bytes."""
        return identity.hash

    @staticmethod
    def identity_get_hex_hash(identity):
        """Get identity hex hash string."""
        return identity.hexhash

    @staticmethod
    def identity_get_public_key(identity):
        """Get public key bytes."""
        return identity.get_public_key()

    @staticmethod
    def identity_get_private_key(identity):
        """Get private key bytes, or None if public-only."""
        try:
            return identity.get_private_key()
        except Exception:
            return None

    @staticmethod
    def identity_sign(identity, message):
        """Sign a message. Returns signature bytes."""
        return identity.sign(bytes(message))

    @staticmethod
    def identity_validate(identity, signature, message):
        """Validate a signature. Returns bool."""
        return identity.validate(bytes(signature), bytes(message))

    @staticmethod
    def identity_encrypt(identity, plaintext):
        """Encrypt with identity's public key. Returns ciphertext bytes."""
        return identity.encrypt(bytes(plaintext))

    @staticmethod
    def identity_decrypt(identity, ciphertext):
        """Decrypt with identity's private key. Returns plaintext bytes or None."""
        try:
            return identity.decrypt(bytes(ciphertext))
        except Exception:
            return None

    # ─── RnsDestinationProvider ───────────────────────────────────

    def create_destination(self, identity, direction, dest_type, app_name, aspects):
        """Create a destination. Returns live RNS.Destination object.

        Args:
            identity: Live RNS.Identity object
            direction: int (RNS.Destination.IN=1 or RNS.Destination.OUT=2)
            dest_type: int (SINGLE=1, GROUP=2, PLAIN=3, LINK=4)
            app_name: str
            aspects: list of str
        """
        return RNS.Destination(
            identity,
            direction,
            dest_type,
            app_name,
            *aspects,
        )

    # ─── RnsDestination (live object methods) ─────────────────────

    @staticmethod
    def destination_get_hash(destination):
        return destination.hash

    @staticmethod
    def destination_get_hex_hash(destination):
        return destination.hexhash

    @staticmethod
    def destination_announce(destination, app_data=None):
        """Announce this destination on the network."""
        if app_data is not None:
            destination.announce(app_data=bytes(app_data))
        else:
            destination.announce()

    @staticmethod
    def destination_set_link_established_callback(destination, callback):
        """Set link established callback. callback receives a raw Link object."""
        destination.set_link_established_callback(callback)

    @staticmethod
    def destination_register_request_handler(destination, path, response_generator):
        """Register a request handler on the destination."""
        destination.register_request_handler(
            path,
            response_generator=response_generator,
            allow=RNS.Destination.ALLOW_ALL,
        )

    @staticmethod
    def destination_deregister_request_handler(destination, path):
        destination.deregister_request_handler(path)

    # ─── RnsLinkProvider ──────────────────────────────────────────

    @staticmethod
    def create_link(destination, established_callback=None, closed_callback=None):
        """Create a link to a destination. Returns live RNS.Link object."""
        link = RNS.Link(destination)
        if established_callback:
            link.set_link_established_callback(established_callback)
        if closed_callback:
            link.set_link_closed_callback(closed_callback)
        return link

    # ─── RnsLink (live object methods) ────────────────────────────

    @staticmethod
    def link_get_id(link):
        return link.link_id

    @staticmethod
    def link_get_status(link):
        """Returns int status: 0=PENDING, 1=ACTIVE, 2=STALE, 4=CLOSED."""
        return link.status

    @staticmethod
    def link_get_mtu(link):
        return link.MDU  # Maximum Data Unit

    @staticmethod
    def link_get_rtt(link):
        """Returns RTT in seconds (float), or None."""
        return link.rtt

    @staticmethod
    def link_get_is_initiator(link):
        return link.initiator

    @staticmethod
    def link_send(link, data):
        """Send data over link. Returns True on success."""
        try:
            packet = RNS.Packet(link, bytes(data))
            receipt = packet.send()
            return receipt is not None
        except Exception:
            return False

    @staticmethod
    def link_teardown(link, reason=0):
        link.teardown()

    @staticmethod
    def link_identify(link, identity):
        """Identify on this link. Returns True on success."""
        try:
            link.identify(identity)
            return True
        except Exception:
            return False

    @staticmethod
    def link_get_remote_identity(link):
        """Get remote peer's identity. Returns live Identity or None."""
        return link.get_remote_identity()

    @staticmethod
    def link_get_establishment_rate(link):
        """Returns establishment rate in bits/sec, or None."""
        try:
            return link.get_establishment_rate()
        except Exception:
            return None

    @staticmethod
    def link_get_expected_rate(link):
        """Returns expected throughput in bits/sec, or None."""
        try:
            return link.get_expected_rate()
        except Exception:
            return None

    @staticmethod
    def link_encrypt(link, plaintext):
        return link.encrypt(bytes(plaintext))

    @staticmethod
    def link_decrypt(link, ciphertext):
        try:
            return link.decrypt(bytes(ciphertext))
        except Exception:
            return None

    @staticmethod
    def link_set_closed_callback(link, callback):
        link.set_link_closed_callback(callback)

    @staticmethod
    def link_set_packet_callback(link, callback):
        link.set_packet_callback(callback)

    # ─── RnsTransport ─────────────────────────────────────────────

    @staticmethod
    def transport_register_destination(destination):
        RNS.Transport.register_destination(destination)

    @staticmethod
    def transport_deregister_destination(destination):
        RNS.Transport.deregister_destination(destination)

    @staticmethod
    def transport_register_announce_handler(handler):
        RNS.Transport.register_announce_handler(handler)

    @staticmethod
    def transport_deregister_announce_handler(handler):
        RNS.Transport.deregister_announce_handler(handler)

    @staticmethod
    def transport_has_path(dest_hash_bytes):
        return RNS.Transport.has_path(bytes(dest_hash_bytes))

    @staticmethod
    def transport_request_path(dest_hash_bytes):
        RNS.Transport.request_path(bytes(dest_hash_bytes))

    @staticmethod
    def transport_hops_to(dest_hash_bytes):
        return RNS.Transport.hops_to(bytes(dest_hash_bytes))

    @staticmethod
    def transport_get_interfaces():
        """Returns list of dicts with interface info."""
        result = []
        for iface in RNS.Transport.interfaces:
            result.append({
                "name": str(iface),
                "online": getattr(iface, "online", True),
                "type": type(iface).__name__,
                "rxb": getattr(iface, "rxb", 0),
                "txb": getattr(iface, "txb", 0),
            })
        return result

    @staticmethod
    def transport_persist_data():
        try:
            RNS.Transport.persist_data()
        except Exception:
            log.error("Error persisting transport data: %s", traceback.format_exc())

    def get_next_hop_interface_name(self, dest_hash):
        """Return formatted interface name for next hop to destination, or None."""
        try:
            # Convert Chaquopy jarray to Python bytes for RNS dict key lookups
            if not isinstance(dest_hash, (bytes, bytearray)):
                dest_hash = bytes(dest_hash)
            if RNS.Transport.has_path(dest_hash):
                iface = RNS.Transport.next_hop_interface(dest_hash)
                if iface is None:
                    return None
                return format_interface_name(iface)
        except Exception as e:
            log_debug("RnsApi", "get_next_hop_interface_name", f"lookup failed: {e}")
        return None

    # ─── LxmfRouter ──────────────────────────────────────────────

    def init_lxmf_router(self, identity, storagepath=None):
        """Initialize LXMF router. Returns the raw router object."""
        self.lxmf_router = LXMF.LXMRouter(
            identity=identity,
            storagepath=storagepath,
        )
        return self.lxmf_router

    def lxmf_register_delivery_identity(self, identity):
        if self.lxmf_router:
            self.lxmf_router.register_delivery_identity(identity)

    def lxmf_register_delivery_callback(self, callback):
        if self.lxmf_router:
            self.lxmf_router.register_delivery_callback(callback)

    def lxmf_handle_outbound(self, message):
        if self.lxmf_router:
            self.lxmf_router.handle_outbound(message)

    def lxmf_set_outbound_propagation_node(self, dest_hash_bytes):
        if self.lxmf_router:
            if dest_hash_bytes is not None:
                self.lxmf_router.set_outbound_propagation_node(bytes(dest_hash_bytes))
            else:
                self.lxmf_router.set_outbound_propagation_node(None)

    def lxmf_get_outbound_propagation_node(self):
        if self.lxmf_router:
            return self.lxmf_router.get_outbound_propagation_node()
        return None

    def lxmf_request_messages_from_propagation_node(self, identity=None, max_messages=256):
        if self.lxmf_router:
            self.lxmf_router.request_messages_from_propagation_node(
                identity=identity,
                max_messages=max_messages,
            )

    def lxmf_get_propagation_state(self):
        """Returns dict with propagation transfer state."""
        if not self.lxmf_router:
            return {"state": 0, "state_name": "idle", "progress": 0.0, "messages_received": 0}
        try:
            state = self.lxmf_router.propagation_transfer_state
            progress = self.lxmf_router.propagation_transfer_progress
            return {
                "state": state,
                "state_name": self._propagation_state_name(state),
                "progress": float(progress) if progress else 0.0,
                "messages_received": getattr(
                    self.lxmf_router, "propagation_transfer_last_result", 0
                ) or 0,
            }
        except Exception:
            return {"state": 0, "state_name": "idle", "progress": 0.0, "messages_received": 0}

    def lxmf_get_version(self):
        try:
            return LXMF.__version__
        except Exception:
            return None

    @staticmethod
    def _propagation_state_name(state):
        names = {
            0: "idle",
            1: "path_requested",
            2: "link_establishing",
            3: "link_established",
            4: "request_sent",
            5: "receiving",
            6: "response_received",
            7: "complete",
        }
        return names.get(state, "unknown")

    # ─── LxmfMessageFactory ──────────────────────────────────────

    @staticmethod
    def create_lxmf_message(
        source_destination,
        dest_destination,
        content,
        fields=None,
        desired_method=None,
        try_propagation_on_fail=True,
    ):
        """Create an LXMF message. Returns live LXMF.LXMessage object.

        Args:
            source_destination: Live RNS.Destination (sender's delivery destination)
            dest_destination: Live RNS.Destination (recipient's delivery destination)
            content: str message content
            fields: dict of LXMF fields (optional)
            desired_method: LXMF delivery method constant (optional)
            try_propagation_on_fail: bool
        """
        if desired_method is None:
            desired_method = LXMF.LXMessage.DIRECT

        msg = LXMF.LXMessage(
            source_destination,
            dest_destination,
            content,
            desired_method=desired_method,
        )
        if fields:
            msg.fields = fields
        msg.try_propagation_on_fail = try_propagation_on_fail
        return msg

    # ─── LxmfMessage (live object methods) ────────────────────────

    @staticmethod
    def lxmf_message_get_hash(message):
        return message.hash

    @staticmethod
    def lxmf_message_get_state(message):
        """Returns int state code."""
        return message.state

    @staticmethod
    def lxmf_message_get_content(message):
        return message.content_as_string()

    @staticmethod
    def lxmf_message_get_fields(message):
        return message.fields

    @staticmethod
    def lxmf_message_get_timestamp(message):
        return message.timestamp

    @staticmethod
    def lxmf_message_get_source_hash(message):
        return message.source_hash

    @staticmethod
    def lxmf_message_get_destination_hash(message):
        return message.destination_hash

    @staticmethod
    def lxmf_message_register_delivery_callback(message, callback):
        message.register_delivery_callback(callback)

    @staticmethod
    def lxmf_message_register_failed_callback(message, callback):
        message.register_failed_callback(callback)

    # ─── LxmfAppDataParser ────────────────────────────────────────

    @staticmethod
    def display_name_from_app_data(app_data):
        """Parse display name from LXMF announce app_data."""
        try:
            return LXMF.LXMRouter.display_name_from_app_data(bytes(app_data))
        except Exception:
            return None

    @staticmethod
    def propagation_node_name_from_app_data(app_data):
        """Parse propagation node name from app_data."""
        try:
            return LXMF.LXMRouter.node_name_from_app_data(bytes(app_data))
        except Exception:
            return None

    @staticmethod
    def stamp_cost_from_app_data(app_data):
        """Parse stamp cost from app_data."""
        try:
            return LXMF.LXMRouter.stamp_cost_from_app_data(bytes(app_data))
        except Exception:
            return None

    @staticmethod
    def is_propagation_node_announce_valid(app_data):
        """Check if propagation node announce app_data is valid."""
        try:
            return LXMF.LXMRouter.propagation_node_announce_is_valid(bytes(app_data))
        except Exception:
            return False
