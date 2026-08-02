#!/usr/bin/env python3
"""
Regenerate every fixture and the full model, in dependency order.

Each generator in this directory already exposes main() as its entry point, so
this imports them and calls those directly rather than spawning an interpreter
per generator. One process means constants.py is parsed once and every
generator sees the same values, which matters now that several of them derive
geometry from each other (generate_full_model reads the star radius and the V3
dome's centre height to place fixtures).

Output is identical either way: every generator writes to an absolute path
built from constants.py, so none of them depend on the working directory.

Needs Python 3.9 or newer (enforced in constants.py, pinned in .python-version).

Usage:
    python3 Generators/generate_all.py
    ./Generators/generate_all.py
"""

from __future__ import annotations

import sys
from importlib import import_module
from pathlib import Path

# The generators import each other and constants.py as flat, top-level modules
# ("from constants import ..."), which is why the old shell script cd'd here
# first. Running this file as a script already puts its directory on sys.path;
# this keeps the imports working if it is invoked some other way.
GENERATORS_DIR = str(Path(__file__).resolve().parent)
if GENERATORS_DIR not in sys.path:
    sys.path.insert(0, GENERATORS_DIR)

# Imported for its side effect as much as its value: constants.py holds the
# minimum Python version and refuses to load on anything older. Doing it here,
# before the generator loop, means a too-old interpreter reports the version
# problem rather than surfacing as "generate_star.py failed".
from constants import MIN_PYTHON  # noqa: E402


# Order matters: generate_full_model assembles the finished fixtures into the
# .lxm, so it runs last.
GENERATORS = (
    "generate_waterfall",
    "generate_harness_point_sizes",
    "generate_v3_dome_model",
    "generate_full_model",
)


def run(module_name: str) -> None:
    """Import one generator and run its main() as if it had been invoked bare.

    A couple of the generators take optional command-line overrides (--output,
    --dome-radius-m) and reach for sys.argv to find them. In-process they would
    read *our* argv instead, so they are handed an empty one and fall back to
    the defaults in constants.py -- exactly what running them with no arguments
    did before.
    """
    module = import_module(module_name)
    original_argv = sys.argv
    sys.argv = [f"{module_name}.py"]
    try:
        module.main()
    finally:
        sys.argv = original_argv


def main() -> int:
    version = ".".join(str(part) for part in sys.version_info[:3])
    minimum = ".".join(str(part) for part in MIN_PYTHON)
    print(f"Python {version} (>= {minimum} required) at {sys.executable}")
    print(f"Running {len(GENERATORS)} generator(s)")
    print()

    for module_name in GENERATORS:
        print(f"== {module_name}.py ==")
        try:
            run(module_name)
        except Exception as error:
            # Stop at the first failure rather than building the model out of
            # half-regenerated fixtures. Flush first: piped to a file, stdout is
            # block-buffered and stderr is not, so the error would otherwise
            # land above the generator output that explains it.
            sys.stdout.flush()
            print(f"error: {module_name}.py failed: {error}", file=sys.stderr)
            return 1
        print()

    print("All fixtures regenerated.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
