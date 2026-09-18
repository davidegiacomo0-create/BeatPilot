#!/usr/bin/env python3
"""Run release-blocking checks. Exit 1 means the defects remain unresolved."""
import pathlib
import subprocess
import sys
import tempfile

here = pathlib.Path(__file__).resolve().parent
core = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else here / "baseline-core"
sources = sorted(core.glob("*.java"))
if not sources:
    raise SystemExit("Provide the directory containing the BeatPilot core Java files.")
with tempfile.TemporaryDirectory(prefix="beatpilot-audit-") as compiled:
    subprocess.run(["java", "com.sun.tools.javac.Main", "--release", "17", "-d", compiled,
                    *map(str, sources), str(here / "ReliabilityAudit.java")], check=True)
    result = subprocess.run(["java", "-cp", compiled, "ReliabilityAudit"], check=False)
    raise SystemExit(result.returncode)
