#!/usr/bin/env python3
"""Verify shadow-orchestration lockstep between IRLite and IRL-redactor (Ф4 of
plan-shadow-seam-refactor).

Both mods carry a copy of the same MC-typed shadow orchestration past the
``ShadowCasterSource`` seam (frozen in irl-core/docs/shadow-caster-seam-spec.md,
Ф1; cut into redactor-main in Ф2; ported to IRLite in Ф3). The orchestration
files MUST stay byte-identical after a small set of allowlisted substitutions
that account for legitimately per-mod identifiers (package prefix, config class
name, source-impl class name). This script enforces that contract: any drift
introduced on one side without the matching change on the other is reported as
a hard failure with the differing lines shown side by side.

Run from anywhere; the script locates both sibling repos relative to its own
location (`<BBS>/irl-core/tools/verify-shadow-lockstep.py` ->
`<BBS>/irlights` + `<BBS>/bbs-irlights-addon`). Override with env vars
`IRL_REDACTOR_DIR` / `IRLITE_DIR` if needed.

Exit codes: 0 = lockstep intact; 1 = drift detected; 2 = setup error
(missing file, missing repo).
"""

from __future__ import annotations

import difflib
import os
import re
import sys
from pathlib import Path

# --- Lockstep file set (canonical paths under each mod's shadow package) ----
# These files MUST normalize to byte-identical content. Any orchestration
# change must be applied to BOTH copies in the same commit.
LOCKSTEP_FILES = [
    "BlockShadowCache.java",
    "BlockShadowCollector.java",
    "BlockShadowEntry.java",
    "CasterType.java",
    "ImmediateOccluderBatch.java",
    "IRLShadowQuality.java",
    "OccluderBatch.java",
    "OccluderSink.java",
    "PointShadowArray.java",
    "ShadowBakeState.java",
    "ShadowBaker.java",
    "ShadowCasterSource.java",
    "ShadowRenderer.java",
    "SpotlightDepthAtlas.java",
]

# Per-mod seam implementations — explicitly OUT of lockstep (they're the only
# point where BBS-specific draw code is allowed to live).
PER_MOD_SEAM_FILES = {
    "redactor": "RedactorEntityCasterSource.java",
    "irlite": "IRLiteBbsCasterSource.java",
}

# --- Path resolution -------------------------------------------------------
SHADOW_SUBPATH_REDACTOR = Path("src/client/java/org/qualet/irlredactor/light/shadow")
SHADOW_SUBPATH_IRLITE = Path("src/client/java/qualet/irlite/client/light/shadow")

# --- Normalization ---------------------------------------------------------
# Substitutions applied to both files before diffing. Order is significant
# (longer prefixes first to avoid partial matches via the |-alternation).
# Each entry collapses a legitimately per-mod identifier onto a neutral token.
SUBSTITUTIONS = [
    # Project root packages (longest first so qualet.irlite.client wins over
    # qualet.irlite when both prefixes are present).
    ("org.qualet.irlredactor", "MOD"),
    ("qualet.irlite.client", "MOD"),
    ("org.qualet.irl", "MOD"),   # irl-core (used by IRLite for LightRegistry)
    ("qualet.irlite", "MOD"),    # IRLite mixin / non-client packages
    # Config holders.
    ("IrliteConfig", "Cfg"),
    ("LightConfig", "Cfg"),
    # Per-mod ShadowCasterSource implementations.
    ("RedactorEntityCasterSource", "SourceImpl"),
    ("IRLiteBbsCasterSource", "SourceImpl"),
]

_SUB_DICT = dict(SUBSTITUTIONS)
_SUB_RE = re.compile("|".join(re.escape(k) for k, _ in SUBSTITUTIONS))


def normalize(text: str) -> str:
    """Strip BOM, normalize line endings, drop package+import lines, apply
    the allowlisted identifier substitutions."""
    if text.startswith("﻿"):
        text = text[1:]
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    out = []
    for line in text.split("\n"):
        stripped = line.lstrip()
        if stripped.startswith("package ") or stripped.startswith("import "):
            continue
        out.append(_SUB_RE.sub(lambda m: _SUB_DICT[m.group(0)], line))
    return "\n".join(out)


