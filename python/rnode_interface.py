"""
Columba RNode Interface

A simplified RNode interface that uses the KotlinRNodeBridge for Bluetooth
communication. This interface implements the KISS protocol for communicating
with RNode LoRa hardware.

The KISS protocol and command structure is based on the Reticulum Network Stack
RNodeInterface implementation.
"""

import collections
import threading
import time
import RNS


class KISS:
    """KISS protocol constants and helpers."""

    # Frame delimiters
    FEND = 0xC0
    FESC = 0xDB
    TFEND = 0xDC
    TFESC = 0xDD

    # Commands
    CMD_UNKNOWN = 0xFE
    CMD_DATA = 0x00
    CMD_FREQUENCY = 0x01
    CMD_BANDWIDTH = 0x02
    CMD_TXPOWER = 0x03
    CMD_SF = 0x04
    CMD_CR = 0x05
    CMD_RADIO_STATE = 0x06
    CMD_RADIO_LOCK = 0x07
    CMD_DETECT = 0x08
    CMD_LEAVE = 0x0A
    CMD_ST_ALOCK = 0x0B
    CMD_LT_ALOCK = 0x0C
    CMD_READY = 0x0F
    CMD_STAT_RX = 0x21
    CMD_STAT_TX = 0x22
    CMD_STAT_RSSI = 0x23
    CMD_STAT_SNR = 0x24
    CMD_STAT_CHTM = 0x25
    CMD_STAT_PHYPRM = 0x26
    CMD_STAT_BAT = 0x27
    CMD_BLINK = 0x30
    CMD_RANDOM = 0x40
    CMD_BT_CTRL = 0x46
    CMD_PLATFORM = 0x48
    CMD_MCU = 0x49
    CMD_FW_VERSION = 0x50
    CMD_RESET = 0x55
    CMD_ERROR = 0x90

    # External framebuffer (display)
    CMD_FB_EXT = 0x41      # Enable/disable external framebuffer
    CMD_FB_WRITE = 0x43    # Write framebuffer data

    # Framebuffer constants
    FB_BYTES_PER_LINE = 8  # 64 pixels / 8 bits per byte

    # Detection
    DETECT_REQ = 0x73
    DETECT_RESP = 0x46

    # Radio state
    RADIO_STATE_OFF = 0x00
    RADIO_STATE_ON = 0x01
    RADIO_STATE_ASK = 0xFF

    # Platforms
    PLATFORM_AVR = 0x90
    PLATFORM_ESP32 = 0x80
    PLATFORM_NRF52 = 0x70

    # Errors
    ERROR_INITRADIO = 0x01
    ERROR_TXFAILED = 0x02
    ERROR_QUEUE_FULL = 0x04
    ERROR_INVALID_CONFIG = 0x40

    # Human-readable error messages
    ERROR_MESSAGES = {
        0x01: "Radio initialization failed",
        0x02: "Transmission failed",
        0x04: "Data queue overflowed",
        0x40: (
            "Invalid configuration - TX power may exceed device limits. "
            "Try reducing TX power (common limits: SX1262=22dBm, SX1276=17dBm)"
        ),
    }

    @staticmethod
    def get_error_message(error_code):
        """Get human-readable error message for error code."""
        return KISS.ERROR_MESSAGES.get(error_code, f"Unknown error (0x{error_code:02X})")

    @staticmethod
    def escape(data):
        """Escape special bytes in KISS data."""
        data = data.replace(bytes([0xDB]), bytes([0xDB, 0xDD]))
        data = data.replace(bytes([0xC0]), bytes([0xDB, 0xDC]))
        return data

    @staticmethod
    def unescape(data):
        """
        Unescape KISS data.

        Handles escape sequences:
        - 0xDB 0xDC -> 0xC0 (FEND)
        - 0xDB 0xDD -> 0xDB (FESC)
        - Invalid escape (0xDB followed by other) -> skipped entirely
        - Trailing 0xDB -> skipped
        """
        result = bytearray()
        i = 0
        while i < len(data):
            if data[i] == 0xDB:  # FESC - escape character
                if i + 1 >= len(data):
                    # Trailing FESC at end of data - skip it
                    break
                next_byte = data[i + 1]
                if next_byte == 0xDC:
                    result.append(0xC0)  # TFEND -> FEND
                    i += 2
                elif next_byte == 0xDD:
                    result.append(0xDB)  # TFESC -> FESC
                    i += 2
                else:
                    # Invalid escape sequence - skip both bytes
                    i += 2
            else:
                result.append(data[i])
                i += 1
        return bytes(result)


