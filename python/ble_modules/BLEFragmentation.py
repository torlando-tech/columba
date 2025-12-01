# MIT License
#
# Copyright (c) 2025 Reticulum BLE Interface Contributors
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

"""
BLE Fragmentation Protocol

Handles fragmentation and reassembly of Reticulum packets for BLE transport.

BLE has MTU limitations (typically 20-512 bytes) while Reticulum packets
can be up to 500 bytes. This module splits packets into fragments with
headers for reassembly.

Fragment Header Format (5 bytes):
    [Type: 1 byte][Sequence: 2 bytes][Total: 2 bytes][Data: variable]

Fragment Types:
    0x01 = START    - First fragment
    0x02 = CONTINUE - Middle fragment
    0x03 = END      - Last fragment
"""

import time
import struct

# Import RNS for logging
try:
    import RNS
except ImportError:
    # Fallback for testing without RNS
    RNS = None


class BLEFragmenter:
    """
    Fragments Reticulum packets into BLE-sized chunks.

    Each fragment includes a header with type, sequence number, and total
    fragment count to enable reassembly on the receiving end.
    """

    # Fragment types
    TYPE_START = 0x01
    TYPE_CONTINUE = 0x02
    TYPE_END = 0x03

    # Header size
    HEADER_SIZE = 5  # 1 byte type + 2 bytes sequence + 2 bytes total

    def __init__(self, mtu=185):
        """
        Initialize fragmenter.

        Args:
            mtu: Maximum transmission unit for BLE (default 185 for BLE 4.2)
        """
        self.mtu = max(mtu, 20)  # Minimum 20 bytes for BLE
        # Data payload per fragment = MTU - header
        self.payload_size = self.mtu - self.HEADER_SIZE

        if self.payload_size < 1:
            raise ValueError(f"MTU {mtu} too small for fragmentation (min {self.HEADER_SIZE + 1})")

    def fragment_packet(self, packet):
        """
        Split a Reticulum packet into BLE fragments.

        Args:
            packet: bytes, the full Reticulum packet

        Returns:
            list of bytes, each element is one BLE fragment with header + data
        """
        if not isinstance(packet, bytes):
            raise TypeError("Packet must be bytes")

        if len(packet) == 0:
            raise ValueError("Cannot fragment empty packet")

        packet_size = len(packet)

        # Calculate number of fragments needed
        num_fragments = (packet_size + self.payload_size - 1) // self.payload_size

        # MEDIUM #10: Check for sequence number wraparound (16-bit limit: 0-65535)
        # Maximum packet size = 65535 * (MTU - 5)
        # For MTU=185: max packet = 65535 * 180 = 11,796,300 bytes (~11MB)
        # This should be sufficient for Reticulum's use case (typical packets < 500 bytes)
        if num_fragments > 65535:
            if RNS:
                RNS.log(f"BLEFragmenter: Packet too large: {packet_size} bytes requires {num_fragments} fragments (max 65535)", RNS.LOG_ERROR)
                max_packet_size = 65535 * self.payload_size
                RNS.log(f"BLEFragmenter: Maximum packet size for MTU {self.mtu}: {max_packet_size} bytes", RNS.LOG_ERROR)
            raise ValueError(
                f"Packet requires {num_fragments} fragments, exceeds max (65535). "
                f"Packet size too large for BLE MTU {self.mtu}. "
                f"Maximum supported: {65535 * self.payload_size} bytes"
            )

        # Log fragmentation for multi-fragment packets
        if RNS and num_fragments > 1:
            RNS.log(f"BLEFragmenter: Fragmenting {packet_size} byte packet into {num_fragments} fragments (MTU={self.mtu}, payload={self.payload_size})", RNS.LOG_DEBUG)
        elif RNS and num_fragments > 10:
            # Warn about very high fragment counts (possible performance issue)
            RNS.log(f"BLEFragmenter: High fragment count: {num_fragments} fragments for {packet_size} bytes", RNS.LOG_WARNING)

        # Always use fragmentation protocol for consistency
        # Even single-fragment packets get headers for uniform handling

        fragments = []

        for i in range(num_fragments):
            # Determine fragment type
            if i == 0:
                frag_type = self.TYPE_START
            elif i == num_fragments - 1:
                frag_type = self.TYPE_END
            else:
                frag_type = self.TYPE_CONTINUE

            # Extract data for this fragment
            start_idx = i * self.payload_size
            end_idx = min(start_idx + self.payload_size, packet_size)
            data = packet[start_idx:end_idx]

            # Build fragment header
            header = struct.pack(
                "!BHH",  # Network byte order: unsigned char, unsigned short, unsigned short
                frag_type,
                i,  # sequence number
                num_fragments  # total fragments
            )

            # Combine header + data
            fragment = header + data
            fragments.append(fragment)

        return fragments

    def get_fragment_overhead(self, packet_size):
        """
        Calculate fragmentation overhead for a given packet size.

        Args:
            packet_size: Size of packet in bytes

        Returns:
            tuple of (num_fragments, total_overhead_bytes, overhead_percentage)
        """
        # Always calculate with headers for consistency
        num_fragments = (packet_size + self.payload_size - 1) // self.payload_size
        overhead_bytes = num_fragments * self.HEADER_SIZE
        overhead_pct = (overhead_bytes / packet_size) * 100 if packet_size > 0 else 0.0

        return (num_fragments, overhead_bytes, overhead_pct)


