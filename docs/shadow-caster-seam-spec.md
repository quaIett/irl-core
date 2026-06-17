# ShadowCasterSource — frozen seam contract (v2)

> **Status: FROZEN (`readyToFreeze = true`).** Phase 1 of `plan-shadow-seam-refactor`.
> Produced 2026-06-17 by an 11-agent adversarial workflow (3 seam cartographers →
> contract author → 5 per-invariant adversaries + 1 completeness critic → reviser).
> Every one of the 5 invariants had a **major** hole found and folded in; the critic
> surfaced 6+ structural gaps (occType home, batch/scratch/setBaking trio, pass
> lifecycle, block-list cache contract) — all accepted, none wrongly rejected.
>
> **Base commits at freeze:** irl-core@59d893a, redactor port/1.21.11@1093f64,
> redactor main (1.20.4, canon), IRLite master@9953f31.
> **Canon orchestration = redactor `main` (1.20.4)** — BBS-free, has all 6 T-perf-opts,
> NOT the raw-GL rewrite. Phase 2 cuts the seam into this branch first.
>
> Line anchors below are RE-ANCHORED to the freeze commits; they drift — re-verify
> before editing. Full per-variant cartography lives in the workflow output
> (`tasks/wwyzixvf0.output`, run `wf_df826ff8-9bd`).

---

## Простое объяснение (для отчётов — дословно из плана)

> Тени отличались по двум причинам: (1) аддон умеет рисовать тень по геометрии
> **BBS-формы актёра** (кастомный морф — как в BBS-превью), а редактор рисует то,
> что есть в ванильном мире — **настоящие ванильные модели мобов/блоков** (НЕ коробку!).
> Важно: на 1.20 редактор даёт правильный силуэт; «коробка» от энтити — это регрессия
> ПОРТА 1.21.11 (там render-API переписан на raw-GL и энтити временно упрощён до
> bounding-box), а не поведение редактора как такового. (2) редактор просто свежее по
> оптимизациям.
>
> Идея: **«как нарисовать тень от каста» — это единственная BBS-завязка**, выносим её
> за интерфейс (`ShadowCasterSource`) — как мы уже сделали с патчером (`PatcherHost`).
> Аддон подставляет «рисуй форму», редактор — «рисуй бокс». **Вся остальная логика теней
> (раскладка карт глубины, планировщик, оптимизации) — общая.** Бонус: при общей
> оркестрации IRLite **получает все 6 оптимизаций даром**.

---

## Architecture

```
shared (orchestration, MC-typed)                         ← stays identical across all variants
  ├─ spot-atlas / point-cube-array layout (SpotlightDepthAtlas, PointShadowArray — already separate)
  ├─ collect() called ONCE per bake → fills fixed-32 SoA via OccluderSink
  ├─ cull (range / insideCone / sphereTouchesFace), T1.1 shortlist, sticky tiles
  ├─ 2-layer bake (static base → GPU copy → dynamic overlay), dirty caches, all 6 T-opts
  ├─ per-pass: begin*() → [ emitOccluder × N ] → flush (once, success path) → endPass()
  ├─ INV enforcement: setBaking gate, scratch MatrixStack, try/catch + terminateRun,
  │                    last-before-draw matrix re-establish, flush-vs-block ordering
  └─ block cast (cutout + opaque AABB) → ShadowOccluders.drawBlockOccluders  (BBS-FREE, shared)
        ▲ calls only the two seam methods ▼
   ShadowCasterSource (THE seam)
        ├─ redactor-main → real vanilla model (EntityRenderDispatcher.render)
        ├─ redactor-port → inflated box blob into raw POSITION depth-VBO  (regression; restorable)
        └─ IRLite        → BBS Form / Film / Morph silhouettes (mchorse)
```

The block cast (cutout/AABB) is BBS-free on all three variants and flows through a
**separate** path keyed by `LightRegistry` id with its own per-light VBO caches — it
never touches `occ[]`. It is therefore a shared helper, **NOT** part of the BBS seam.

---

## The shared occluder model (must be preserved)

Orchestration holds casters as a faceless Struct-of-Arrays, `MAX_OCCLUDERS = 32`,
**fixed arrays, deliberately no per-frame allocation**:

