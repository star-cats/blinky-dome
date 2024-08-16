"""Remaps old BM2023 BlinkyDome topology to updated 12V BM2024 topology."""
import csv

INPUT = "src/main/resources/led-vertex-locations-original.csv"
OUTPUT = "src/main/resources/led-vertex-locations.csv"

rows = list(csv.reader(open(INPUT)))
header = rows[0][:-3] + ["spAddress", "spPort", "spFirstLedOffset"]
data = rows[1:]

REMAPPING = [
    13, 15, 29, 31, 45, 47, 61, 63, 77, 79,
    10, 14, 8, 12, 26, 30, 24, 28, 42, 46,
    72, 76, 74, 78, 56, 60, 58, 62, 40, 44,
    5, 11, 3, 9, 1, 7, 21, 27, 19, 25,
    17, 23, 37, 43, 35, 41, 33, 39, 53, 59,
    65, 71, 67, 73, 69, 75, 49, 55, 51, 57,
    6, 4, 2, 0, 22, 20, 18, 16, 38, 36,
    64, 66, 68, 70, 48, 50, 52, 54, 32, 34
]

TRIANGLES_PER_GROUP = 10

GROUP_TO_STARPUSHER_MAPPING = [
    ("10.1.1.2", 1),
    ("10.1.1.2", 2),
    ("10.1.1.2", 3),
    ("10.1.1.3", 1),
    ("10.1.1.4", 1),
    ("10.1.1.3", 2),
    ("10.1.1.5", 1),
    ("10.1.1.5", 2),
]

LEDS_PER_TRIANGLE = 33

reordered_rows = []

for ii, remap_index in enumerate(REMAPPING):
    group = ii // TRIANGLES_PER_GROUP
    group_index = ii % TRIANGLES_PER_GROUP
    sp_address, sp_port = GROUP_TO_STARPUSHER_MAPPING[group]
    sp_first_led_offset = group_index * LEDS_PER_TRIANGLE
    reordered_rows.append(
        [group, group_index] + data[remap_index][2:-3] + [sp_address, sp_port, sp_first_led_offset]
    )

with open(OUTPUT, "w") as f:
    writer = csv.writer(f)
    writer.writerows([header] + reordered_rows)


