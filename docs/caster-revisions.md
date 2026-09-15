# Evaluated caster revisions and live overlay reuse

Core 1.1.6 adds the optional `ShadowCasterSource.revision` method. Existing
implementations return `CasterRevision.UNKNOWN` by default and keep updating
every frame. `collect` still runs once before the spot/point loops; revisions
are sampled once for each retained dynamic caster, after nearest-N selection.

A known revision covers six independent domains: world transform, evaluated
pose/bones, morph/scale, geometry, cutout/material and resource generation.
The values may be content signatures or version counters. Zero is valid.
The source promises that these values cover the actual output of *every* draw
in this bake. A last-visible-frame pose, serialized Form, tick, position or
renderer class alone does not satisfy the contract. Incomplete, view-dependent
or unsupported state is UNKNOWN. The caster stays in the dynamic layer.

`ShadowOverlayCache` compares exact caster identity and immutable member state;
collection order is irrelevant. Members include the draw arm, bounds and point
face mask. A hit additionally requires the same light/static signature, tile,
terrain list identity, double light anchor and partial-tile policy. It occurs
before static-to-live copy, dynamic draws and all derived filters.

Disappearance does not hit the cache: the existing leave/restore path runs.
Point updates keep the existing full pyramid and moment dependency chains.
This change does not introduce per-face filtering or promote dynamic casters
into static geometry. The five shadow-caster seam invariants remain in force.

Only successful draws with a current static base commit. A swallowed caster
or Immediate flush failure invalidates that light; an escaping draw/filter
failure clears both caches. Reuse waits for allocated filter resources.
Filter deletion invalidates snapshots, while point-array growth preserves old
texels through the existing copy. Tile steal, world/quality reset, disabled
shaders, disabled cache and owner eviction also invalidate snapshots.

`-Dirlite.noOverlayReuse=true` disables sampling and reuse for an A/B control.
`caster.known` and `caster.unknown` count retained dynamic casters each frame;
`sp.reuse` and `pt.reuse` count whole light overlays skipped. They are operation
counts, not time estimates. Existing draw/copy/filter counters count only work
that actually runs. The static bake budget is unchanged.

Run `tools/verify-overlays.ps1 -JavaHome 'C:/Program Files/Java/jdk-21'` for the
CPU-only cache regressions. Build and in-world rendering checks are separate.