| field | type | meaning |
|---|---|---|
| `occ[]` | `Object` | faceless caster handle (`Entity` \| `ModelBlockEntity` \| `IEntity` \| box-stub); orchestration never unwraps it |
| `occType[]` | `int` | draw-arm tag only — `CasterType.ENTITY/MODEL_BLOCK/REPLAY` |
| `oStatic[]` | `boolean` | **NEW (CHANGE 2)** — static-layer membership, *independent* of `occType` |
| `ox/oy/oz[]` | `float` | bounding-sphere center (`oy` raised to mid-height) |
| `orad[]` | `float` | bounding-sphere radius (circumscribing; see INV-5) |
| `ostatichash[]` | `long` | full-avalanche silhouette signature for a static caster, else `0L` |
| `occCount` | `int` | fill cursor (reset each `collect()`) |

---

## Frozen interfaces

> Package shown is the redactor-side `org.qualet.irlredactor.light.shadow` (Phase 2
> cuts here first). It neutralizes when the orchestration is extracted to a shared
> module (Ф4). `CasterType` is a **neutral holder** — sources must NOT import the GL
> class `ShadowRenderer` (CHANGE 8).

### `ShadowCasterSource` — the only thing a variant supplies

```java
public interface ShadowCasterSource {

    /**
     * WHAT casts. Called ONCE per bake by the shared orchestration, BEFORE any begin*()
     * pass and before both the spot and point loops; the SoA is then stable for the whole
     * bake. The impl walks its world/scene and calls one sink.emit*() per in-range caster.
     *
     * For every emitted caster the impl MUST:
     *   - pass the caster as Object (faceless; only emitOccluder unwraps it);
     *   - set occType (draw-arm selector ONLY);
     *   - set isStatic (INV-2): true ONLY for a caster baked into the never-rebaked static
     *     layer whose silhouette changes ONLY when staticHash changes. A caster whose
     *     silhouette can vary on ANY input not folded into staticHash (animation clock,
     *     time, external pose) MUST be isStatic=false, even if it is geometrically a model
     *     block. occType and isStatic are INDEPENDENT axes;
     *   - emit the bounding sphere in the PINNED convention (INV-5) — prefer emitFromBox,
     *     which computes it for you;
     *   - supply staticHash (INV-3): a FULL-AVALANCHE signature over the caster's COMPLETE
     *     static silhouette state for an isStatic caster, else EXACTLY 0L.
     *
     * Source-side robustness (e.g. BBS reflection/accessor try/catch in IRLite) lives
     * INSIDE this impl, never in the shared sink. A throwing collect for one caster
     * degrades to "that caster is absent", never aborts the bake.
     */
    void collect(ClientWorld world, Vec3d camPos, float tickDelta, OccluderSink sink);

    /**
     * HOW to draw ONE shortlisted caster for the current pass. The shared layer has ALREADY
     * bound FBO+viewport+scissor, built/cached the light projection, set the light view onto
     * ambient currentView/currentProj + live RenderSystem, pinned depth state, raised
     * ShadowBakeState.setBaking(true), reset the shared scratch MatrixStack, and OPENED the
     * batch. The impl ONLY emits geometry into `batch`. It MUST NOT touch FBO/tile/scissor,
     * MUST NOT toggle setBaking, MUST NOT flush/terminate the batch, MUST NOT swallow its
     * own exceptions.
     *
     * This is an EMIT/APPEND op, not a self-contained draw. One pass calls emitOccluder for
     * every shortlisted+filtered caster, then the SHARED layer flushes `batch` exactly once
     * on the SUCCESS path (the single-draw batching win). An impl MAY draw immediately if its
     * batch kind supports it, but the canonical contract is append-then-shared-flush.
     *
     * Light view/proj are NOT params (CHANGE 1): no current variant consumes them as params
     * (main/IRLite ride live RenderSystem modelview; the port reads the ambient
     * currentView/currentProj static fields). Any draw needing explicit matrices reads them
     * from the same ambient fields the block helper reads.
     *
     * INV-1 (matrix corruption): this call MAY dirty live RenderSystem matrices — ALLOWED.
     * The shared layer repairs them (conditional rule below). The impl NEVER repairs matrices.
     * INV-4 (exception + run isolation): this method MAY throw. The SHARED wrapper owns the
     * try/catch and, on a throw, terminates the open batch run BEFORE the next emitOccluder.
     */
    void emitOccluder(Object caster, int type, float tickDelta, OccluderBatch batch);
}
```

### `OccluderSink` — allocation-free SoA writer

