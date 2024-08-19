"""When using the BlinkyDomeTriangleRotatorPattern, dumping the updated geometry writes to
/var/tmp/led-vertex-locations.csv. This script produces a readable diff between the new dumped geometry and
the existing dome geometry.
"""
import csv

EXISTING = "src/main/resources/led-vertex-locations.csv"
NEW = "/var/tmp/led-vertex-locations.csv"

def parse_geometry(filename):
    parsed = []
    rows = list(csv.reader(open(filename)))
    headers = rows[0]
    data = rows[1:]

    for row in data:
        parsed.append(dict(zip(headers, row)))
    return parsed


existing = parse_geometry(EXISTING)
new = parse_geometry(NEW)

if len(existing) != len(new):
    raise Exception("Row counts differ")

for existing_row, new_row in zip(existing, new):
    if existing_row['domeGroup'] != new_row['domeGroup']:
        raise Exception("Invalid geometry")
    if existing_row['domeIndex'] != new_row['domeIndex']:
        raise Exception("Invalid geometry")

    if existing_row != new_row:
        domeGroup = existing_row['domeGroup']
        domeIndex = existing_row['domeIndex']

        for key in existing_row:
            existing_value = existing_row[key]
            new_value = new_row[key]
            if existing_value != new_value:
                print(f'Change {domeGroup} {domeIndex}: {key} {existing_value} -> {new_value}')