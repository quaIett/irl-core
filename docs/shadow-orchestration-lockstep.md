# Shadow-orchestration lockstep (Ф4) — RETIRED

> **RETIRED 2026-06-25 — superseded by the irl-core extraction (Ф2 of
> `plan-irl-core-shadow-extraction`).** The orchestration this document tracks is no
> longer duplicated: Ф2 moved all 14 files into the per-version `irl-core` Loom module
> (`org.qualet.irl.light.shadow`) behind the `ShadowConfig` + `ShadowEngine` seams
> (see [shadow-config-source-injection-spec.md](shadow-config-source-injection-spec.md)).
> One copy now ⇒ nothing to keep in lockstep; `tools/verify-shadow-lockstep.py` is
> retired to a stub. The "option (a) shared module" rejected below became cheap
> precisely because the core went **per-version** (one Loom per MC line kills the
> 1.9 ↔ 1.15.5 composite friction), `LightRegistry` had already migrated to irl-core,
> and per-version MC-coupling is the intended design — so all three blockers listed
> under "Ф4 rejected option (a)" no longer hold. The text below is the historical Ф4
> record (option C+), kept for the audit trail.

> **Status: ACTIVE (option C+).** Phase 4 of `plan-shadow-seam-refactor`.
> Established 2026-06-17 after Ф3 brought IRLite and redactor-main onto the
> same orchestration. Builds on [shadow-caster-seam-spec.md](shadow-caster-seam-spec.md)
> (the seam contract frozen in Ф1).

## What this is

After Ф3, the MC-typed shadow orchestration past `ShadowCasterSource` is the
same code in both IRLite and IRL-redactor (1.20.4 main). Each mod carries its
own physical copy of those files — there is **no shared Loom module** — but
the copies are kept byte-identical (modulo a small, mechanically-enforced
allowlist) by a verifier script.

Ф4 rejected option (a) — a shared `irl-shadow` Loom module — because it would
require:

- pulling redactor-main onto `org.qualet.irl.light.LightRegistry` from
  irl-core (redactor-main still has its own copy from before the irl-core
  split);
- absorbing Loom 1.9 ↔ Loom 1.15.5 composite-build friction (the two mods
  are on incompatible Loom majors);
- moving MC-typed code into the otherwise plain-Java `irl-core` artifact,
  which would couple it to a specific MC version and undo the artifact's
  reason for existing.

Option (c+) keeps the cost of those changes off the table while still
mechanically catching drift the moment one side edits orchestration without
the matching edit on the other.

## Files under lockstep (14)

Path bases:

- redactor-main → `IRL-redactor/src/client/java/org/qualet/irlredactor/light/shadow/`
- IRLite        → `IRLite/src/client/java/qualet/irlite/client/light/shadow/`

Both directories must contain identically-named copies of these files, and
the verifier requires them to normalize to byte-identical content:

| file | role |
|---|---|
| `ShadowBaker.java` | bake driver — `collect()` → SoA → loop → tile assign → 2-layer bake |
| `ShadowRenderer.java` | GL layer — FBO/program/state, `begin*()`/emit/flush/`endPass()` |
| `SpotlightDepthAtlas.java` | spot depth-tile atlas (sticky tiles, GPU copy) |
| `PointShadowArray.java` | point cube-array atlas |
| `BlockShadowCache.java` | per-light world-block list (stable instance until edit) |
| `BlockShadowCollector.java` | world-block scan over a cone/range |
| `BlockShadowEntry.java` | per-block draw record |
| `IRLShadowQuality.java` | resolution/budget tiers |
| `ShadowBakeState.java` | global "is bake in progress" gate |
| `ShadowCasterSource.java` | the seam contract (frozen in Ф1) |
| `OccluderSink.java` | seam — allocation-free SoA writer (`emitFromBox` / `emit`) |
| `OccluderBatch.java` | seam — opaque batch handle + INV-4 hooks |
| `ImmediateOccluderBatch.java` | shared `Immediate`-backed batch impl |
| `CasterType.java` | neutral tag holder (`ENTITY` / `MODEL_BLOCK` / `REPLAY`) |

## Files NOT under lockstep (per-mod)

Each mod owns exactly one `ShadowCasterSource` implementation alongside the
above files. These hold the only BBS-coupled (or, in the editor's case,
vanilla-coupled) draw code and are NOT expected to match:

| owner | file | what it does |
|---|---|---|
| redactor-main | `RedactorEntityCasterSource.java` | real vanilla `EntityRenderDispatcher.render` |
| IRLite        | `IRLiteBbsCasterSource.java`        | BBS `Form` / `Film` / `MorphRenderer` silhouettes |

The verifier also asserts each per-mod impl is present in its owner repo and
absent in the other repo (catches an accidental cross-copy).

## Allowlisted divergences