```java
public interface OccluderSink {

    /**
     * BLESSED PATH: append one BOX-shaped occluder, COMPUTING the cull-pinned bounding sphere
     * internally so the source cannot get it wrong (INV-5, CHANGE 5). Allocation-free;
     * silently dropped if the SoA is full.
     *
     * sphere center = (interpX, interpY + boxHeight*0.5, interpZ)   // cy RAISED to mid-height
     * sphere radius = 0.5 * sqrt(dx*dx + dy*dy + dz*dz) * scale + OVERLAP_MARGIN
     *                 // dx/dy/dz = box edge lengths → half the box DIAGONAL (circumscribing),
     *                 // times scale for transform-scaled casters, + OVERLAP_MARGIN as slack.
     * For a ROTATED caster the source MUST pass a box that already encloses the rotated geometry.
     */
    void emitFromBox(Object caster, int type, boolean isStatic,
                     double interpX, double interpY, double interpZ,
                     Box box, float scale, long staticHash);

    /**
     * RAW escape hatch for a NON-box caster: append with a pre-computed sphere. Allocation-free;
     * silently dropped if full. The sink CANNOT validate the floats, so the bounding-sphere
     * convention (INV-5) is here a SOURCE obligation. Prefer emitFromBox whenever a Box exists.
     *
     * cx/cy/cz = sphere center (cy RAISED to mid-height); radius = CIRCUMSCRIBING radius
     * (half box DIAGONAL, post-scale/post-rotate) + OVERLAP_MARGIN — NOT a half-edge.
     */
    void emit(Object caster, int type, boolean isStatic,
              float cx, float cy, float cz, float radius, long staticHash);
}
```

### `OccluderBatch` — opaque batch handle + shared INV-4 hooks

```java
public interface OccluderBatch {
    // OPAQUE to the source: each variant downcasts to its known backend (vanilla Immediate
    // for main/IRLite; raw POSITION FloatBuffer VBO for the port). The shared wrapper uses
    // these hooks to enforce INV-4 run-isolation — the source never calls them.
    long mark();                                     // snapshot start-of-this-caster write position
    void terminateRun(Matrix4f view, Matrix4f proj); // on a throw: drain Immediate (re-assert
                                                     //   view/proj) OR rewind raw buffer to the mark
    // NOTE: exact mark()/terminateRun() shape is implementer latitude (residual #1); the
    //       contract requires only that the wrapper can terminate a partial run at a quad
    //       boundary before the next emit. Deferred-flush (append-at-emit, draw-at-endPass)
    //       batch kind is permitted ONLY for non-corrupting raw-VBO caster paths.
}
```

### `CasterType` — neutral tag holder (CHANGE 8)

```java
public final class CasterType {           // NOT on ShadowRenderer — sources must not import GL
    public static final int ENTITY = 0;
    public static final int MODEL_BLOCK = 1;
    public static final int REPLAY = 2;
    private CasterType() {}
}
```

### `ShadowOccluders.drawBlockOccluders` — shared block helper (OUTSIDE the BBS seam)

```java
final class ShadowOccluders {
    /** Draw a light's WORLD-BLOCK occluders into the bound depth FBO, between begin*()/endPass().
     *  Opaque entries → AABB triangles; cutout entries → textured BakedModel quads with atlas
     *  alpha-discard. BOTH draw with the light's OWN view/proj read from the ambient
     *  currentView/currentProj fields, NEVER live RenderSystem modelview (INV-1 CLAUSE 2). The
     *  cutout arm re-establishes currentView/currentProj before any RenderLayer.startDrawing loop.
     *
     *  CACHE CONTRACT (CHANGE 11): the per-light VBO is cached and rebuilt ONLY on block-list
     *  INSTANCE change (lastBlocks.get(lightId) != blocks — reference identity, the de-facto
     *  "staticHash" for world blocks on every variant). The CALLER MUST pass a STABLE List
     *  instance per light, rebuilt only when the block set changes; rebuilding it every frame
     *  silently defeats the cache (a perf cliff, not a correctness bug). */
    static void drawBlockOccluders(long lightId, List<BlockShadowEntry> blocks);
}
```

`drawBlockOccluders` is THE primary INV-1 CLAUSE-2 enforcement site (it draws strictly via
the explicit ambient matrices). It carries only `(lightId, blocks)` — no view/proj param —
because it reads the ambient fields `begin*()` already set.

---

## The 5 load-bearing invariants (final, hardened)

> A violation **compiles fine and emits no GL error** — it is visible only in-world. This is
> the entire reason for the adversarial pass and the mandatory in-world gate after Ф2/Ф3.

### INV-1 — Matrix corruption (conditional re-establish, last-before-draw, pinned ordering)

