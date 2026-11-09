#!/usr/bin/env python3
"""Build and verify the QuickLZ level-3 stream used by TodooCard v157+."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path


QUICKLZ_BLOCK_SIZE = 2048
QUICKLZ_LEVEL3_HASH_VALUES = 4096
QUICKLZ_LEVEL3_POINTERS = 16
T3_CONTROLLER_BYTES = 528 * 792 // 2


@dataclass(frozen=True)
class PayloadInfo:
    byte_length: int
    decoded_bytes: int
    block_count: int
    compressed_blocks: int
    stored_blocks: int
    levels: tuple[int, ...]


def _read_le(source: bytes | bytearray, offset: int, count: int) -> int:
    value = 0
    for index in range(count):
        if offset + index < len(source):
            value |= source[offset + index] << (index * 8)
    return value


def _write_le(target: bytearray, offset: int, value: int, count: int) -> None:
    for index in range(count):
        target[offset + index] = (value >> (index * 8)) & 0xFF


def _level3_hash(fetch: int) -> int:
    return ((fetch >> 12) ^ fetch) & (QUICKLZ_LEVEL3_HASH_VALUES - 1)


def encode_level3_block(source: bytes) -> bytes:
    """Match the mini-program's independent QuickLZ 1.5 level-3 encoder."""
    if not 0 < len(source) <= QUICKLZ_BLOCK_SIZE:
        raise ValueError(
            f"QuickLZ block must contain 1-{QUICKLZ_BLOCK_SIZE} bytes, got {len(source)}"
        )

    core = bytearray(len(source) + 400)
    hash_offsets = [-1] * (
        QUICKLZ_LEVEL3_HASH_VALUES * QUICKLZ_LEVEL3_POINTERS
    )
    hash_counters = [0] * QUICKLZ_LEVEL3_HASH_VALUES

    last_byte = len(source) - 1
    last_match_start = last_byte - 6 - 4
    source_offset = 0
    control_word_offset = 0
    target_offset = 4
    control_word = 0x80000000

    def flush_control_word() -> None:
        value = ((control_word >> 1) | 0x80000000) & 0xFFFFFFFF
        _write_le(core, control_word_offset, value, 4)

    while source_offset <= last_match_start:
        if control_word & 1:
            flush_control_word()
            control_word_offset = target_offset
            target_offset += 4
            control_word = 0x80000000

        fetch = _read_le(source, source_offset, 3)
        hash_value = _level3_hash(fetch)
        maximum_match_length = min(258, len(source) - 4 - source_offset)
        hash_base = hash_value * QUICKLZ_LEVEL3_POINTERS
        hash_count = hash_counters[hash_value]
        previous_offset = -1
        match_length = 0
        candidate_count = min(hash_count, QUICKLZ_LEVEL3_POINTERS)
        for candidate_index in range(candidate_count):
            candidate_offset = hash_offsets[hash_base + candidate_index]
            candidate_distance = source_offset - candidate_offset
            if (
                candidate_offset < 0
                or candidate_distance < 3
                or candidate_distance > 131071
                or source[candidate_offset] != source[source_offset]
                or source[candidate_offset + 1] != source[source_offset + 1]
                or source[candidate_offset + 2] != source[source_offset + 2]
            ):
                continue
            candidate_length = 3
            while (
                candidate_length < maximum_match_length
                and source[candidate_offset + candidate_length]
                == source[source_offset + candidate_length]
            ):
                candidate_length += 1
            if candidate_length > match_length:
                match_length = candidate_length
                previous_offset = candidate_offset

        hash_offsets[
            hash_base + (hash_count & (QUICKLZ_LEVEL3_POINTERS - 1))
        ] = source_offset
        hash_counters[hash_value] = (hash_count + 1) & 0xFFFF

        if match_length >= 3:
            match_offset = source_offset - previous_offset
            control_word = ((control_word >> 1) | 0x80000000) & 0xFFFFFFFF
            for index in range(1, match_length):
                if source_offset + index <= last_match_start:
                    inner_offset = source_offset + index
                    inner_hash = _level3_hash(_read_le(source, inner_offset, 3))
                    inner_count = hash_counters[inner_hash]
                    hash_offsets[
                        inner_hash * QUICKLZ_LEVEL3_POINTERS
                        + (inner_count & (QUICKLZ_LEVEL3_POINTERS - 1))
                    ] = inner_offset
                    hash_counters[inner_hash] = (inner_count + 1) & 0xFFFF
            source_offset += match_length
            if match_length == 3 and match_offset <= 63:
                core[target_offset] = match_offset << 2
                target_offset += 1
            elif match_length == 3 and match_offset <= 16383:
                _write_le(core, target_offset, (match_offset << 2) | 1, 2)
                target_offset += 2
            elif match_length <= 18 and match_offset <= 1023:
                _write_le(
                    core,
                    target_offset,
                    ((match_length - 3) << 2) | (match_offset << 6) | 2,
                    2,
                )
                target_offset += 2
            elif match_length <= 33:
                _write_le(
                    core,
                    target_offset,
                    ((match_length - 2) << 2) | (match_offset << 7) | 3,
                    3,
                )
                target_offset += 3
            else:
                _write_le(
                    core,
                    target_offset,
                    ((match_length - 3) << 7) | (match_offset << 15) | 3,
                    4,
                )
                target_offset += 4
        else:
            core[target_offset] = source[source_offset]
            target_offset += 1
            source_offset += 1
            control_word >>= 1

    while source_offset <= last_byte:
        if control_word & 1:
            flush_control_word()
            control_word_offset = target_offset
            target_offset += 4
            control_word = 0x80000000
        core[target_offset] = source[source_offset]
        target_offset += 1
        source_offset += 1
        control_word >>= 1

    while not control_word & 1:
        control_word >>= 1
    flush_control_word()

    core_length = max(target_offset, 9)
    header_size = 3 if len(source) < 216 else 9
    compressed_size = header_size + core_length
    if compressed_size >= header_size + len(source):
        stored = bytearray(header_size + len(source))
        stored[0] = 0x7C | (0x02 if header_size == 9 else 0)
        if header_size == 3:
            stored[1] = len(stored)
            stored[2] = len(source)
        else:
            _write_le(stored, 1, len(stored), 4)
            _write_le(stored, 5, len(source), 4)
        stored[header_size:] = source
        return bytes(stored)

    compressed = bytearray(compressed_size)
    compressed[0] = 0x7D | (0x02 if header_size == 9 else 0)
    if header_size == 3:
        compressed[1] = compressed_size
        compressed[2] = len(source)
    else:
        _write_le(compressed, 1, compressed_size, 4)
        _write_le(compressed, 5, len(source), 4)
    compressed[header_size:] = core[:core_length]
    return bytes(compressed)