Lockstep means "byte-identical after normalization." The normalizer is
defined in `tools/verify-shadow-lockstep.py` and applies these substitutions
to both sides before diffing:

| substitution | why it's allowed |
|---|---|
| `package` declaration dropped | both sides have a one-line package decl |
| `import` lines dropped | each side imports its own neighbours; equivalent paths after the prefix substitutions below |
| `org.qualet.irlredactor` → `MOD` | redactor root package |
| `qualet.irlite.client` → `MOD` | IRLite client-side package |
| `org.qualet.irl` → `MOD` | irl-core packages (IRLite consumes `org.qualet.irl.light.LightRegistry`) |
| `qualet.irlite` → `MOD` | IRLite non-client packages (mixin etc.) referenced in javadoc |
| `LightConfig` ↔ `IrliteConfig` → `Cfg` | each mod's static config accessor — same shape, different name |
| `RedactorEntityCasterSource` ↔ `IRLiteBbsCasterSource` → `SourceImpl` | the per-mod seam impl class wired into the static `SOURCE` field at the bottom of `ShadowBaker.java` |

Anything else that differs is treated as drift and fails the run.

## How to run

From any of the three repos (or anywhere):

```sh
python <repos>/irl-core/tools/verify-shadow-lockstep.py
```

The script locates its own siblings — if checked out side by side under one
parent folder (`<parent>/{irl-core, IRL-redactor, IRLite}`) no arguments
are needed. To override, set env vars:

```sh
IRL_REDACTOR_DIR=/path/to/IRL-redactor \
IRLITE_DIR=/path/to/IRLite \
python <repos>/irl-core/tools/verify-shadow-lockstep.py
```

Exit codes:

- `0` — lockstep intact, all 14 files normalize identically
- `1` — drift detected (per-file unified diff printed)
- `2` — setup error (missing repo or missing file)

## What to do when the verifier fails

The verifier reports drift as a unified diff between the normalized copies.
The fix is always the same: **apply the matching change to the other mod's
copy and rerun the verifier** until it passes. The two mods MUST land
orchestration edits as a pair (one commit per repo, in the same session).

Do NOT:

- weaken the verifier (e.g. add a one-off substitution) just to dodge a
  legitimate drift — that defeats the entire mechanism;
- edit the per-mod seam impl (`RedactorEntityCasterSource` /
  `IRLiteBbsCasterSource`) to recover orchestration behavior — the seam
  contract in `shadow-caster-seam-spec.md` is frozen; seam impls only
  implement the two contract methods.

If a divergence is truly load-bearing and per-mod (e.g. some future caster
type that only exists on one side), prefer adding a new sink/batch shape to
the seam over splitting the orchestration; if a split is unavoidable, update
this doc and the normalizer's substitution table in lockstep with the code,
under explicit review.

## Adding a new orchestration file to the lockstep set

1. Add the file to both repos at the same path under their respective
   `light/shadow/` directory; the contents must match modulo the normalizer.
2. Append the filename to `LOCKSTEP_FILES` in `verify-shadow-lockstep.py`.
3. Run the verifier from a clean state — it must `PASS`.
4. Commit the file (in both mod repos) + the verifier change (in irl-core)
   together. Reference this doc in the commit message.

## Adding a new per-mod seam impl

1. Place the file in its owner repo at `light/shadow/<Name>.java`.
2. Append the filename to `PER_MOD_SEAM_FILES` in
   `verify-shadow-lockstep.py` under the right owner key.
3. Run the verifier — it must `PASS` and report the new per-mod sanity check
   (must exist in owner; must NOT exist in the other).

## Limitations and what comes next

- **Port branch (1.21.11) is NOT in the lockstep set.** `redactor` branch
  `port/1.21.11` has a raw-GL rewrite of `ShadowRenderer` (the 1.21.5+
  pipeline API removed the buffers the 1.20.4 path uses). Unifying it with
  the main orchestration is plan-shadow-seam-refactor's Ф5 — a Stonecutter
  pass that wraps only the GL layer in `//? if` directives, now that the
  seam isolates the BBS-coupled paths.
- **Drift is detected, not prevented.** A developer can still land an
  unmatched change if the verifier isn't run pre-commit. Wiring this into a
  git pre-commit hook in each mod repo is a low-cost upgrade if drift
  becomes a recurring problem.
- **The verifier reads but does not write.** It will never edit the
  orchestration files; recovery is always a manual matched edit on the other
  side.

## Hand-off to Ф5 (Stonecutter)

When the 1.20.4 ↔ 1.21.11 axis is collapsed, this lockstep doc remains the
contract for IRLite ↔ redactor (1.20.4); Stonecutter directives in
`ShadowRenderer.java` will only affect the GL leaf inside each per-mod copy,
not the cross-mod parity captured here.