**CONDITIONAL** (CHANGE 1, was an unconditional MUST that did not hold on the port).
IF a variant's `emitOccluder` draw can dirty live RenderSystem modelview/projection (vanilla
`EntityRenderDispatcher` in main, BBS Form/Morph in IRLite) AND that draw is a buffered
Immediate flushed by the shared layer, THEN:
- **CLAUSE 1:** the shared flush method MUST re-establish the light's view/proj as its
  **IMMEDIATE LAST action before the caster draw** — after this and every other caster's emit,
  with NO `emitOccluder` between the re-establish and the draw. A re-establish performed only
  at batch-OPEN is **insufficient and forbidden** as the sole guard (a later emit clobbers it).
- **CLAUSE 2:** the shared bake loop MUST perform that caster flush **BEFORE** invoking
  `drawBlockOccluders` in the same `begin*()/endPass()` bracket; and `drawBlockOccluders` MUST
  draw every opaque AABB and cutout block via the ambient currentView/currentProj (never live
  modelview), the cutout arm re-establishing those before any `RenderLayer.startDrawing` loop.
- **EXEMPTION:** a self-contained raw-GL caster that never touches RenderSystem matrices (the
  port box blob) has nothing to corrupt and MAY defer its flush past the block pass to `endPass`.

*Failure:* the buffered caster draws through modelview a later emit corrupted → entity/form
silhouette lands at a garbage transform and casts NO shadow, while block shadows (independently
guarded) still look fine — masking the bug.
*Enforced by:* SHARED. main `flushCasterBatch` (re-load currentProj + loadIdentity +
multiplyPositionMatrix(currentView) + applyModelViewMatrix, THEN `immediate.draw`), flush inside
`renderInRangeCone` BEFORE `renderBlocksDepth`. IRLite: per-caster `immediate.draw` is HOISTED to
one shared flush doing the same last-before-draw re-establish (NEW vs today). port: EXEMPT.

### INV-2 — Static vs dynamic split (decoupled from the draw-path tag)

**DECOUPLED** (CHANGE 2, was `CASTER_MODEL_BLOCK == static`, which froze any model-block
carrying a playing animation with a constant Transform). `occType` selects **only** the
`emitOccluder` draw arm. The static/dynamic split is driven by a **separate per-caster boolean
`isStatic`** on `OccluderSink.emit*` / `oStatic[]`. A source MUST set `isStatic=false` for ANY
caster whose silhouette can change without changing its bounding sphere or `staticHash` (a
playing animation clock, time, external pose), even if it is geometrically a `MODEL_BLOCK`.

*Failure:* an animated model-block (rotating fan, waving banner, breathing statue morph) left
`isStatic=true` bakes ONCE into the static tile; its shadow FREEZES at the first pose while the
model visibly animates. (Converse: a static caster marked dynamic re-bakes every frame — perf
loss only.)
*Enforced by:* SPLIT. SOURCE sets `isStatic` (+`occType`) in `collect`. SHARED branches the
split on `oStatic[k]` (NOT `occType[k]==MODEL_BLOCK`): `scanInRange` folds+counts statics where
`oStatic[k]`; `casterMatches(STATIC/DYNAMIC/ALL)` tests `oStatic[k]` for the SPLIT while still
keying the draw arm on `occType` inside `emitOccluder`.

### INV-3 — staticHash (avalanche-mixed, count-folded, injective over the static multiset)

**HARDENED** (CHANGE 3, was "order-independently SUMS" — a plain additive, non-injective fold).
A static caster supplies a FULL-AVALANCHE per-silhouette signature over its COMPLETE static
state (form/identity + center + EVERY Transform component — scale, rotate, **translate**).
Non-static casters supply EXACTLY `0L`. The shared layer combines the in-range static hashes via
an order-independent **avalanche-mixed accumulator** (never plain addition) AND folds the
in-range static **COUNT** into the per-light signature.

*Failure (old additive sum):* (a) a balanced two-prop editor edit (multi-select A+B, drag-rotate
with equal-and-opposite deltas) leaves Σ unchanged → dirty test reports CLEAN → bake skipped →
both shadows FROZEN indefinitely (sticky tile reused); (b) a membership swap preserving the hash
total leaves the shadow at a vacated caster's location.
*Enforced by:* SPLIT. SOURCE owns hash CONTENT + full-avalanche stability (IRLite
`modelBlockHash` = avalanche over `identityHashCode(form)` + center + Transform scale.xyz /
rotate.xyz / translate.xyz; editor = box/blockstate hash; dynamic = `0L`). SHARED owns the fold:
`staticOccSig ^= mix64(ostatichash[k] * 0x9E3779B97F4A7C15L)` + fold the static count into the
per-light `sig` together with `lightGeomSig`, tested against the `lastSig` cache.

### INV-4 — Exception isolation AND vertex-run isolation in the shared wrapper

