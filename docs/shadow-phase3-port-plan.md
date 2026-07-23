I have the frozen contract and all four maps. The plan is synthesized below — I have everything needed; no further file reads are required since the maps already carry verified line anchors.

---

# Ф3 Port Plan — IRLite onto the canon seamed shadow orchestration

**Single source of truth for the implementation workflow.** Canon = redactor `main@522c1f0` (1.20.4). Target = IRLite `master@9953f31`. Spec = `C:/Users/Qualet/Documents/Project/Minecraft/BBS/irl-core/docs/shadow-caster-seam-spec.md` (5 invariants). All paths absolute.

Two roots involved:
- **CANON (copy FROM):** `C:/Users/Qualet/Documents/Project/Minecraft/BBS/IRL-redactor/src/client/java/org/qualet/irlredactor/light/shadow/`
- **TARGET (write TO):** `C:/Users/Qualet/Documents/Project/Minecraft/BBS/IRLite/src/client/java/qualet/irlite/client/light/shadow/`

Package rename rule applied to every copied file: `org.qualet.irlredactor.light.shadow` → `qualet.irlite.client.light.shadow`.

---

## 0. Pre-flight invariants (read before touching code)

The 5 invariants fail **silently at compile time** and are visible only in-world (spec lines 234, 502). IRLite is the **only** variant where INV-2/3/4 are NON-INERT (Map D summary; spec line 311) — it has static model-blocks and a throwing BBS-render arm. Two pre-existing bugs in IRLite's current collect code MUST be fixed during the port, not after:
- **INV-2 freeze** (Map B risks; Map D risk #1): IRLite treats `occType==CASTER_MODEL_BLOCK` as static → animated model blocks freeze.
- **INV-5 under-bound** (Map B/D INV-5 risks; spec 315-334): all 3 IRLite arms use `max-edge*0.5` → large/anisotropic/rotated forms drop out of the cone cull.

---

## 1. SEAM FILES — copy VERBATIM (package line only)

Copy these 5 canon files into the TARGET package. **Only the `package` line changes.** No other edits.

| # | Copy FROM (canon) | Copy TO (IRLite) | Lines | Notes |
|---|---|---|---|---|
| S1 | `IRL-redactor/.../shadow/ShadowCasterSource.java` | `IRLite/.../shadow/ShadowCasterSource.java` | 54 | Imports `ClientWorld`, `Vec3d` only (Map A). Javadoc @links to `RedactorEntityCasterSource` are prose-only, not load-bearing. |
| S2 | `.../OccluderSink.java` | `.../OccluderSink.java` | 59 | Import `Box` only. Carries the INV-5 `emitFromBox` sphere math (Baker:1214-1216 body); the concrete impl lives inside ShadowBaker SINK field. |
| S3 | `.../OccluderBatch.java` | `.../OccluderBatch.java` | 28 | Import `org.joml.Matrix4f` only. `mark()`/`terminateRun(view,proj)`. |
| S4 | `.../ImmediateOccluderBatch.java` | `.../ImmediateOccluderBatch.java` | 53 | Imports `VertexConsumerProvider`, `MatrixStack`, `Matrix4f`. `terminateRun` calls same-package `ShadowRenderer.flushCasterImmediate` (depends on S7 ShadowRenderer present). IRLite's 3 BBS arms ALL draw into a vanilla Immediate, so this backend fits unchanged (Map C ImmediateOccluderBatch note; Map D ImmediateOccluderBatch). |
| S5 | `.../CasterType.java` | `.../CasterType.java` | 21 | NO imports. Constants `ENTITY=0/MODEL_BLOCK=1/REPLAY=2` match IRLite's old `ShadowRenderer.CASTER_*` (Renderer:186-188) 1:1, so switch arms map directly (Map C CasterType note). |

**NOT copied:** `RedactorEntityCasterSource.java` (canon RES) — it is the entity-only degenerate CAST half. It is **replaced** by the new IRLite BBS source in §3. Use it as a structural template only (Map A copyability=`replace`; Map D RES note: keep IRLite's MorphRenderer-first drawEntity, do NOT substitute the redactor dispatcher-only version).

---

## 2. ORCHESTRATION — bring canon ShadowBaker + ShadowRenderer into IRLite

**Strategy: copy-and-adapt (full file replacement), NOT in-place merge.** IRLite's current ShadowBaker (1340 ln) / ShadowRenderer (782 ln) are the OLD pre-seam variant and lack `oStatic[]`, T1.1 shortlist, `lastFaceDynamic`, `STATIC_COUNT_MIX`, the seam types, and `flushCasterImmediate` (Map B summary + divergences). Cherry-picking the perf opts piecemeal would re-derive the orchestration and risk leaving IRLite's additive INV-3 fold in place (Map D risk "STATICHASH FOLD LOCATION"). Replace wholesale to inherit all 6 T-opts + the hardened folds for free.

### S6 — ShadowBaker.java (canon `IRL-redactor/.../shadow/ShadowBaker.java`, 1251 ln → `IRLite/.../shadow/ShadowBaker.java`)

Copy verbatim, then apply EXACTLY these adaptations:

1. **Package line:** → `package qualet.irlite.client.light.shadow;`
2. **LightRegistry import retarget:** `org.qualet.irlredactor.light.LightRegistry` → `org.qualet.irl.light.LightRegistry` (irl-core; byte-identical contract — Map C externalDependencies, Map C summary "two LightRegistry classes are byte-identical except the package"). Method shapes are identical: `getCount/getType/getX/Y/Z/getRange/getDirX/Y/Z/getCosOuter/getShadows/getId/setShadowTile` (Map A externalDependencies).
3. **LightConfig → IrliteConfig:** retarget `org.qualet.irlredactor.light.LightConfig` to `qualet.irlite.IrliteConfig` at every call. Calls used: `shadowQuality()/shadowCache()/shadowBakeBudget()/shadowBlocks()/shadowBlockRadius()` (Map A bakeInner externalDeps; Map C IrliteConfig note). **`shadowBakeBudget()` does NOT exist in IrliteConfig today** — see §5/B5. Do NOT copy redactor's `LightConfig.java` into IRLite (it would shadow IrliteConfig and re-introduce a non-BBS config — Map C risk "irl-core asymmetry").
4. **SOURCE field (Baker:1195):** swap
   `private static final ShadowCasterSource SOURCE = new RedactorEntityCasterSource();`
   →
   `private static final ShadowCasterSource SOURCE = new IRLiteBbsCasterSource();`
   (the new file from §3). This is the ONE port edit beyond imports/config (Map C "ShadowBaker.SOURCE (field)").
5. **SINK field (Baker:1201-1229) + put (1232-1244):** copy verbatim. The `emitFromBox` INV-5 diagonal sphere (Baker:1214-1216) is load-bearing — keep the diagonal formula, do NOT revert to IRLite's old half-edge (Map A SINK note; spec 156-159).
6. **`collect()` (Baker:1246-1250):** the 2-line shim `occCount=0; SOURCE.collect(world,cameraPos,tickDelta,SINK)` — copy verbatim (Map C collect note). IRLite's old inline collect (Baker:1068-1114) does NOT survive; its body migrates into §3.
7. **No other body edits.** `scanInRange` (INV-2 split on `oStatic[k]`, Baker:1053), `casterMatches` (tests `oStatic`, not occType), the INV-3 avalanche fold (`STATIC_COUNT_MIX=0x9E3779B97F4A7C15L`, Baker:50 + fold at 339-340/529-530), `bakeInner` two-layer bake, T1.1 shortlist, T2.4 budget — all copy unchanged (Map A methods).

**Entry-point alignment (Map C):** `ShadowBaker.bake(ClientWorld, Vec3d cameraPos, Vec3d cameraForward, float tickDelta)` is **byte-identical** on both variants. IRLite's frame hook `GameRendererLightMixin` (`IRLite/.../mixin/client/GameRendererLightMixin.java`) calls this signature and needs **ZERO edits** (Map C GameRendererLightMixin note; Map C Q1). Also re-point/verify these IRLite-only callers still match canon names (Map B risk "IRLite-only callers"):
- `ShadowBaker.onShadersDisabled()` — present in canon (Map A rawNotes); verify the IRLite mixin/caller wiring.
- `BlockShadowCache.invalidateAt` wiring via `WorldBlockChangeMixin` — see §4.

### S7 — ShadowRenderer.java (canon `IRL-redactor/.../shadow/ShadowRenderer.java`, 893 ln → `IRLite/.../shadow/ShadowRenderer.java`)

Copy verbatim, then:

1. **Package line:** → `package qualet.irlite.client.light.shadow;`
2. **LightRegistry import retarget** (if referenced) → `org.qualet.irl.light.LightRegistry`. No SOURCE field here (source is a param to `emitCaster`) and no LightConfig (Map C ShadowRenderer note).
3. **No body edits.** `emitCaster` (INV-1/INV-4 wrapper, 234-256), `beginCasterBatch`/`endCasterBatch` (setBaking gate, 215-225/260-273), `flushCasterImmediate` (INV-1 last-before-draw re-establish, 295-310), pass lifecycle, `renderBlocksDepth`/`renderBlocksDepthCutout` — verbatim (Map A methods). Vanilla 1.20.x render API (`RenderLayer`/`Immediate`/`EntityRenderDispatcher.render`/`Box.maxX` fields/`RenderSystem.getModelViewStack`) is stable across 1.20.1–1.20.4 (Map C rawNotes "no new vanilla API").
4. **CASTER_* constants:** canon ShadowRenderer does NOT carry them (they moved to `CasterType`, spec CHANGE 8). IRLite's old ShadowRenderer DID (Renderer:186-188) — they vanish with the file replacement; all call sites now use `CasterType.*` (Map C divergences).

### Sibling-class reconciliation (must compile against TARGET package, NO import lines)

These are SAME-PACKAGE deps the canon ShadowBaker/ShadowRenderer reference with no import — they must already exist in IRLite's shadow package with identical APIs (Map A externalDependencies; Map B files):

| Sibling | Status | Action |
|---|---|---|
| `BlockShadowEntry` | byte-identical mod package (Map B) | none |
| `BlockShadowCollector` | byte-identical mod package (Map B) | none |
| `PointShadowArray` | identical; `copyStaticToLive(slot)` + `copyStaticFaceToLive(slot,face)` present (Map B note; T1.2 needs the per-face copy) | **verify `copyStaticFaceToLive` exists** — Map B says "no API change required here" but Map A risk flags T1.2 needs it. If absent, port it from canon. |
| `SpotlightDepthAtlas` | identical; `copyStaticToLive(tile)` present (Map B) | none |
| `ShadowBakeState` | identical (Map B/D) | none — impl must NOT toggle it (spec 123) |
| `IRLShadowQuality` | identical (Map B) | none |
| **`BlockShadowCache`** | **DIVERGES** — canon adds `CacheEntry.cx/cy/cz/cr` + exact center+radius rejection in `invalidateAt` (T2.5 sphere-exact); IRLite invalidates on section-touch alone (Map B note; Map C divergences) | **Upgrade IRLite's BlockShadowCache to the canon version** (perf-only; coarser IRLite version is correctness-safe but over-invalidates). The canon ShadowBaker assumes nothing extra from invalidateAt, so this is a clean drop-in of the richer canon file. If deferred, IRLite stays correct — flag as perf-debt (Open Q3). |

---

## 3. NEW FILE — `IRLiteBbsCasterSource.java`

**Path:** `C:/Users/Qualet/Documents/Project/Minecraft/BBS/IRLite/src/client/java/qualet/irlite/client/light/shadow/IRLiteBbsCasterSource.java`
**Declaration:** `public final class IRLiteBbsCasterSource implements ShadowCasterSource`
**Wired in:** constructed by `ShadowBaker.SOURCE` (§2 step S6.4). No other construction site.

This file re-expresses IRLite's old 7 BBS methods (Map D) behind the two seam methods. Structure mirrors `RedactorEntityCasterSource` (Map D RES note) but with 3 collect arms + 3 draw arms.

### Imports to carry in
All `mchorse.bbs_mod.*` (Map D externalDependencies), the two existing accessors `qualet.irlite.mixin.client.bbs.FilmsAccessor` + `WorldBlockEntityTickersAccessor` (Map D files; §5/B reuses them unchanged), `io.netty.util.collection.IntObjectMap.PrimitiveEntry`, vanilla entity/render types, and the seam types (same package, no import). Move the FNV helper + `FNV_OFFSET`/`FNV_PRIME` constants into this impl (Map D modelBlockHash externalDeps — these were ShadowBaker L856-860, but canon owns its own copy for the shared fold; the impl needs its OWN copy for the per-caster `modelBlockHash` CONTENT). `FULL_LIGHT = LightmapTextureManager.pack(15,15)` constant lives here (Map D drawEntity/RES).

### `collect(ClientWorld world, Vec3d camPos, float tickDelta, OccluderSink sink)` — 3 arms, in this order

> **Historical note (pre-OPEN-2):** arm order originally controlled over-cap drops. The live sink now retains the nearest 128 casters; traversal order only resolves exact equal-distance ties deterministically.

**Arm 1 — ENTITY** (from old `collect()` Baker:1068-1114):
- Walk `world.getEntities()`, keep `LivingEntity || ItemEntity` within `COLLECT_DIST`.
- Per entity, lerp `ex/ey/ez` (`lastRenderX/Y/Z`→`getX/Y/Z`), then **one call**:
  `sink.emitFromBox(entity, CasterType.ENTITY, false, ex, ey, ez, entity.getBoundingBox(), 1f, 0L)`.
- **DELETE** `occCount=0` (sink owns cursor), the `MAX_OCCLUDERS` break (sink retains the bounded nearest set), and the old local `max-edge*0.5` radius math at Baker:1097 (**INV-5 fix** — emitFromBox derives the diagonal). `isStatic=false`, `staticHash=0L` (entities always dynamic, **INV-2**). This arm is line-for-line `RedactorEntityCasterSource.collect` (Map D rawNotes).

**Arm 2 — MODEL_BLOCK** (from old `collectModelBlocks` Baker:1235-1321 + `modelBlockHash` 1326-1339):
- All BBS try/catch STAYS INSIDE this method (**INV-4 scoping**, spec 112-113).
- Enumerate model blocks via `((WorldBlockEntityTickersAccessor)(Object)world).irlite$getBlockEntityTickers()` → resolve each pos to `ModelBlockEntity` via `world.getBlockEntity(pos)`; keep enabled (`ModelProperties.isEnabled`) within `COLLECT_DIST`.
- Pull `Form` + `Transform`. Compute center: `wx=pos.getX()+0.5+t.translate.x`, `wy=pos.getY()+t.translate.y` (feet + translate; NO +0.5), `wz=pos.getZ()+0.5+t.translate.z` (Map D rawNotes confirms translate folded via center).
- **INV-2 (NON-INERT, MUST FIX):** compute `boolean isAnimated` — probe the BBS Form's playing-animation / non-static-morph state. If a cheap probe exists, `isStatic = !isAnimated`. **If no cheap probe is available, conservatively `isStatic=false` for ALL model blocks** (correct; loses the static-tile cache win — Map D risk #1; Open Q1). The old L1314-1317 "documented limitation" freeze is REVOKED (spec INV-2, line 268).
- **INV-5 (NON-INERT, MUST FIX):** build a **rotation-expanded Box** enclosing the post-scale/post-rotate geometry from `form.hitboxWidth/Height` × `Transform.scale`, then prefer:
  `sink.emitFromBox(mbe, CasterType.MODEL_BLOCK, isStatic, wx, wy, wz, rotExpandedBox, Transform.scale, isStatic ? modelBlockHash(wx,wy,wz,t,System.identityHashCode(form)) : 0L)`.
  The old radius (Baker:1296-1299) ignored `Transform.rotate` entirely — must not survive (spec 315-334). If a Box is awkward, use `sink.emit(...)` with a hand-computed half-diagonal over the rotated AABB.
- **INV-3 (NON-INERT) CONTENT:** `modelBlockHash` = FNV avalanche over `identityHashCode(form)` + center (`wx/wy/wz`, carries translate) + `Transform.scale.xyz` + `Transform.rotate.xyz` (Baker:1326-1339; rotate2 removed in BBS 2.2.1 — single rotate suffices, Map D modelBlockHash note). **The impl returns this long as per-caster CONTENT ONLY.** The order-independent avalanche `mix64` + static-COUNT fold ACROSS casters lives in the SHARED `scanInRange`/sig-combine (canon ShadowBaker, already ported in §2) — the impl must NOT do that fold (Map D risk "STATICHASH FOLD LOCATION"; spec 292-296). occType stays MODEL_BLOCK regardless of isStatic (independent axes, spec 268).

**Arm 3 — REPLAY** (from old `collectFilmReplays` Baker:1116-1202 + support helper `getActiveEditorController` 1204-1233):
- All BBS try/catch STAYS INSIDE (**INV-4**).
- `BBSModClient.getFilms()` → `((FilmsAccessor)(Object)films).irlite$getControllers()`; add the active editor controller via the private helper `getActiveEditorController()` (moves verbatim as a private static, its `Throwable→null` catch stays, gated on `MinecraftClient.currentScreen != null` — Map D getActiveEditorController).
- Per controller, iterate `ctrl.getEntities()` (`IntObjectMap.PrimitiveEntry<IEntity>`), **skip actor replays** (`replay.actor.get()` — real actors come via the entity arm).
- Lerp pos + body yaw. **INV-5:** use the form hitbox Box → `emitFromBox`, or `sink.emit(stub, CasterType.REPLAY, false, cx, cy, cz, diagonalRadius, 0L)`. Old radius (Baker:1190) was `max(hbW*0.5, ey)` — replace with diagonal. `isStatic=false`, `staticHash=0L` (replays dynamic).
- **Pick ONE cy convention** (Map D collectFilmReplays note): if using `emit()`, pass `cy=wy+ey` directly; if `emitFromBox`, pass `interpY=wy(feet)` and let emitFromBox raise mid-height. Do not double-raise.

### `emitOccluder(Object caster, int type, float tickDelta, OccluderBatch batch)` — 3 arms

From old `renderCaster` dispatcher (Renderer:190-233) **MINUS the shared scaffolding**. The core migration (Map D renderCaster):
- **DELETE** the null/inPass guard, the `depthMask/enableDepthTest/disableBlend` pins, the `setBaking(true/false)` pair, the per-caster `try/catch` (L208-232), and the per-caster `immediate.draw()` (L223). All of these are now owned by the SHARED `emitCaster`/`beginCasterBatch`/`endCasterBatch` (canon ShadowRenderer 215-273; spec 122-124).
- Body:
  ```
  ImmediateOccluderBatch b = (ImmediateOccluderBatch) batch;          // downcast
  Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
  switch (type) {
      case CasterType.MODEL_BLOCK -> drawModelBlock((ModelBlockEntity) caster, b.matrices(), b.immediate(), cam, tickDelta);
      case CasterType.REPLAY      -> drawReplay((IEntity) caster, b.matrices(), cam, tickDelta);
      default /* ENTITY */        -> drawEntity((Entity) caster, b.matrices(), b.immediate(), tickDelta);
  }
  ```
- **Does NOT** `new` a MatrixStack (uses `b.matrices()` — the shared scratch the wrapper reset, spec CHANGE 7), **does NOT** flush, **does NOT** catch its own throw (**INV-4**: the throw must propagate to the wrapper, spec 138-139, 300-306), **does NOT** repair matrices (**INV-1**: the impl appends; the shared `flushCasterImmediate` repairs, spec 136-137).

**Three private static draw-leaves — move VERBATIM** (Map D), each balanced push/.../pop on `b.matrices()`:
- `drawEntity(Entity, MatrixStack, Immediate, float)` (Renderer:235-268) — **KEEP MorphRenderer-first** (`renderPlayer` for `AbstractClientPlayerEntity`, `renderLivingEntity` for `LivingEntity`), vanilla `EntityRenderDispatcher.render` fallback. This is the reason the IRLite variant exists (Map D divergences; do NOT substitute redactor's dispatcher-only).
- `drawModelBlock(ModelBlockEntity, MatrixStack, Immediate, Camera, float)` (Renderer:270-301) — feet Y = `pos.getY()+translate.y` (no +0.5, no ey); `MatrixStackUtils.applyTransform`; `FormUtilsClient.getRenderer` + `FormRenderingContext(FormRenderType.MODEL_BLOCK).camera(cam)`. **Preserve the draw-feet-Y vs cull-center-Y difference** — do not unify (Map D drawModelBlock note + rawNotes).
- `drawReplay(IEntity, MatrixStack, Camera, float)` (Renderer:303-327) — **NO immediate param** (FormRenderingContext carries consumers); lerp pos + body yaw, `RotationAxis.POSITIVE_Y` rotate, `FormRenderType.ENTITY` context.

> **BATCHED-FLUSH CAPTURE risk (Map D risk):** `drawModelBlock`/`drawReplay` render through `FormRenderingContext`, not directly into the `immediate` param. For the single shared end-of-batch flush to capture their geometry, the BBS `FormRenderer` MUST emit into MinecraftClient's entity `Immediate` (the same one `endCasterBatch` drains). This held under the old per-caster flush — **VERIFY it still holds under deferred flush** (Open Q2). If a form arm self-flushes internally, that arm needs its own immediate draw and cannot be batched.

---

## 4. BLOCK-CAST — shared, BBS-free; sibling reconciliation

The block-cast path is **shared and BBS-free on all variants** (spec 58-60, lines 206-227). It flows through `ShadowRenderer.renderBlocksDepth`/`renderBlocksDepthCutout` keyed by `LightRegistry` id with its own per-light VBO caches; it never touches `occ[]` and is NOT part of the BBS seam (Map A renderBlocksDepth*; Map D ShadowRenderer role "block-cast BBS-free, NOT part of seam").

Reconciliation:
- `BlockShadowCollector` / `BlockShadowEntry` — byte-identical to canon mod package (Map B): no change.
- `BlockShadowCache` — the ONLY structural sibling divergence (Map B/C divergences). Upgrade IRLite's to the canon version with `CacheEntry.cx/cy/cz/cr` + exact center+radius rejection in `invalidateAt` (T2.5). Correctness-safe either way; canon is a clean drop-in because the ported ShadowBaker assumes nothing extra from `invalidateAt` (§2 sibling table).
- **INV-1 CLAUSE 2 is satisfied by the ported orchestration** (Map A rawNotes "INV ordering proof in bakeInner"): the caster flush (`endCasterBatch` inside `renderInRangeCone`/`Face`) runs BEFORE `renderBlocksDepth` within the same `begin*/endPass` bracket. `renderBlocksDepth` draws via explicit ambient `currentView`/`currentProj` (never live modelview, Renderer:359-446/541-625). The impl must not reorder this (spec 246-249).
- Re-verify `WorldBlockChangeMixin` still calls `BlockShadowCache.invalidateAt` with the same signature after the upgrade (Map B risk "IRLite-only callers").

---

## 5. BUILD WIRING — changes + green-build DoD

### B5 — IrliteConfig.shadowBakeBudget() (the ONLY hard config gap)
**File:** `C:/Users/Qualet/Documents/Project/Minecraft/BBS/IRLite/src/main/java/qualet/irlite/IrliteConfig.java` (49 ln).
Add a BBS `ValueInt`-backed field + accessor `shadowBakeBudget()` defaulting to **4** (matches redactor `LightConfig` default — Map C LightConfig note). The canon ShadowBaker (T2.4, ~Baker:259) calls `LightConfig.shadowBakeBudget()`; without this the port won't compile (Map C risks "IrliteConfig.shadowBakeBudget() is MISSING"). Reuse existing `shadowQuality/shadowCache/shadowBlocks/shadowBlockRadius`.

### No build.gradle / mixins.json / accessor changes needed
- `build.gradle` — no new compile dep; irl-core (`org.qualet.irl.light.LightRegistry`) + Iris already on classpath; Java 17 both sides; 1.20.4 dev profile matches redactor's exact yarn/fabric/iris coords (Map C build.gradle note + rawNotes Q3/Q4).
- `irlite.client.mixins.json` / `fabric.mod.json` — `GameRendererLightMixin`, `WorldBlockChangeMixin`, `bbs.FilmsAccessor`, `bbs.WorldBlockEntityTickersAccessor` already registered. The seam adds plain classes, no new mixins (Map C fabric.mod.json + mixins.json notes; Map C Q4).
- The new seam files MUST land under `qualet.irlite.client.light.shadow` (NOT a new package) so Loom's client sourceSet + mixin package scoping pick them up without config edits (Map C risk "must land under qualet.irlite.client.light.shadow").

### Green-build Definition of Done
1. `./gradlew -Pmc=1.20.4 build` (IRLite dev profile, matches canon mappings) compiles clean.
2. No dangling references to removed symbols: `ShadowRenderer.CASTER_*`, `ShadowRenderer.renderCaster`, IRLite's old inline `collect`/`collectModelBlocks`/`collectFilmReplays`/`modelBlockHash`/`drawEntity`/`drawModelBlock`/`drawReplay`.
3. All `org.qualet.irlredactor.*` imports gone from the copied files (retargeted to `org.qualet.irl.light.*` + `qualet.irlite.*`).
4. `IrliteConfig.shadowBakeBudget()` resolves.
5. Build-green is necessary but NOT sufficient — the 5 invariants fail silently at compile (spec 502); §7 gate is mandatory.

---

## 6. EXECUTION ORDER (numbered, dependency-ordered)

Per `feedback-no-per-session-branch`: work in the current branch unless the user asks otherwise. Steps 1–9 are ONE coherent edit set (the build is red between step 1 and step 8 — do not commit mid-sequence). Step 0 may be done in isolation.

| Step | Action | Depends on | Isolatable? |
|---|---|---|---|
| **0** | Upgrade `IRLite/.../shadow/BlockShadowCache.java` to canon (cx/cy/cz/cr + exact-rejection `invalidateAt`). Re-verify `WorldBlockChangeMixin` signature. | none | **YES — isolated worktree OK** (correctness-safe, compiles standalone against old orchestration). Land/commit first if desired. |
| **1** | Copy 5 SEAM files S1–S5 verbatim (package line only). | none | Part of coherent set (S4 references S7). |
| **2** | Add `IrliteConfig.shadowBakeBudget()` (B5). | none | Could isolate, but trivial; do inline. |
| **3** | Verify `PointShadowArray.copyStaticFaceToLive` exists; port from canon if absent (§2 sibling table). | none | Part of coherent set. |
| **4** | Copy + adapt canon **ShadowRenderer.java** → S7 (package + LightRegistry retarget). Provides `flushCasterImmediate` for S4. | 1 | Coherent set. |
| **5** | Copy + adapt canon **ShadowBaker.java** → S6 (package, LightRegistry→irl-core, LightConfig→IrliteConfig, SOURCE field, collect shim). References `IRLiteBbsCasterSource` (step 6) — will not compile until 6. | 1,2,4 | Coherent set. |
| **6** | Write **`IRLiteBbsCasterSource.java`** (§3): 3 collect arms + 3 emitOccluder arms + `modelBlockHash` + `getActiveEditorController` + FNV helper + FULL_LIGHT, migrating IRLite's 7 old BBS methods with the INV-2/INV-5 fixes. | 1 (seam types), 5 (SOURCE wiring target) | Coherent set — the hardest file; the INV-2 animation probe (Open Q1) may need a spike. |
| **7** | Delete obsolete code paths: IRLite's old inline collect arms + draw leaves are gone with the file replacements in 4/5; confirm no orphan references compile-error. | 4,5,6 | Coherent set. |
| **8** | `./gradlew -Pmc=1.20.4 build` → green (§5 DoD). | 1–7 | — |
| **9** | Commit (only when user asks). | 8 | — |
| **10** | In-world invariant gate (§7) — USER eyeballs. | 8/9 deployed to PrismLauncher mods | Separate session/runtime. |

---

## 7. IN-WORLD INVARIANT GATE (mandatory; user eyeballs)

The 5 invariants are invisible at compile time (spec 234, 502). Run a BBS scene with shadows enabled and check EACH:

- [ ] **INV-1 — block shadows present.** World blocks under a shadow-casting light cast shadows. (If a throwing/corrupting form silently killed the caster pass but blocks still draw, this proves the matrix re-establish/ordering is the suspect — spec 254-255.)
- [ ] **INV-1 — entity/form silhouette casts a shadow** (not just blocks). A morph/player/mob silhouette appears in the shadow, at the correct transform (not a garbage offset).
- [ ] **INV-2 — animated model-block does NOT freeze.** Place a model block with a **playing animation** (rotating/waving/breathing morph clock) under a light; its shadow must MOVE with the animation, not freeze at the first pose (spec 272-273). This is the headline INV-2 test — IRLite's old code froze here.
- [ ] **INV-3 — no stale shadow on a balanced edit.** Multi-select two model-block props, drag-rotate with equal-and-opposite deltas (or a hash-preserving membership swap); the shadow must update, not stay frozen (spec 288-291). Tests the avalanche+count fold.
- [ ] **INV-4 — no phantom shadow streak from a throwing form.** Trigger a form/morph that throws in render (bad BBS reflection / missing form); a single bad caster must vanish without fusing a garbage triangle streak into the next caster's shadow (spec 308-311).
- [ ] **INV-5 — large/anisotropic/rotated forms cast near the cone edge.** A 4-block Ghast (or scale 4,1,4 form, or a rotated model block) lingering ~35–40° off a spotlight's axis must still cast a shadow — it must not silently drop out of the cone cull (spec 326-330).
- [ ] **Shadow matches BBS preview.** The cast silhouette matches the BBS in-editor form preview (the MorphRenderer-first path, not a vanilla box) (spec 26-35).

If any fails: the orchestration is shared/correct on canon, so the suspect is almost always the new `IRLiteBbsCasterSource` (INV-2 isStatic probe, INV-5 box expansion, or INV-4 not propagating its throw) — see Open Questions.

---

## 8. OPEN QUESTIONS / RISKS needing a human decision

1. **[Q1 — INV-2 animation probe] BLOCKING for the static-cache win.** How to cheaply detect whether a `ModelBlockEntity`'s Form has a playing animation / non-static morph clock? No existing IRLite code probes this (Map B risk "today there is NO animation-detection logic to copy — it is new work"). **Decision needed:** (a) find a cheap BBS Form/morph "is-animating" probe, or (b) conservatively set `isStatic=false` for ALL model blocks (always correct, loses the static-tile cache perf). Recommend shipping (b) first to pass the INV-2 gate, then optimizing to (a) once an animation probe is confirmed safe.

2. **[Q2 — INV deferred-flush capture] VERIFY in-world.** Do `drawModelBlock`/`drawReplay` (BBS `FormRenderer` via `FormRenderingContext`) emit into MinecraftClient's entity `Immediate` such that the single shared `endCasterBatch` flush captures them? This held under IRLite's old per-caster flush; the batching migration (T2.2) defers the flush (Map D risk "BATCHED-FLUSH CAPTURE"; spec 380-384). If a form arm self-flushes internally, it cannot be batched and needs its own immediate draw — discover only in-world.

3. **[Q3 — BlockShadowCache upgrade scope]** Upgrade to the canon `cx/cy/cz/cr` exact-rejection version now (step 0) or defer? It is perf-only (IRLite's coarser version over-invalidates but is correct — Map B/C divergences). Recommend doing it in step 0 (isolated, low-risk) to land all of T2.5; defer only if time-boxed.

4. **[Q4 — INV-5 rotation-expanded box construction]** The exact geometry to build a rotation-expanded AABB for a model block from `form.hitboxWidth/Height` × `Transform.scale` × `Transform.rotate` is new code (Map D risk "non-trivial new geometry; today's radius ignores Transform.rotate"). Spec gives the convention (post-rotate diagonal, spec 315-334) but not the rotation-expansion routine. Implementer latitude; verify against the INV-5 in-world test (Ghast/anisotropic/rotated near cone edge).

5. **[Q5 — INV-4 impl must drop its own try/catch]** IRLite's old `renderCaster` swallowed throws (L208-232). The migrated `emitOccluder` MUST let throws propagate to the shared `emitCaster` wrapper, else run-isolation is silently defeated (Map D risk INV-4; spec 300-306). This is a discipline risk during the §3 port, not a design decision — call it out in review.

6. **[Q6 — resolved by OPEN-2]** The 3 collect arms share a bounded nearest-128 sink. Keep entity→model-block→replay for deterministic exact-distance ties; arm order no longer decides ordinary over-cap retention.

---

**Plan provenance:** canon orchestration = redactor `main@522c1f0` (Map A); IRLite target = `master@9953f31` (Map B); build/integration = Map C; BBS draw-arm extraction = Map D; invariants = frozen spec (read in full). Build-green is necessary but the §7 in-world gate is the real DoD.
