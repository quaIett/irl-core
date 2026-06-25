#!/usr/bin/env python3
"""RETIRED 2026-06-25 — shadow orchestration moved into irl-core.

Ф2 of plan-irl-core-shadow-extraction physically moved the 14 MC-typed
shadow-orchestration files out of BOTH mods and into the per-version irl-core
Loom module (package org.qualet.irl.light.shadow), behind two seams:
ShadowConfig (config pull) + ShadowEngine (caster-source + config injection).

There is now exactly ONE copy of the orchestration (in irl-core), so the
byte-identical-lockstep contract this script enforced no longer applies — there
is nothing left to keep in lockstep. Each mod keeps only its per-mod
ShadowCasterSource impl (RedactorEntityCasterSource / IRLiteBbsCasterSource),
which were never in lockstep anyway.

To change the orchestration now: edit it ONCE in
  irl-core/src/main/java/org/qualet/irl/light/shadow/
then `gradlew publishToMavenLocal` in irl-core and rebuild the mods.

See irl-core/docs/shadow-config-source-injection-spec.md (the Ф1 contract, now
implemented) and irl-core/docs/shadow-orchestration-lockstep.md (the retired Ф4
verifier this script belonged to). This stub is kept (rather than deleted) so a
stale invocation prints this explanation and exits 0 instead of failing oddly.
"""

import sys


def main() -> int:
    print(__doc__)
    print("verify-shadow-lockstep: RETIRED — orchestration now lives once in irl-core.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