**STRENGTHENED** (CHANGE 4, was crash-isolation only). The per-caster try/catch MUST live in the
SHARED wrapper, never in the source. On a throw the wrapper MUST **terminate the open batch run
BEFORE the next `emitOccluder`**: drain an Immediate batch (re-asserting light matrices first per
INV-1) or rewind a raw-buffer batch to the start-of-this-caster mark it snapshotted before the
call. This recovery termination is **separate from and additional to** the once-per-pass success
flush; "flush exactly once" describes the SUCCESS path only. An impl drawing into a raw buffer
MUST advance it only by WHOLE casters, so the rewind mark is always at a quad boundary.

*Failure:* caster B writes a half-quad then throws; the wrapper catches (crash isolated) but
caster C appends after B's dangling run; the single success flush fuses B's stray vertices with
C's into a garbage triangle → a phantom shadow streak. Masked on main/port (throw-free pure-math
`emitBox`); bites only the IRLite throwing-BBS-render arm the contract exists to enable.
*Enforced by:* SHARED wrapper (the call SITE of `emitOccluder`); it snapshots `batch.mark()`
before each emit and calls `terminateRun` in its catch.

### INV-5 — Bounding-sphere convention (circumscribing radius from the rotated box diagonal)

**CORRECTED** (CHANGE 5, was "half the max box edge + OVERLAP_MARGIN" — a half-edge that
UNDER-bounds large/anisotropic/rotated casters and was frozen as guaranteed-correct). Every
sphere MUST CIRCUMSCRIBE the geometry `emitOccluder` draws: center = (interp x, y RAISED to
mid-height, interp z); radius = `0.5 * sqrt(dx² + dy² + dz²)` (half the box DIAGONAL over the
FULL edge lengths of the drawn / inflated / post-scale / **post-rotate** AABB) + `OVERLAP_MARGIN`
as pure slack. The cull consumes `orad` (range, `insideCone`) and `orad*SQRT2`
(`sphereTouchesFace`). A sub-bounding sphere makes the conservative cull SILENTLY DROP valid
casters near the boundary.

*Failure:* a 4-block Ghast — old half-edge radius `2.0+0.5=2.5` vs true circumscribing
`2.0*√3≈3.46`; its corner pokes into a 20° cone ~35–40° off-axis but the 2.5 sphere fails
`insideCone`, so the Ghast is never shortlisted and casts NO shadow while it lingers in that band.
Same for anisotropic IRLite forms (scale 4,1,4: true corner 2.87 vs old 2.5) and ANY rotation
(absent from the old formula).
*Enforced by:* SPLIT, with `emitFromBox` structurally pinning the convention. SOURCE supplies the
rotation-expanded `Box`; `emitFromBox` computes `rad` + mid-height `cy`. Canonical `collect` bodies
change from `max-edge*0.5` to the diagonal form. SHARED cull unchanged. `OVERLAP_MARGIN` demoted
to pure slack (no longer the corner-coverage term).

---

## Per-variant implementation sketches

### redactor-main (1.20.4) — CANON, real vanilla model

- **collect():** per `LivingEntity`/`ItemEntity` within `COLLECT_DIST`:
  `sink.emitFromBox(entity, CasterType.ENTITY, false, ex, ey, ez, entity.getBoundingBox(), 1f, 0L)`.
  No MODEL_BLOCK/REPLAY arm — all dynamic, hash 0.
- **emitOccluder():** `switch(type)` has only the ENTITY arm: lerp pos + yaw →
  `EntityRenderDispatcher.render(entity, cx,cy,cz, yaw, tickDelta, sharedScratchStack, (Immediate)batch, FULL_LIGHT)`.
  Rides live RenderSystem modelview and corrupts it (INV-1 trigger). Scratch stack owned+reset by
  the shared wrapper (CHANGE 7).
- **static/dynamic:** always ENTITY + `isStatic=false` ⇒ always dynamic; the static machinery is
  inert-but-correct. The flush lives in `renderInRangeCone` (per dispatch call), running BEFORE the
  block pass — the corrupting-variant ordering INV-1 CLAUSE 2 mandates.

### redactor-port (1.21.11) — degenerate box blob, raw-GL (verified in source)

- **collect()** (ShadowBaker L1157-1196): filter `LivingEntity||ItemEntity` within
  `COLLECT_DIST_SQ`; `sink.emitFromBox(entity, CasterType.ENTITY, false, ex, ey, ez,
  entity.getBoundingBox(), 1f, 0L)`. The direct SoA writes + MAX_OCCLUDERS guard move into the sink;
  the `max-edge*0.5` radius (L1186) becomes the diagonal form inside `emitFromBox`.
