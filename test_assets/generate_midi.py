#!/usr/bin/env python3
"""Generate MIDI test files for pitch detection algorithm testing."""

import struct
import os

TICKS_PER_QNOTE = 480
TEMPO_MICROS = 500_000  # 120 BPM

INSTRUMENTS = {
    "piano": 0,
    "cello": 42,
    "oboe": 68,
    "guitar": 24,
}

PATTERNS = [
    {
        "name": "whole",
        "start_note": 24,   # C1
        "end_note": 84,     # C6
        "note_len_ticks": TICKS_PER_QNOTE * 4,
        "gap_ticks": 20,
    },
    {
        "name": "quarter",
        "start_note": 24,   # C1
        "end_note": 84,     # C6
        "note_len_ticks": TICKS_PER_QNOTE,
        "gap_ticks": 10,
    },
    {
        "name": "16th",
        "start_note": 24,   # C1
        "end_note": 84,     # C6
        "note_len_ticks": TICKS_PER_QNOTE // 4,
        "gap_ticks": 5,
    },
    {
        "name": "32nd",
        "start_note": 24,   # C1
        "end_note": 84,     # C6
        "note_len_ticks": TICKS_PER_QNOTE // 8,
        "gap_ticks": 3,
    },
]

OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "midi")


def write_var_len(value):
    """Encode a value as a MIDI variable-length quantity."""
    bytes_ = []
    while True:
        bytes_.insert(0, value & 0x7F)
        value >>= 7
        if value == 0:
            break
    result = bytearray()
    for i, b in enumerate(bytes_):
        if i < len(bytes_) - 1:
            result.append(b | 0x80)
        else:
            result.append(b)
    return bytes(result)


def make_track(events, channel=0):
    """Build a MIDI track chunk from a list of (delta_time, event_bytes) pairs."""
    data = bytearray()
    for delta, event in events:
        data.extend(write_var_len(delta))
        data.extend(event if isinstance(event, (bytes, bytearray)) else bytes([event]))
    return b"MTrk" + struct.pack(">I", len(data)) + bytes(data)


def build_pattern(instrument_program, start_note, end_note, note_len_ticks, gap_ticks):
    """Build MIDI bytes for a single pattern with given instrument."""
    ticks_per_beat = TICKS_PER_QNOTE

    header = struct.pack(">HHH", 0, 1, ticks_per_beat)  # format 0, 1 track
    header_chunk = b"MThd" + struct.pack(">I", 6) + header

    events = []

    # Set tempo
    tempo_bytes = struct.pack(">I", TEMPO_MICROS)[1:]  # 3 bytes
    events.append((0, bytes([0xFF, 0x51, 0x03]) + tempo_bytes))

    # Set instrument (program change)
    events.append((0, bytes([0xC0 | 0, instrument_program])))

    # Ascending
    for note in range(start_note, end_note + 1):
        events.append((gap_ticks, bytes([0x90, note, 100])))
        events.append((note_len_ticks - gap_ticks, bytes([0x80, note, 0])))

    # Descending (skip the top note to avoid double-play)
    for note in range(end_note - 1, start_note - 1, -1):
        events.append((gap_ticks, bytes([0x90, note, 100])))
        events.append((note_len_ticks - gap_ticks, bytes([0x80, note, 0])))

    # End of track
    events.append((0, bytes([0xFF, 0x2F, 0x00])))

    track = make_track(events, channel=0)
    return header_chunk + track


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    for pat in PATTERNS:
        for instr_name, instr_prog in INSTRUMENTS.items():
            filename = f"{pat['name']}_{instr_name}.mid"
            filepath = os.path.join(OUTPUT_DIR, filename)

            data = build_pattern(
                instrument_program=instr_prog,
                start_note=pat["start_note"],
                end_note=pat["end_note"],
                note_len_ticks=pat["note_len_ticks"],
                gap_ticks=pat["gap_ticks"],
            )

            with open(filepath, "wb") as f:
                f.write(data)

            # Quick metadata for verification
            note_count = (pat["end_note"] - pat["start_note"] + 1) * 2 - 1
            print(f"  {filename}: notes {pat['start_note']}-{pat['end_note']}, "
                  f"{note_count} notes, instrument={instr_name}({instr_prog})")

    print(f"\nGenerated {len(PATTERNS) * len(INSTRUMENTS)} MIDI files in {OUTPUT_DIR}/")


if __name__ == "__main__":
    main()