def build_level3_payload(raw: bytes) -> bytes:
    if len(raw) != T3_CONTROLLER_BYTES:
        raise ValueError(
            f"T3 controller payload must be {T3_CONTROLLER_BYTES} bytes, got {len(raw)}"
        )
    blocks = [
        encode_level3_block(raw[offset : offset + QUICKLZ_BLOCK_SIZE])
        for offset in range(0, len(raw), QUICKLZ_BLOCK_SIZE)
    ]
    return b"\x00\x00\x00\x00" + b"".join(blocks)


def inspect_payload(payload: bytes) -> PayloadInfo:
    if len(payload) < 4 or payload[:4] != b"\x00\x00\x00\x00":
        raise ValueError("QuickLZ payload is missing its four-byte zero prefix")
    offset = 4
    decoded_bytes = 0
    compressed_blocks = 0
    stored_blocks = 0
    levels: set[int] = set()
    while offset < len(payload):
        if offset + 3 > len(payload):
            raise ValueError(f"Truncated QuickLZ block header at {offset}")
        header = payload[offset]
        header_size = 9 if header & 0x02 else 3
        if offset + header_size > len(payload):
            raise ValueError(f"Truncated QuickLZ block header at {offset}")
        compressed_size = _read_le(
            payload, offset + 1, 4 if header_size == 9 else 1
        )
        decoded_size = _read_le(
            payload, offset + 5 if header_size == 9 else offset + 2,
            4 if header_size == 9 else 1,
        )
        level = (header >> 2) & 0x03
        if (
            not header & 0x40
            or level not in (1, 3)
            or compressed_size < header_size
            or not 0 < decoded_size <= QUICKLZ_BLOCK_SIZE
            or offset + compressed_size > len(payload)
        ):
            raise ValueError(f"Invalid QuickLZ block at {offset}")
        levels.add(level)
        if header & 1:
            compressed_blocks += 1
        else:
            stored_blocks += 1
        decoded_bytes += decoded_size
        offset += compressed_size
    if offset != len(payload):
        raise ValueError(f"QuickLZ payload length mismatch: {len(payload)}")
    return PayloadInfo(
        byte_length=len(payload),
        decoded_bytes=decoded_bytes,
        block_count=compressed_blocks + stored_blocks,
        compressed_blocks=compressed_blocks,
        stored_blocks=stored_blocks,
        levels=tuple(sorted(levels)),
    )