- **emitOccluder()** (renderCaster L253-292): guard `instanceof Entity`, compute the
  `ENTITY_BOX_INFLATE` box, APPEND 36 verts via `emitBox` into the raw `casterBuf` FloatBuffer. Does
  NOT draw — `batch` is the raw depth-VBO accumulator, flushed once by `flushCasterBoxes` at
  `endPass` (L1038, AFTER `renderBlocksDepth`).
- **INV-1 EXEMPT:** the box leaf never touches RenderSystem modelview, so its after-the-block-pass
  deferred flush is legal. A FUTURE model-restore arm needs mark+rewind PLUS a setBaking gate (absent
  today), a textured POSITION+UV immediate (`casterBuf` is POSITION-only), and a scratch stack — a
  larger lift than "flip on params", which is why params are dropped (CHANGE 1) not frozen as dead weight.

### IRLite (1.20.1/1.20.4) — BBS Form/Film/Morph (exercises INV-2/3/4)

- **collect():** union of three arms. Entity → `emitFromBox(.., ENTITY, false, .., 0L)`.
  Model-block → per `ModelBlockEntity` with enabled `ModelProperties`, pull Form+Transform, emit a
  **rotation-expanded** box and `emitFromBox(mbe, MODEL_BLOCK, isAnimated ? false : true, .., Transform.scale,
  isStatic ? modelBlockHash : 0L)` where `modelBlockHash` = avalanche over
  `identityHashCode(form)+center+Transform.scale/rotate/translate` (CHANGE 3). Replay → per active
  Film replay, `emitFromBox(stub, REPLAY, false, .., 0L)`. **All BBS reflection/accessor try/catch
  stay INSIDE this impl** (INV-4 scoping).
- **emitOccluder():** the `switch` from `renderCaster` MINUS the shared wrapper. ENTITY→drawEntity
  (BBS MorphRenderer, vanilla fallback); MODEL_BLOCK→drawModelBlock; REPLAY→drawReplay. Each pushes
  a translate onto the SHARED scratch stack and rides live RenderSystem modelview.
- **Behavioral change the freeze spells out:** per-caster `immediate.draw` migrates to ONE shared
  flush (the T2.2 batching win) ⇒ the shared flush now needs the **last-before-draw re-establish
  IRLite does NOT need today** (today per-caster draw is adjacent to `applyMatrices`), and INV-4
  granularity goes per-caster → per-batch-with-terminateRun. The wrapper owns setBaking, scratch
  reset, try/catch+terminateRun, and the re-establish (the trio resolved together, CHANGE 6).

---

## Re-anchored seam anchors (freeze commits — re-verify before editing)

### redactor-main (1.20.4) — ShadowBaker 1213 ln / ShadowRenderer 901 ln

| role | file:lines | method | bbs |
|---|---|---|---|
| soa-fields | Baker:54-66 | occluder SoA decls | no |
| occType-const | Renderer:201-203 | CASTER_* | no |
| collect | Baker:1172-1212 | collect() (only ENTITY emitted) | no |
| bounding-sphere | Baker:1201-1208 | collect() sphere math | no |
| draw-leaf | Renderer:237-260 | bufferCaster() | no |
| draw-leaf | Renderer:306-318 | drawEntity() → EntityRenderDispatcher.render | no |
| draw-dispatch | Baker:1083-1096 / 1103-1121 | renderInRangeCone / renderInRangeFace | no |
| batch-open/close | Renderer:220-230 / 264-277 | beginCasterBatch / endCasterBatch | no |
| matrix-reset | Renderer:289-304 | flushCasterBatch (INV-1 re-establish) | no |
| exception-catch | Renderer:246-259 | bufferCaster try/catch (INV-4) | no |
| matrix-reset | Renderer:421-433 | renderBlocksDepth VBO draw (explicit view/proj) | no |
| block-cast | Renderer:367-454 / 549-734 | renderBlocksDepth / renderBlocksDepthCutout | no |
| scan/cull | Baker:998-1055 | scanInRange (T1.1 shortlist) | no |
| static-hash | Baker:979-987 | lightGeomSig + ostatichash fold | no |
| filter | Baker:1060-1075 | casterMatches + CASTERS_* | no |

### redactor-port (1.21.11) — ShadowBaker 1199 ln / ShadowRenderer 1090 ln