class ColumbaRNodeInterface:
    """
    RNode interface for Columba using KotlinRNodeBridge.

    This interface handles KISS protocol communication with RNode hardware
    over Bluetooth Classic (SPP/RFCOMM) or Bluetooth Low Energy (BLE GATT).
    """

    # Validation limits
    FREQ_MIN = 137000000
    FREQ_MAX = 3000000000

    # Required firmware version
    REQUIRED_FW_VER_MAJ = 1
    REQUIRED_FW_VER_MIN = 52

    # Timeouts
    DETECT_TIMEOUT = 5.0
    CONFIG_DELAY = 0.15

    # Connection modes
    MODE_CLASSIC = "classic"  # Bluetooth Classic (SPP/RFCOMM)
    MODE_BLE = "ble"          # Bluetooth Low Energy (GATT)

    def __init__(self, owner, name, config):
        """
        Initialize the RNode interface.

        Args:
            owner: The Reticulum instance
            name: Interface name
            config: Configuration dictionary with:
                - target_device_name: Bluetooth device name (e.g., "RNode 5A3F")
                - connection_mode: "classic" or "ble" (default: "classic")
                - frequency: LoRa frequency in Hz
                - bandwidth: LoRa bandwidth in Hz
                - tx_power: Transmission power in dBm
                - spreading_factor: LoRa spreading factor (5-12)
                - coding_rate: LoRa coding rate (5-8)
                - st_alock: Short-term airtime limit (optional)
                - lt_alock: Long-term airtime limit (optional)
        """
        self.owner = owner
        self.name = name
        self.online = False
        self.detached = False
        self.detected = False
        self.firmware_ok = False
        self.interface_ready = False

        # Standard RNS interface attributes
        self.IN = True
        self.OUT = True
        self.bitrate = 10000  # Approximate LoRa bitrate (varies with SF/BW)
        self.rxb = 0  # Received bytes counter
        self.txb = 0  # Transmitted bytes counter
        self.held_announces = []  # Held announces for processing
        self.announce_allowed_at = 0  # Timestamp when next announce is allowed
        self.announce_cap = RNS.Reticulum.ANNOUNCE_CAP  # Announce rate cap
        self.oa_freq_deque = collections.deque(maxlen=16)  # Outgoing announce frequency tracking
        self.ia_freq_deque = collections.deque(maxlen=16)  # Incoming announce frequency tracking
        self.announce_rate_target = None  # Target announce rate (None = no specific target)
        self.announce_rate_grace = 0  # Grace period for announce rate limiting
        self.announce_rate_penalty = 0  # Penalty for exceeding announce rate
        self.ifac_size = 16  # Interface authentication code size
        self.ifac_netname = None  # Network name for IFAC
        self.ifac_netkey = None  # Network key for IFAC
        self.AUTOCONFIGURE_MTU = False  # Whether to autoconfigure MTU
        self.FIXED_MTU = True  # Whether MTU is fixed (not dynamically adjusted)
        # IMPORTANT: HW_MTU must NOT be None!
        # When HW_MTU is None, RNS Transport truncates packet.data by 3 bytes before
        # computing link_id in Link.validate_request(). This causes the receiver to
        # compute a different link_id than the sender, causing link establishment to fail.
        # Setting HW_MTU to 500 (LoRa typical MTU) prevents this truncation.
        self.HW_MTU = 500  # Hardware MTU for LoRa
        self.mtu = RNS.Reticulum.MTU  # Maximum transmission unit

        # Set interface mode from config
        mode_str = config.get("mode", "full")
        if mode_str == "full":
            self.mode = RNS.Interfaces.Interface.Interface.MODE_FULL
        elif mode_str == "gateway":
            self.mode = RNS.Interfaces.Interface.Interface.MODE_GATEWAY
        elif mode_str == "access_point":
            self.mode = RNS.Interfaces.Interface.Interface.MODE_ACCESS_POINT
        elif mode_str == "roaming":
            self.mode = RNS.Interfaces.Interface.Interface.MODE_ROAMING
        elif mode_str == "boundary":
            self.mode = RNS.Interfaces.Interface.Interface.MODE_BOUNDARY
        else:
            self.mode = RNS.Interfaces.Interface.Interface.MODE_FULL

        # Get Kotlin bridge from wrapper
        self.kotlin_bridge = None
        self._get_kotlin_bridge()

        # Configuration
        self.target_device_name = config.get("target_device_name")
        self.connection_mode = config.get("connection_mode", self.MODE_CLASSIC)
        self.frequency = config.get("frequency", 915000000)
        self.bandwidth = config.get("bandwidth", 125000)
        self.txpower = config.get("tx_power", 7)
        self.sf = config.get("spreading_factor", 7)
        self.cr = config.get("coding_rate", 5)
        self.st_alock = config.get("st_alock")
        self.lt_alock = config.get("lt_alock")

        # State tracking
        self.state = KISS.RADIO_STATE_OFF
        self.platform = None
        self.mcu = None
        self.maj_version = 0
        self.min_version = 0

        # Radio state readback
        self.r_frequency = None
        self.r_bandwidth = None
        self.r_txpower = None
        self.r_sf = None
        self.r_cr = None
        self.r_state = None
        self.r_stat_rssi = None
        self.r_stat_snr = None

        # External framebuffer (display) settings
        self.enable_framebuffer = config.get("enable_framebuffer", False)
        self.framebuffer_enabled = False

        # Read thread
        self._read_thread = None
        self._running = threading.Event()  # Thread-safe flag for read loop control
        self._read_lock = threading.Lock()

        # Auto-reconnection
        self._reconnect_thread = None
        self._reconnecting = False
        self._max_reconnect_attempts = 30  # Try for ~5 minutes (30 * 10s)
        self._reconnect_interval = 10.0  # Seconds between reconnection attempts

        # Error callback for surfacing RNode errors to UI
        self._on_error_callback = None
        # Online status change callback for UI refresh
        self._on_online_status_changed = None

        # Validate configuration
        self._validate_config()

        RNS.log(f"ColumbaRNodeInterface '{name}' initialized", RNS.LOG_DEBUG)

    def _get_kotlin_bridge(self):
        """Get the Kotlin RNode bridge from the wrapper."""
        try:
            import reticulum_wrapper
            wrapper = reticulum_wrapper._global_wrapper_instance
            if wrapper and hasattr(wrapper, 'kotlin_rnode_bridge'):
                self.kotlin_bridge = wrapper.kotlin_rnode_bridge
                RNS.log("Got KotlinRNodeBridge from wrapper", RNS.LOG_DEBUG)
            else:
                RNS.log("KotlinRNodeBridge not available in wrapper", RNS.LOG_ERROR)
        except Exception as e:
            RNS.log(f"Failed to get KotlinRNodeBridge: {e}", RNS.LOG_ERROR)

    def _validate_config(self):
        """Validate configuration parameters."""
        if self.frequency < self.FREQ_MIN or self.frequency > self.FREQ_MAX:
            raise ValueError(f"Invalid frequency: {self.frequency}")

        # Max TX power varies by region (up to 36 dBm for NZ 865)
        # The RNode firmware will validate against actual hardware limits
        # and return error 0x40 if TX power exceeds device capability
        if self.txpower < 0 or self.txpower > 36:
            raise ValueError(f"Invalid TX power: {self.txpower}")

        if self.bandwidth < 7800 or self.bandwidth > 1625000:
            raise ValueError(f"Invalid bandwidth: {self.bandwidth}")

        if self.sf < 5 or self.sf > 12:
            raise ValueError(f"Invalid spreading factor: {self.sf}")

        if self.cr < 5 or self.cr > 8:
            raise ValueError(f"Invalid coding rate: {self.cr}")

        if self.st_alock is not None and (self.st_alock < 0.0 or self.st_alock > 100.0):
            raise ValueError(f"Invalid short-term airtime limit: {self.st_alock}")

        if self.lt_alock is not None and (self.lt_alock < 0.0 or self.lt_alock > 100.0):
            raise ValueError(f"Invalid long-term airtime limit: {self.lt_alock}")

    def start(self):
        """Start the interface - connect to RNode and configure radio."""
        if self.kotlin_bridge is None:
            RNS.log("Cannot start - KotlinRNodeBridge not available", RNS.LOG_ERROR)
            return False

        if not self.target_device_name:
            RNS.log("Cannot start - no target device name configured", RNS.LOG_ERROR)
            return False

        mode_str = "BLE" if self.connection_mode == self.MODE_BLE else "Bluetooth Classic"
        RNS.log(f"Connecting to RNode '{self.target_device_name}' via {mode_str}...", RNS.LOG_INFO)

        # Connect via Kotlin bridge with specified mode
        if not self.kotlin_bridge.connect(self.target_device_name, self.connection_mode):
            RNS.log(f"Failed to connect to {self.target_device_name}", RNS.LOG_ERROR)
            return False

        # Set up data callback
        self.kotlin_bridge.setOnDataReceived(self._on_data_received)
        self.kotlin_bridge.setOnConnectionStateChanged(self._on_connection_state_changed)

        # Start read thread
        self._running.set()
        self._read_thread = threading.Thread(target=self._read_loop, daemon=True)
        self._read_thread.start()

        # Configure device
        try:
            time.sleep(1.5)  # Allow BLE connection to fully stabilize
            self._configure_device()
            return True
        except Exception as e:
            RNS.log(f"Failed to configure RNode: {e}", RNS.LOG_ERROR)
            self.stop()
            return False

    def stop(self):
        """Stop the interface and disconnect."""
        self._running.clear()
        self._reconnecting = False  # Stop any reconnection attempts
        self._set_online(False)

        if self.kotlin_bridge:
            self.kotlin_bridge.disconnect()

        if self._read_thread:
            self._read_thread.join(timeout=2.0)

        if self._reconnect_thread:
            self._reconnect_thread.join(timeout=2.0)

        RNS.log(f"RNode interface '{self.name}' stopped", RNS.LOG_INFO)

    def detach(self):
        """Detach interface from transport. Called during Reticulum shutdown."""
        RNS.log(f"Detaching RNode interface '{self.name}'", RNS.LOG_DEBUG)
        self.stop()

    def _configure_device(self):
        """Detect and configure the RNode."""
        # Send detect command
        self._detect()

        # Wait for detection response
        start_time = time.time()
        while not self.detected and (time.time() - start_time) < self.DETECT_TIMEOUT:
            time.sleep(0.1)

        if not self.detected:
            raise IOError("Could not detect RNode device")

        if not self.firmware_ok:
            raise IOError(f"Invalid firmware version: {self.maj_version}.{self.min_version}")

        RNS.log(f"RNode detected: platform={hex(self.platform or 0)}, "
                f"firmware={self.maj_version}.{self.min_version}", RNS.LOG_INFO)

        # Configure radio parameters
        RNS.log("Configuring RNode radio...", RNS.LOG_VERBOSE)
        self._init_radio()

        # Validate configuration
        if self._validate_radio_state():
            self.interface_ready = True
            self._set_online(True)
            RNS.log(f"RNode '{self.name}' is online", RNS.LOG_INFO)

            # Display Columba logo on RNode if enabled
            self._display_logo()
        else:
            raise IOError("Radio configuration validation failed")

    def _detect(self):
        """Send detect command to RNode."""
        # Send detect command - each KISS frame needs FEND at start and end
        kiss_command = bytes([
            KISS.FEND, KISS.CMD_DETECT, KISS.DETECT_REQ, KISS.FEND,
            KISS.FEND, KISS.CMD_FW_VERSION, 0x00, KISS.FEND,
            KISS.FEND, KISS.CMD_PLATFORM, 0x00, KISS.FEND,
            KISS.FEND, KISS.CMD_MCU, 0x00, KISS.FEND
        ])
        RNS.log(f"Sending detect command: {kiss_command.hex()}", RNS.LOG_DEBUG)
        self._write(kiss_command)

    def _init_radio(self):
        """Initialize radio with configured parameters."""
        self._set_frequency()
        time.sleep(self.CONFIG_DELAY)

        self._set_bandwidth()
        time.sleep(self.CONFIG_DELAY)

        self._set_tx_power()
        time.sleep(self.CONFIG_DELAY)

        self._set_spreading_factor()
        time.sleep(self.CONFIG_DELAY)

        self._set_coding_rate()
        time.sleep(self.CONFIG_DELAY)

        if self.st_alock is not None:
            self._set_st_alock()
            time.sleep(self.CONFIG_DELAY)

        if self.lt_alock is not None:
            self._set_lt_alock()
            time.sleep(self.CONFIG_DELAY)

        self._set_radio_state(KISS.RADIO_STATE_ON)
        time.sleep(self.CONFIG_DELAY)

    def _set_frequency(self):
        """Set radio frequency."""
        c1 = (self.frequency >> 24) & 0xFF
        c2 = (self.frequency >> 16) & 0xFF
        c3 = (self.frequency >> 8) & 0xFF
        c4 = self.frequency & 0xFF
        data = KISS.escape(bytes([c1, c2, c3, c4]))
        kiss_command = bytes([KISS.FEND, KISS.CMD_FREQUENCY]) + data + bytes([KISS.FEND])
        self._write(kiss_command)

    def _set_bandwidth(self):
        """Set radio bandwidth."""
        c1 = (self.bandwidth >> 24) & 0xFF
        c2 = (self.bandwidth >> 16) & 0xFF
        c3 = (self.bandwidth >> 8) & 0xFF
        c4 = self.bandwidth & 0xFF
        data = KISS.escape(bytes([c1, c2, c3, c4]))
        kiss_command = bytes([KISS.FEND, KISS.CMD_BANDWIDTH]) + data + bytes([KISS.FEND])
        self._write(kiss_command)

    def _set_tx_power(self):
        """Set TX power."""
        kiss_command = bytes([KISS.FEND, KISS.CMD_TXPOWER, self.txpower, KISS.FEND])
        self._write(kiss_command)

    def _set_spreading_factor(self):
        """Set spreading factor."""
        kiss_command = bytes([KISS.FEND, KISS.CMD_SF, self.sf, KISS.FEND])
        self._write(kiss_command)

    def _set_coding_rate(self):
        """Set coding rate."""
        kiss_command = bytes([KISS.FEND, KISS.CMD_CR, self.cr, KISS.FEND])
        self._write(kiss_command)

    def _set_st_alock(self):
        """Set short-term airtime lock."""
        at = int(self.st_alock * 100)
        c1 = (at >> 8) & 0xFF
        c2 = at & 0xFF
        data = KISS.escape(bytes([c1, c2]))
        kiss_command = bytes([KISS.FEND, KISS.CMD_ST_ALOCK]) + data + bytes([KISS.FEND])
        self._write(kiss_command)

    def _set_lt_alock(self):
        """Set long-term airtime lock."""
        at = int(self.lt_alock * 100)
        c1 = (at >> 8) & 0xFF
        c2 = at & 0xFF
        data = KISS.escape(bytes([c1, c2]))
        kiss_command = bytes([KISS.FEND, KISS.CMD_LT_ALOCK]) + data + bytes([KISS.FEND])
        self._write(kiss_command)

    def _set_radio_state(self, state):
        """Set radio state (on/off)."""
        self.state = state
        kiss_command = bytes([KISS.FEND, KISS.CMD_RADIO_STATE, state, KISS.FEND])
        self._write(kiss_command)

    def _validate_radio_state(self):
        """Validate that radio state matches configuration."""
        # Wait a moment for state to be reported back
        time.sleep(0.3)

        # Read radio state under lock for thread safety (Issue 3)
        # The read loop updates these from a background thread
        with self._read_lock:
            r_frequency = self.r_frequency
            r_bandwidth = self.r_bandwidth
            r_sf = self.r_sf
            r_cr = self.r_cr
            r_state = self.r_state

        # Check if we got the expected values back
        if r_frequency is not None and r_frequency != self.frequency:
            RNS.log(f"Frequency mismatch: configured={self.frequency}, reported={r_frequency}", RNS.LOG_ERROR)
            return False

        if r_bandwidth is not None and r_bandwidth != self.bandwidth:
            RNS.log(f"Bandwidth mismatch: configured={self.bandwidth}, reported={r_bandwidth}", RNS.LOG_ERROR)
            return False

        if r_sf is not None and r_sf != self.sf:
            RNS.log(f"SF mismatch: configured={self.sf}, reported={r_sf}", RNS.LOG_ERROR)
            return False

        if r_cr is not None and r_cr != self.cr:
            RNS.log(f"CR mismatch: configured={self.cr}, reported={r_cr}", RNS.LOG_ERROR)
            return False

        if r_state != KISS.RADIO_STATE_ON:
            RNS.log(f"Radio state not ON: {r_state}", RNS.LOG_ERROR)
            return False

        return True

    # Exponential backoff delays for write retries (in seconds)
    WRITE_BACKOFF_DELAYS = [0.3, 1.0, 3.0]

    def _write(self, data, max_retries=3):
        """Write data to the RNode via Kotlin bridge with exponential backoff retry."""
        if self.kotlin_bridge is None:
            raise IOError("Kotlin bridge not available")

        last_error = None
        for attempt in range(max_retries):
            written = self.kotlin_bridge.writeSync(data)
            if written == len(data):
                return  # Success

            last_error = f"expected {len(data)}, wrote {written}"
            if attempt < max_retries - 1:
                # Use exponential backoff delay (0.3s, 1.0s, 3.0s, ...)
                delay = self.WRITE_BACKOFF_DELAYS[min(attempt, len(self.WRITE_BACKOFF_DELAYS) - 1)]
                RNS.log(f"Write attempt {attempt + 1} failed ({last_error}), retrying in {delay}s...", RNS.LOG_WARNING)
                time.sleep(delay)

        raise IOError(f"Write failed after {max_retries} attempts: {last_error}")

    # -------------------------------------------------------------------------
    # External Framebuffer (Display) Methods
    # -------------------------------------------------------------------------

    def enable_external_framebuffer(self):
        """Enable external framebuffer mode on RNode display."""
        kiss_command = bytes([KISS.FEND, KISS.CMD_FB_EXT, 0x01, KISS.FEND])
        self._write(kiss_command)
        self.framebuffer_enabled = True
        RNS.log(f"{self} External framebuffer enabled", RNS.LOG_DEBUG)

    def disable_external_framebuffer(self):
        """Disable external framebuffer, return to normal RNode UI."""
        kiss_command = bytes([KISS.FEND, KISS.CMD_FB_EXT, 0x00, KISS.FEND])
        self._write(kiss_command)
        self.framebuffer_enabled = False
        RNS.log(f"{self} External framebuffer disabled", RNS.LOG_DEBUG)

    def write_framebuffer(self, line, line_data):
        """Write 8 bytes of pixel data to a specific line (0-63).

        Args:
            line: Line number (0-63)
            line_data: 8 bytes of pixel data (64 pixels, 1 bit per pixel)
        """
        if line < 0 or line > 63:
            raise ValueError(f"Line must be 0-63, got {line}")
        if len(line_data) != KISS.FB_BYTES_PER_LINE:
            raise ValueError(f"Line data must be {KISS.FB_BYTES_PER_LINE} bytes")

        data = bytes([line]) + line_data
        escaped = KISS.escape(data)
        kiss_command = bytes([KISS.FEND, KISS.CMD_FB_WRITE]) + escaped + bytes([KISS.FEND])
        self._write(kiss_command)

    def display_image(self, imagedata):
        """Send a 64x64 monochrome image to RNode display.

        Args:
            imagedata: List or bytes of 512 bytes (64 lines x 8 bytes per line)
        """
        if len(imagedata) != 512:
            raise ValueError(f"Image data must be 512 bytes, got {len(imagedata)}")

        for line in range(64):
            line_start = line * KISS.FB_BYTES_PER_LINE
            line_end = line_start + KISS.FB_BYTES_PER_LINE
            line_data = bytes(imagedata[line_start:line_end])
            self.write_framebuffer(line, line_data)
            # Small delay to prevent BLE write throttling
            time.sleep(0.015)

        RNS.log(f"{self} Sent 64x64 image to RNode framebuffer", RNS.LOG_DEBUG)

    def _display_logo(self):
        """Display or disable the Columba logo on RNode based on settings."""
        if self.enable_framebuffer:
            try:
                from columba_logo import columba_fb_data
                self.display_image(columba_fb_data)
                # Delay before enable command to ensure framebuffer data is processed
                time.sleep(0.05)
                self.enable_external_framebuffer()
                RNS.log(f"{self} Displayed Columba logo on RNode", RNS.LOG_DEBUG)
            except ImportError:
                RNS.log(f"{self} columba_logo module not found, skipping logo display", RNS.LOG_WARNING)
            except Exception as e:
                RNS.log(f"{self} Failed to display logo: {e}", RNS.LOG_WARNING)
        else:
            # Explicitly disable external framebuffer to restore normal RNode UI
            try:
                self.disable_external_framebuffer()
                RNS.log(f"{self} Disabled external framebuffer on RNode", RNS.LOG_DEBUG)
            except Exception as e:
                RNS.log(f"{self} Failed to disable framebuffer: {e}", RNS.LOG_WARNING)

    def _read_loop(self):
        """Background thread for reading and parsing KISS frames."""
        in_frame = False
        escape = False
        command = KISS.CMD_UNKNOWN
        data_buffer = b""

        RNS.log("RNode read loop started", RNS.LOG_DEBUG)

        while self._running.is_set():
            try:
                # Read available data
                raw_data = self.kotlin_bridge.read()
                # Convert to bytes if needed (Chaquopy may return jarray)
                if hasattr(raw_data, '__len__'):
                    data = bytes(raw_data)
                else:
                    data = bytes(raw_data) if raw_data else b""

                if len(data) == 0:
                    time.sleep(0.01)
                    continue

                # Parse KISS frames
                RNS.log(f"RNode parsing {len(data)} bytes: {data.hex()}", RNS.LOG_DEBUG)
                for byte in data:
                    if in_frame and byte == KISS.FEND and command == KISS.CMD_DATA:
                        # End of data frame
                        in_frame = False
                        self._process_incoming(data_buffer)
                        data_buffer = b""
                    elif byte == KISS.FEND:
                        # Start of frame
                        in_frame = True
                        command = KISS.CMD_UNKNOWN
                        data_buffer = b""
                    elif in_frame and len(data_buffer) < 512:
                        if escape:
                            if byte == KISS.TFEND:
                                data_buffer += bytes([KISS.FEND])
                            elif byte == KISS.TFESC:
                                data_buffer += bytes([KISS.FESC])
                            else:
                                # Invalid escape sequence - FESC should only be followed by TFEND or TFESC
                                RNS.log(f"Invalid KISS escape sequence: FESC followed by 0x{byte:02X}", RNS.LOG_WARNING)
                                data_buffer += bytes([byte])
                            escape = False
                        elif byte == KISS.FESC:
                            escape = True
                        elif command == KISS.CMD_UNKNOWN:
                            command = byte
                        elif command == KISS.CMD_DATA:
                            data_buffer += bytes([byte])
                        elif command == KISS.CMD_FREQUENCY:
                            if len(data_buffer) < 4:
                                data_buffer += bytes([byte])
                                if len(data_buffer) == 4:
                                    freq = (data_buffer[0] << 24) | (data_buffer[1] << 16) | (data_buffer[2] << 8) | data_buffer[3]
                                    with self._read_lock:
                                        self.r_frequency = freq
                                    RNS.log(f"RNode frequency: {freq}", RNS.LOG_DEBUG)
                        elif command == KISS.CMD_BANDWIDTH:
                            if len(data_buffer) < 4:
                                data_buffer += bytes([byte])
                                if len(data_buffer) == 4:
                                    bw = (data_buffer[0] << 24) | (data_buffer[1] << 16) | (data_buffer[2] << 8) | data_buffer[3]
                                    with self._read_lock:
                                        self.r_bandwidth = bw
                                    RNS.log(f"RNode bandwidth: {bw}", RNS.LOG_DEBUG)
                        elif command == KISS.CMD_TXPOWER:
                            with self._read_lock:
                                self.r_txpower = byte
                            RNS.log(f"RNode TX power: {byte}", RNS.LOG_DEBUG)
                        elif command == KISS.CMD_SF:
                            with self._read_lock:
                                self.r_sf = byte
                            RNS.log(f"RNode SF: {byte}", RNS.LOG_DEBUG)
                        elif command == KISS.CMD_CR:
                            with self._read_lock:
                                self.r_cr = byte
                            RNS.log(f"RNode CR: {byte}", RNS.LOG_DEBUG)
                        elif command == KISS.CMD_RADIO_STATE:
                            with self._read_lock:
                                self.r_state = byte
                            RNS.log(f"RNode radio state: {byte}", RNS.LOG_DEBUG)
                        elif command == KISS.CMD_STAT_RSSI:
                            with self._read_lock:
                                self.r_stat_rssi = byte - 157  # RSSI offset
                        elif command == KISS.CMD_STAT_SNR:
                            with self._read_lock:
                                self.r_stat_snr = int.from_bytes([byte], "big", signed=True) / 4.0
                        elif command == KISS.CMD_FW_VERSION:
                            if len(data_buffer) < 2:
                                data_buffer += bytes([byte])
                                if len(data_buffer) == 2:
                                    self.maj_version = data_buffer[0]
                                    self.min_version = data_buffer[1]
                                    self._validate_firmware()
                        elif command == KISS.CMD_PLATFORM:
                            self.platform = byte
                        elif command == KISS.CMD_MCU:
                            self.mcu = byte
                        elif command == KISS.CMD_DETECT:
                            if byte == KISS.DETECT_RESP:
                                self.detected = True
                                RNS.log("RNode detected!", RNS.LOG_DEBUG)
                        elif command == KISS.CMD_ERROR:
                            error_message = KISS.get_error_message(byte)
                            RNS.log(f"RNode error (0x{byte:02X}): {error_message}", RNS.LOG_ERROR)
                            # Surface error to UI via callback
                            if self._on_error_callback:
                                try:
                                    self._on_error_callback(byte, error_message)
                                except Exception as cb_err:
                                    RNS.log(f"Error callback failed: {cb_err}", RNS.LOG_ERROR)
                        elif command == KISS.CMD_READY:
                            pass  # Device ready

            except Exception as e:
                if self._running.is_set():
                    RNS.log(f"Read loop error: {e}", RNS.LOG_ERROR)
                    time.sleep(0.1)

        RNS.log("RNode read loop stopped", RNS.LOG_DEBUG)

    def _validate_firmware(self):
        """Check if firmware version is acceptable."""
        if self.maj_version > self.REQUIRED_FW_VER_MAJ:
            self.firmware_ok = True
        elif self.maj_version == self.REQUIRED_FW_VER_MAJ and self.min_version >= self.REQUIRED_FW_VER_MIN:
            self.firmware_ok = True
        else:
            self.firmware_ok = False
            RNS.log(f"Firmware version {self.maj_version}.{self.min_version} is below required "
                    f"{self.REQUIRED_FW_VER_MAJ}.{self.REQUIRED_FW_VER_MIN}", RNS.LOG_WARNING)

    def _process_incoming(self, data):
        """Process incoming data frame from RNode."""
        if len(data) > 0 and self.online:
            # Update receive counter
            self.rxb += len(data)
            # Pass to Reticulum Transport for processing
            RNS.Transport.inbound(data, self)
            RNS.log(f"RNode received {len(data)} bytes", RNS.LOG_DEBUG)

    def _on_data_received(self, data):
        """Callback from Kotlin bridge when data is received."""
        # Data is already being processed in _read_loop via polling
        # This callback is for future async implementation
        pass

    def _on_connection_state_changed(self, connected, device_name):
        """Callback when Bluetooth connection state changes."""
        if connected:
            RNS.log(f"RNode connected: {device_name}", RNS.LOG_INFO)
            # Stop any reconnection attempts if we're now connected
            self._reconnecting = False
        else:
            RNS.log(f"RNode disconnected: {device_name}", RNS.LOG_WARNING)
            self._set_online(False)
            self.detected = False
            # Start auto-reconnection if not already reconnecting
            self._start_reconnection_loop()

    def setOnErrorReceived(self, callback):
        """
        Set callback for RNode error events.

        The callback will be called when the RNode reports an error,
        with signature: callback(error_code: int, error_message: str)

        @param callback: Callable that receives (error_code, error_message)
        """
        self._on_error_callback = callback

    def setOnOnlineStatusChanged(self, callback):
        """
        Set callback for online status change events.

        The callback will be called when the interface's online status changes,
        with signature: callback(is_online: bool)

        This enables event-driven UI updates when the RNode connects/disconnects.

        @param callback: Callable that receives (is_online)
        """
        self._on_online_status_changed = callback

    def _set_online(self, is_online):
        """
        Set online status and notify callback if status changed.

        Thread-safe: Uses _read_lock to synchronize with process_outgoing().

        @param is_online: New online status
        """
        with self._read_lock:
            old_status = self.online
            self.online = is_online
        if old_status != is_online and self._on_online_status_changed:
            try:
                self._on_online_status_changed(is_online)
            except Exception as e:
                RNS.log(f"Error in online status callback: {e}", RNS.LOG_ERROR)

    def _start_reconnection_loop(self):
        """Start a background thread to attempt reconnection."""
        if self._reconnecting:
            RNS.log("Reconnection already in progress", RNS.LOG_DEBUG)
            return

        self._reconnecting = True
        self._reconnect_thread = threading.Thread(target=self._reconnection_loop, daemon=True)
        self._reconnect_thread.start()
        RNS.log(f"Started auto-reconnection loop for {self.target_device_name}", RNS.LOG_INFO)

    def _reconnection_loop(self):
        """Background thread that attempts to reconnect to the RNode."""
        attempt = 0
        while self._reconnecting and attempt < self._max_reconnect_attempts:
            attempt += 1
            RNS.log(f"Reconnection attempt {attempt}/{self._max_reconnect_attempts} for {self.target_device_name}...", RNS.LOG_INFO)

            try:
                if self.start():
                    RNS.log(f"✅ Successfully reconnected to {self.target_device_name}", RNS.LOG_INFO)
                    self._reconnecting = False
                    return
                else:
                    RNS.log(f"Reconnection attempt {attempt} failed, will retry in {self._reconnect_interval}s", RNS.LOG_WARNING)
            except Exception as e:
                RNS.log(f"Reconnection attempt {attempt} error: {e}", RNS.LOG_ERROR)

            # Wait before next attempt (but check if we should stop)
            for _ in range(int(self._reconnect_interval * 10)):
                if not self._reconnecting:
                    return
                time.sleep(0.1)

        if self._reconnecting:
            RNS.log(f"❌ Failed to reconnect to {self.target_device_name} after {attempt} attempts", RNS.LOG_ERROR)
            self._reconnecting = False

    def process_held_announces(self):
        """Process any held announces. Required by RNS Transport."""
        # Process and clear held announces
        for announce in self.held_announces:
            try:
                RNS.Transport.inbound(announce, self)
            except Exception as e:
                RNS.log(f"Error processing held announce: {e}", RNS.LOG_ERROR)
        self.held_announces = []

    def sent_announce(self, from_spawned=False):
        """Called when an announce is sent on this interface. Tracks announce frequency."""
        self.oa_freq_deque.append(time.time())

    def received_announce(self):
        """Called when an announce is received on this interface. Tracks announce frequency."""
        self.ia_freq_deque.append(time.time())

    def should_ingress_limit(self):
        """Check if ingress limiting should be applied. Required by RNS Transport."""
        return False

    def process_outgoing(self, data):
        """Send data through the RNode interface."""
        # Thread-safe check of online status (synchronized with _set_online)
        with self._read_lock:
            is_online = self.online
        if not is_online:
            RNS.log("Cannot send - interface is offline", RNS.LOG_WARNING)
            return

        # KISS-frame the data
        escaped_data = KISS.escape(data)
        kiss_frame = bytes([KISS.FEND, KISS.CMD_DATA]) + escaped_data + bytes([KISS.FEND])

        try:
            self._write(kiss_frame)
            # Update transmit counter
            self.txb += len(data)
            RNS.log(f"RNode sent {len(data)} bytes", RNS.LOG_DEBUG)
        except Exception as e:
            RNS.log(f"Failed to send data: {e}", RNS.LOG_ERROR)

    def get_rssi(self):
        """Get last received signal strength."""
        with self._read_lock:
            return self.r_stat_rssi

    def get_snr(self):
        """Get last received signal-to-noise ratio."""
        with self._read_lock:
            return self.r_stat_snr

    def __str__(self):
        return f"ColumbaRNodeInterface[{self.name}]"