def resolve_repos() -> tuple[Path, Path]:
    """Locate the two sibling repos. Honors IRL_REDACTOR_DIR / IRLITE_DIR env
    vars, falls back to siblings of irl-core (the script's grandparent)."""
    here = Path(__file__).resolve()
    bbs_root = here.parent.parent.parent  # tools -> irl-core -> BBS
    red = Path(os.environ.get("IRL_REDACTOR_DIR", bbs_root / "irlights"))
    irl = Path(os.environ.get("IRLITE_DIR", bbs_root / "bbs-irlights-addon"))
    return red.resolve(), irl.resolve()


def compare_pair(name: str, red_path: Path, irl_path: Path) -> list[str]:
    """Return a list of human-readable diff lines for one file pair (empty
    list = lockstep intact)."""
    if not red_path.is_file():
        return [f"MISSING in redactor: {red_path}"]
    if not irl_path.is_file():
        return [f"MISSING in IRLite:   {irl_path}"]
    red_n = normalize(red_path.read_text(encoding="utf-8")).split("\n")
    irl_n = normalize(irl_path.read_text(encoding="utf-8")).split("\n")
    if red_n == irl_n:
        return []
    diff = difflib.unified_diff(
        red_n, irl_n,
        fromfile=f"redactor:{name}",
        tofile=f"irlite:{name}",
        n=2,
        lineterm="",
    )
    return list(diff)


def main() -> int:
    red_root, irl_root = resolve_repos()
    red_dir = red_root / SHADOW_SUBPATH_REDACTOR
    irl_dir = irl_root / SHADOW_SUBPATH_IRLITE

    if not red_dir.is_dir():
        print(f"ERROR: redactor shadow dir not found: {red_dir}", file=sys.stderr)
        return 2
    if not irl_dir.is_dir():
        print(f"ERROR: IRLite shadow dir not found:   {irl_dir}", file=sys.stderr)
        return 2

    print(f"Verifying shadow-orchestration lockstep:")
    print(f"  redactor: {red_dir}")
    print(f"  irlite:   {irl_dir}")
    print()

    drift_count = 0
    for name in LOCKSTEP_FILES:
        diff = compare_pair(name, red_dir / name, irl_dir / name)
        if not diff:
            print(f"  OK    {name}")
        else:
            drift_count += 1
            print(f"  DRIFT {name}")
            for line in diff:
                print(f"      {line}")
            print()

    # Sanity check: per-mod seam implementations must each exist exactly in
    # their owner repo (and only there).
    setup_errors = []
    if not (red_dir / PER_MOD_SEAM_FILES["redactor"]).is_file():
        setup_errors.append(
            f"redactor missing its seam impl: {PER_MOD_SEAM_FILES['redactor']}"
        )
    if (red_dir / PER_MOD_SEAM_FILES["irlite"]).is_file():
        setup_errors.append(
            f"redactor unexpectedly carries IRLite seam impl: "
            f"{PER_MOD_SEAM_FILES['irlite']}"
        )
    if not (irl_dir / PER_MOD_SEAM_FILES["irlite"]).is_file():
        setup_errors.append(
            f"IRLite missing its seam impl: {PER_MOD_SEAM_FILES['irlite']}"
        )
    if (irl_dir / PER_MOD_SEAM_FILES["redactor"]).is_file():
        setup_errors.append(
            f"IRLite unexpectedly carries redactor seam impl: "
            f"{PER_MOD_SEAM_FILES['redactor']}"
        )

    print()
    if drift_count == 0 and not setup_errors:
        print(f"PASS: all {len(LOCKSTEP_FILES)} orchestration files are in lockstep.")
        return 0

    if setup_errors:
        print("SETUP ERRORS:")
        for e in setup_errors:
            print(f"  - {e}")
    if drift_count:
        print(f"FAIL: drift in {drift_count}/{len(LOCKSTEP_FILES)} files.")
        print("Apply the matching change to the other mod and rerun.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