class BLEReassembler:
    """
    Reassembles fragmented BLE packets into complete Reticulum packets.

    Maintains reassembly buffers per sender and handles timeouts for
    incomplete packets.
    """

    # Default timeout for incomplete packets (30 seconds)
    DEFAULT_TIMEOUT = 30.0

    def __init__(self, timeout=None):
        """
        Initialize reassembler.

        Args:
            timeout: Seconds to wait for complete packet before discarding (default 30)
        """
        self.timeout = timeout if timeout is not None else self.DEFAULT_TIMEOUT

        # Reassembly buffers: {sender_id: buffer_dict}
        # buffer_dict: {'fragments': {seq: data}, 'total': int, 'start_time': float}
        self.reassembly_buffers = {}

        # Statistics
        self.packets_reassembled = 0
        self.packets_timeout = 0
        self.fragments_received = 0

    def receive_fragment(self, fragment, sender_id=None):
        """
        Process incoming fragment and reassemble if complete.

        Args:
            fragment: bytes, one BLE fragment (header + data)
            sender_id: Identifier of sending device (default None uses 'default')

        Returns:
            bytes or None: Complete packet if ready, None if waiting for more fragments

        Raises:
            ValueError: If fragment is malformed
        """
        if not isinstance(fragment, bytes):
            raise TypeError("Fragment must be bytes")

        if len(fragment) < BLEFragmenter.HEADER_SIZE:
            raise ValueError(f"Fragment too short: {len(fragment)} bytes (min {BLEFragmenter.HEADER_SIZE})")

        sender_id = sender_id if sender_id is not None else "default"
        self.fragments_received += 1

        # Parse header
        frag_type, sequence, total = struct.unpack("!BHH", fragment[:BLEFragmenter.HEADER_SIZE])
        data = fragment[BLEFragmenter.HEADER_SIZE:]

        # Validate fragment type
        if frag_type not in [BLEFragmenter.TYPE_START, BLEFragmenter.TYPE_CONTINUE, BLEFragmenter.TYPE_END]:
            if RNS:
                RNS.log(f"BLEReassembler: Invalid fragment type 0x{frag_type:02x} from {sender_id}", RNS.LOG_WARNING)
            raise ValueError(f"Invalid fragment type: 0x{frag_type:02x}")

        # Validate sequence and total
        if sequence >= total:
            if RNS:
                RNS.log(f"BLEReassembler: Invalid sequence {sequence} >= total {total} from {sender_id}", RNS.LOG_WARNING)
            raise ValueError(f"Invalid sequence {sequence} >= total {total}")

        if total == 0:
            if RNS:
                RNS.log(f"BLEReassembler: Total fragments cannot be zero from {sender_id}", RNS.LOG_WARNING)
            raise ValueError("Total fragments cannot be zero")

        # Log fragment reception (EXTREME level for high-volume operations)
        if RNS:
            frag_type_name = {1: "START", 2: "CONTINUE", 3: "END"}.get(frag_type, "UNKNOWN")
            RNS.log(f"BLEReassembler: Received {frag_type_name} fragment {sequence+1}/{total} from {sender_id} ({len(data)} bytes)", RNS.LOG_EXTREME)

        # Create unique packet key
        packet_key = (sender_id, sequence // total, total)  # Approximate packet ID

        # If this is the first fragment (sequence 0), create new buffer
        if sequence == 0:
            # Create new reassembly buffer
            self.reassembly_buffers[packet_key] = {
                'fragments': {sequence: data},
                'total': total,  # MEDIUM #7: Store expected total for validation
                'start_time': time.time(),
                'sender_id': sender_id
            }
        else:
            # Find existing buffer for this packet
            buffer_key = None
            for key, buffer in self.reassembly_buffers.items():
                if (key[0] == sender_id and
                    buffer['total'] == total and
                    time.time() - buffer['start_time'] < self.timeout):
                    buffer_key = key
                    break

            if buffer_key is None:
                # No buffer found - either fragment 0 not received yet or timed out
                # Create a temporary buffer in case fragment 0 arrives later
                packet_key = (sender_id, sequence // total, total)
                if packet_key not in self.reassembly_buffers:
                    self.reassembly_buffers[packet_key] = {
                        'fragments': {},
                        'total': total,
                        'start_time': time.time(),
                        'sender_id': sender_id
                    }

                # CRITICAL #3: Duplicate fragment detection (data corruption prevention)
                # Check if this fragment was already received with different data
                if sequence in self.reassembly_buffers[packet_key]['fragments']:
                    existing_data = self.reassembly_buffers[packet_key]['fragments'][sequence]
                    if existing_data == data:
                        # Benign duplicate (retransmit) - ignore
                        if RNS:
                            RNS.log(f"BLEReassembler: Duplicate fragment {sequence} from {sender_id} (ignored)",
                                   RNS.LOG_DEBUG)
                        return None
                    else:
                        # DATA MISMATCH - corruption or protocol error!
                        if RNS:
                            RNS.log(f"BLEReassembler: Fragment {sequence} from {sender_id} received twice with "
                                   f"different data! Possible corruption. Discarding buffer.", RNS.LOG_ERROR)
                        # Discard the entire buffer as it's corrupted
                        del self.reassembly_buffers[packet_key]
                        raise ValueError(
                            f"Fragment {sequence} from {sender_id} received twice with "
                            f"different data! Possible corruption."
                        )

                self.reassembly_buffers[packet_key]['fragments'][sequence] = data
                return None
            else:
                packet_key = buffer_key

                # MEDIUM #7: Validate fragment total consistency
                # Ensure all fragments in this packet report the same total count
                buffer = self.reassembly_buffers[packet_key]
                if buffer['total'] != total:
                    if RNS:
                        RNS.log(f"BLEReassembler: Fragment total mismatch for {sender_id}: "
                               f"expected {buffer['total']}, got {total}. Discarding buffer.", RNS.LOG_ERROR)
                    # Discard the entire buffer as it's corrupted
                    del self.reassembly_buffers[packet_key]
                    raise ValueError(
                        f"Fragment total mismatch for {sender_id}: "
                        f"expected {buffer['total']}, got {total}"
                    )

                # CRITICAL #3: Duplicate fragment detection (data corruption prevention)
                # Check if this fragment was already received with different data
                if sequence in self.reassembly_buffers[packet_key]['fragments']:
                    existing_data = self.reassembly_buffers[packet_key]['fragments'][sequence]
                    if existing_data == data:
                        # Benign duplicate (retransmit) - ignore
                        if RNS:
                            RNS.log(f"BLEReassembler: Duplicate fragment {sequence} from {sender_id} (ignored)",
                                   RNS.LOG_DEBUG)
                        return None
                    else:
                        # DATA MISMATCH - corruption or protocol error!
                        if RNS:
                            RNS.log(f"BLEReassembler: Fragment {sequence} from {sender_id} received twice with "
                                   f"different data! Possible corruption. Discarding buffer.", RNS.LOG_ERROR)
                        # Discard the entire buffer as it's corrupted
                        del self.reassembly_buffers[packet_key]
                        raise ValueError(
                            f"Fragment {sequence} from {sender_id} received twice with "
                            f"different data! Possible corruption."
                        )

                self.reassembly_buffers[packet_key]['fragments'][sequence] = data

        buffer = self.reassembly_buffers[packet_key]

        # Check if we have all fragments
        if len(buffer['fragments']) == total:
            # Check for missing sequences
            for i in range(total):
                if i not in buffer['fragments']:
                    # Missing fragment
                    return None

            # All fragments received - reassemble
            packet = self._reassemble(buffer)

            # Clean up buffer
            del self.reassembly_buffers[packet_key]

            self.packets_reassembled += 1

            # Log successful reassembly
            if RNS:
                RNS.log(f"BLEReassembler: Reassembled {len(packet)} byte packet from {total} fragments (sender: {sender_id})", RNS.LOG_DEBUG)

            return packet

        # Not complete yet
        return None

    def _reassemble(self, buffer):
        """
        Combine fragments in sequence order.

        Args:
            buffer: Buffer dict with fragments

        Returns:
            bytes: Complete packet data
        """
        fragments = buffer['fragments']
        total = buffer['total']

        # Combine in sequence order
        packet_parts = []
        for i in range(total):
            if i not in fragments:
                raise ValueError(f"Missing fragment {i} during reassembly")
            packet_parts.append(fragments[i])

        return b''.join(packet_parts)

    def cleanup_stale_buffers(self):
        """
        Remove packets that timed out.

        Returns:
            int: Number of buffers removed
        """
        now = time.time()
        stale_keys = []

        for packet_key, buffer in self.reassembly_buffers.items():
            if now - buffer['start_time'] > self.timeout:
                stale_keys.append(packet_key)

        for key in stale_keys:
            buffer = self.reassembly_buffers[key]
            if RNS:
                sender = buffer.get('sender_id', 'unknown')
                received = len(buffer['fragments'])
                total = buffer['total']
                RNS.log(f"BLEReassembler: Packet timeout from {sender} ({received}/{total} fragments received, age: {now - buffer['start_time']:.1f}s)", RNS.LOG_WARNING)

            del self.reassembly_buffers[key]
            self.packets_timeout += 1

        return len(stale_keys)

    def get_statistics(self):
        """
        Get reassembly statistics.

        Returns:
            dict: Statistics including packets reassembled, timeouts, etc.
        """
        return {
            'packets_reassembled': self.packets_reassembled,
            'packets_timeout': self.packets_timeout,
            'fragments_received': self.fragments_received,
            'pending_packets': len(self.reassembly_buffers)
        }

    def reset_statistics(self):
        """Reset statistics counters."""
        self.packets_reassembled = 0
        self.packets_timeout = 0
        self.fragments_received = 0


class HDLCFramer:
    """
    HDLC-style byte stuffing for packet framing.

    Provides an alternative framing method using HDLC byte stuffing,
    similar to what RNode uses. This can mark packet boundaries in a
    continuous byte stream.
    """

    FLAG = 0x7E  # Frame delimiter
    ESCAPE = 0x7D  # Escape character
    ESCAPE_XOR = 0x20  # XOR mask for escaped bytes

    @staticmethod
    def frame_packet(packet):
        """
        Frame a packet with HDLC byte stuffing.

        Args:
            packet: bytes to frame

        Returns:
            bytes: Framed packet with FLAG delimiters
        """
        if not isinstance(packet, bytes):
            raise TypeError("Packet must be bytes")

        # Byte stuff the data
        stuffed = bytearray()
        for byte in packet:
            if byte == HDLCFramer.FLAG or byte == HDLCFramer.ESCAPE:
                stuffed.append(HDLCFramer.ESCAPE)
                stuffed.append(byte ^ HDLCFramer.ESCAPE_XOR)
            else:
                stuffed.append(byte)

        # Add FLAG delimiters
        frame = bytes([HDLCFramer.FLAG]) + bytes(stuffed) + bytes([HDLCFramer.FLAG])
        return frame

    @staticmethod
    def deframe_packet(frame):
        """
        Remove HDLC framing and unstuff bytes.

        Args:
            frame: bytes, framed packet

        Returns:
            bytes: Original packet data

        Raises:
            ValueError: If frame is malformed
        """
        if not isinstance(frame, bytes):
            raise TypeError("Frame must be bytes")

        if len(frame) < 2:
            raise ValueError("Frame too short (minimum 2 bytes for delimiters)")

        # Check for FLAG delimiters
        if frame[0] != HDLCFramer.FLAG or frame[-1] != HDLCFramer.FLAG:
            raise ValueError("Invalid frame: missing FLAG delimiters")

        # Remove delimiters
        data = frame[1:-1]

        # Unstuff bytes
        unstuffed = bytearray()
        escape_next = False

        for byte in data:
            if escape_next:
                unstuffed.append(byte ^ HDLCFramer.ESCAPE_XOR)
                escape_next = False
            elif byte == HDLCFramer.ESCAPE:
                escape_next = True
            elif byte == HDLCFramer.FLAG:
                raise ValueError("Unexpected FLAG in frame data")
            else:
                unstuffed.append(byte)

        if escape_next:
            raise ValueError("Frame ends with ESCAPE character")

        return bytes(unstuffed)