def _decode_level3_block(block: bytes, header_size: int, decoded_size: int) -> bytes:
    output = bytearray(decoded_size)
    bit_lengths = (4, 0, 1, 0, 2, 0, 1, 0, 3, 0, 1, 0, 2, 0, 1, 0)
    source_offset = header_size
    target_offset = 0
    control_word = 1

    def require_bytes(count: int) -> None:
        if source_offset + count > len(block):
            raise ValueError("Truncated QuickLZ compressed block")

    while True:
        if control_word == 1:
            require_bytes(4)
            control_word = _read_le(block, source_offset, 4)
            source_offset += 4
        if control_word & 1:
            require_bytes(4)
            fetch = _read_le(block, source_offset, 4)
            control_word >>= 1
            if fetch & 3 == 0:
                match_offset = (fetch & 0xFF) >> 2
                match_length = 3
                source_offset += 1
            elif fetch & 2 == 0:
                match_offset = (fetch & 0xFFFF) >> 2
                match_length = 3
                source_offset += 2
            elif fetch & 1 == 0:
                match_offset = (fetch & 0xFFFF) >> 6
                match_length = ((fetch >> 2) & 15) + 3
                source_offset += 2
            elif fetch & 127 != 3:
                match_offset = (fetch >> 7) & 0x1FFFF
                match_length = ((fetch >> 2) & 0x1F) + 2
                source_offset += 3
            else:
                match_offset = fetch >> 15
                match_length = ((fetch >> 7) & 0xFF) + 3
                source_offset += 4
            previous_offset = target_offset - match_offset
            if (
                match_offset < 3
                or previous_offset < 0
                or match_length <= 0
                or match_length > decoded_size - target_offset - 4
            ):
                raise ValueError("Unsafe QuickLZ level-3 match")
            for index in range(match_length):
                output[target_offset] = output[previous_offset + index]
                target_offset += 1
            continue

        if target_offset < decoded_size - 11:
            literal_count = bit_lengths[control_word & 0x0F]
            if not literal_count:
                raise ValueError("Invalid QuickLZ control word")
            require_bytes(literal_count)
            output[target_offset : target_offset + literal_count] = block[
                source_offset : source_offset + literal_count
            ]
            source_offset += literal_count
            target_offset += literal_count
            control_word >>= literal_count
            continue

        while target_offset < decoded_size:
            if control_word == 1:
                require_bytes(4)
                source_offset += 4
                control_word = 0x80000000
            require_bytes(1)
            output[target_offset] = block[source_offset]
            target_offset += 1
            source_offset += 1
            control_word >>= 1
        return bytes(output)


def unwrap_payload(payload: bytes) -> bytes:
    info = inspect_payload(payload)
    output = bytearray()
    offset = 4
    while offset < len(payload):
        header = payload[offset]
        header_size = 9 if header & 0x02 else 3
        compressed_size = _read_le(
            payload, offset + 1, 4 if header_size == 9 else 1
        )
        decoded_size = _read_le(
            payload, offset + 5 if header_size == 9 else offset + 2,
            4 if header_size == 9 else 1,
        )
        block = payload[offset : offset + compressed_size]
        if not header & 1:
            decoded = block[header_size:]
            if len(decoded) != decoded_size:
                raise ValueError("Invalid stored QuickLZ block length")
        elif ((header >> 2) & 0x03) == 3:
            decoded = _decode_level3_block(block, header_size, decoded_size)
        else:
            raise ValueError("Compressed QuickLZ level-1 blocks are not produced here")
        output.extend(decoded)
        offset += compressed_size
    if len(output) != info.decoded_bytes:
        raise ValueError("Decoded QuickLZ byte count does not match its headers")
    return bytes(output)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build the v157+ TodooCard QuickLZ level-3 image payload."
    )
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    raw = args.input.read_bytes()
    payload = build_level3_payload(raw)
    decoded = unwrap_payload(payload)
    if decoded != raw:
        raise SystemExit("QuickLZ round-trip verification failed")
    args.output.write_bytes(payload)
    info = inspect_payload(payload)
    saved = len(raw) - len(payload)
    print(
        f"Wrote {args.output} ({len(payload)} bytes; "
        f"{info.compressed_blocks} compressed + {info.stored_blocks} stored "
        f"2KB blocks; saved {saved} bytes)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