| role | file:lines | method | bbs |
|---|---|---|---|
| collect | Baker:1157-1198 | collect() (ENTITY only) | no |
| bounding-sphere | Baker:1186-1193 | collect() sphere write | no |
| occType-const | Renderer:118-120 | CASTER_* | no |
| draw-leaf | Renderer:253-292 | renderCaster() → emitBox append | no |
| draw-dispatch | Renderer:298-363 | flushCasterBoxes() (batched raw-GL draw) | no |
| exception-catch | Renderer:315-351 | flushCasterBoxes try/catch | no |
| matrix-reset | Renderer:323-329 | currentProj*currentView → uViewProj upload | no |
| block-cast | Renderer:407-423 / 426-575 / 615-700 | renderBlocksDepth / drawOpaqueBlocks / cutout | no |
| renderInRange | Baker:1073-1084 / 1090-1106 | renderInRangeCone / Face | no |
| scan/hash | Baker:992-1048 / 973-981 | scanInRange / lightGeomSig | no |
| pass-lifecycle | Renderer:127-231 | beginBake / beginSpot / beginPointFace / endPass | no |
| budget | Baker:749-762 | allowStaticBake + staticBakeBudget (T2.4) | no |

### IRLite (1.20.1/1.20.4) — ShadowBaker 1340 ln / ShadowRenderer 782 ln

| role | file:lines | method | bbs |
|---|---|---|---|
| soa-fields | Baker:75-87 | occluder SoA decls | no |
| occType-const | Renderer:186-188 | CASTER_* | no |
| **draw-dispatch** | **Renderer:190-233** | **renderCaster() → drawEntity/drawModelBlock/drawReplay** | **YES** |
| **draw-leaf** | **Renderer:235-268 / 270-301 / 303-327** | **drawEntity / drawModelBlock / drawReplay** | **YES** |
| exception-catch | Renderer:208-232 | renderCaster try/catch (the wrapper) | no |
| matrix-reset | Renderer:764-776 | applyMatrices | no |
| block-cast | Renderer:350-437 / 510-638 | renderBlocksDepth / renderBlocksDepthCutout | no |
| **collect** | **Baker:1068-1114** | **collect() (entity + delegates)** | **YES** |
| **collect** | **Baker:1116-1202 / 1235-1321** | **collectFilmReplays / collectModelBlocks** | **YES** |
| **static-hash** | **Baker:1326-1339** | **modelBlockHash (form-identity + Transform)** | **YES** |
| scan/cull | Baker:883-917 / 1031-1066 | scanInRange / insideCone | no |
| renderInRange | Baker:968-990 / 996-1017 | renderInRangeCone / Face | no |
| filter | Baker:926-937 | casterMatches | no |

**The entire BBS coupling is these 7 methods** (4 collect-side + 3 draw-leaf) + their dispatcher.
Everything else is variant-agnostic orchestration the editor inherits unchanged.

---

## What the adversarial pass found (changelog — all ACCEPTED, none wrongly rejected)

1. **INV-1** — v1 left the re-establish TIMING and flush-vs-block ORDERING unpinned and conflated
   the block guard with the caster-flush guard. *Verified the port flushes casters AFTER the block
   pass.* → rewritten as conditional CLAUSE 1 (last-before-draw, batch-open forbidden as sole guard)
   + CLAUSE 2 (caster flush before `drawBlockOccluders` for corrupting variants; raw-GL port exempt).
2. **Critic** flush-site / INV-1-not-true-on-port — same root; two legal flush shapes named.
3. **INV-2** — split keyed solely on `occType==MODEL_BLOCK`; an animated model-block had no legal
   dynamic tag. → decoupled via `isStatic` boolean + `oStatic[]`; orchestration branches on it.
4. **INV-3** — `sig += ostatichash[k]` is additive/non-injective; the static COUNT wasn't folded.
   → avalanche-mixed accumulator + count fold; "full-avalanche over COMPLETE silhouette incl translate".
5. **INV-4** — guaranteed crash-isolation but not vertex-run isolation; "flush exactly once" made the
   recovery flush look forbidden. → terminate-run-before-next-emit + `OccluderBatch.terminateRun()` +
   whole-caster-advance rule.
6. **INV-5** — `max-edge*0.5` half-edge UNDER-bounds large/anisotropic/rotated casters (Ghast drops).
   → true circumscribing diagonal radius (post-scale/post-rotate) + full-containment MUST.
7. **Critic** sink can't ENFORCE the sphere → added `emitFromBox` (structural enforcement) + withdrew
   the "sink pins it" over-claim for raw `emit()`.
8. **Critic** occType constants lived on the GL class → relocated to neutral `CasterType`.
9. **Critic** batch/scratch/setBaking trio + view/proj DEAD on all 3 variants → resolved together;
   `emitOccluder(caster, type, tickDelta, batch)`; explicit matrices read from ambient fields.
10. **Critic** pass-lifecycle + static-overlay ordering → `collect()` called once before any begin*();
    SoA stable for the whole bake; source oblivious to which filter/layer feeds it (disjoint by `oStatic`).
11. **Critic** OccluderBatch opaque vs typed → kept opaque + impl-downcast, added the INV-4 hooks.
12. **Critic** block-list-instance cache contract unstated → documented (stable List instance per light).
13. **Critic** IRLite per-caster-draw→shared-flush migration + main flush ownership + cross-pass
    re-emission → spelled out in the per-variant sketches (one caster → exactly one layer).

---

## Residual open questions (implementer latitude — NOT blockers)

1. **INV-3 avalanche mix** — exact `mix64` rounds/constant and whether the static-count fold is
   xor-mixed or added are unspecified. Any order-independent + non-linear + paired-edit-collision-
   resistant fold is conformant.
2. **Port real-model RESTORE** — out of scope for the freeze. CHANGE 1 keeps the seam ready (draws
   read ambient matrices, no dead params), but reviving vanilla/textured models on 1.21.11 needs a
   `setBaking` gate, a 1.21.11 textured POSITION+UV Immediate, and a per-caster scratch stack — flagged
   for whoever revives that arm; does NOT affect the frozen signatures.

---

## How to use this spec (entering Ф2 / Ф3)

- **Ф2 (врезать шов в redactor-main, BBS-free, lower risk):** introduce `ShadowCasterSource` +
  `OccluderSink` + `OccluderBatch` + `CasterType` + `ShadowOccluders`; move `collect`'s body and
  `bufferCaster`→`drawEntity` behind a `RedactorEntityCasterSource` impl; the orchestration calls
  only the two seam methods. **Behavior must not change** — in-world gate: shadows visually identical.
- **Ф3 (IRLite onto the shared orchestration + BBS impl, highest risk):** port redactor-main's
  seamed orchestration into IRLite, implement `ShadowCasterSource` with the BBS Form/Film/Morph arms.
  IRLite inherits the 6 perf opts. In-world gate by invariant: block shadows present (INV-1), moving
  forms don't freeze (INV-2), shadow matches the BBS preview.
- The 5 invariants fail **silently at compile time** — the in-world gate after Ф2 and Ф3 is mandatory.

---

## Erratum — INV-1 vs self-drawing BBS forms (added 2026-06-17, Ф3 audit)

INV-1 CLAUSE-1 above is written for the **buffered** caster path: a vanilla
`EntityRenderDispatcher.render` / `MorphRenderer` draw that *buffers* into the shared
`Immediate` and is transformed by the live `RenderSystem` modelview **at flush time** —
which is why the shared layer re-establishes the light view/proj as its last action
**before the flush** (`flushCasterImmediate`).

The Ф3 IRLite audit established that **most BBS forms do NOT buffer** — `ModelForm`/BOBJ
(`ModelVAORenderer` → `glDrawArrays`) and several others **self-draw synchronously inside
`emitOccluder`**, reading the live `RenderSystem` modelview **at emit time**, *before* the
end-of-batch re-establish runs. `MobFormRenderer.render3D` further leaves the live modelview
at identity without restoring it. Consequences for a single light pass containing **more than
one self-drawing form** (e.g. a MobForm-morphed entity emitted before a model-block/replay form):

- The end-of-batch re-establish does **not** cover them — each self-drawing form rides whatever
  modelview the *previous* caster left. A form drawn after a modelview-corrupting caster can land
  at a garbage transform and cast a wrong/absent shadow, with no GL error (block shadows, on the
  explicit-matrix path, still look fine — the classic INV-1 masking pattern).

This exposure is **PRE-EXISTING** — old IRLite's per-caster `renderCaster` also never re-established
the light modelview between self-drawing casters (it relied on the single pass-begin `applyMatrices`).
The Ф3 seam port preserves the behavior exactly; it is **not** a regression and did not block Ф3.

**Hardening (scheduled, applies to BOTH variants to stay unified):** in the shared `emitCaster`
wrapper, re-establish the light view/proj (the `flushCasterImmediate` matrix prologue **without**
`immediate.draw()`) immediately **before each** `source.emitOccluder` call, so every self-drawing
form starts from the clean light modelview. Harmless/idempotent for the buffered redactor path
(its draws read the passed `MatrixStack`, not the global modelview, until flush); necessary for
IRLite's self-drawing forms. This also closes the matching latent hole in old IRLite. Verify via the
mixed-caster in-world scene (a MobForm entity + a model block under one light).
