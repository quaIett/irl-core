# Ф3 Adversarial Audit — Synthesizer Verdict

## 1. OVERALL: **SHIP** (with mandatory in-world gate)

Build is green; **the new `IRLiteBbsCasterSource` introduces zero fresh invariant violations on any path attacked by the five adversaries.** Every "broke: true" reduces, on closer reading, to a **pre-existing** condition in OLD IRLite (byte-identical orchestration), not a regression created by the seam refactor — and in each case the new code is equal-or-better than what shipped before. No blocker requires a code change before in-world testing.

Per-invariant ruling:

| Invariant | Ruling | Basis |
|---|---|---|
| INV-1 (matrix corruption, self-drawing forms) | **PASS-as-fed** (latent pre-existing risk, not new) | Adversary-INV1 |
| INV-2 (static/dynamic split) | **PASS** | Adversary-INV2/3 + Wiring |
| INV-3 (staticHash) | **PASS** | Adversary-INV2/3 |
| INV-4 (exception/vertex-run isolation) | **PASS** | Adversary-INV4 |
| INV-5 (bounding-sphere circumscription) | **PASS for entity/replay/yaw; one real defect for off-vertical model blocks** (also pre-existing, partially-closed) | Adversary-INV5 |
| INV-6 (over-cap drop order) | **PASS** | Adversary-Wiring |
| Open Q2 (deferred-flush capture) | **PASS** (forms self-draw; not dropped) | Adversary-INV1 |

The only reason this is not an unqualified "everything is perfect" is that **two of the seven items are confirmable only in-world** (Q2/INV-1 form modelview, INV-2 animation), and **one contract doc claim is provably false** and must be corrected so it does not mislead future maintainers. None of these block shipping the seam.

---

## 2. BLOCKERS and MAJORS (deduped)

### No BLOCKERS.

### MAJOR-A — Contract INV-1 claim is false for self-drawing BBS forms (doc fix only; not a seam regression)
- **Nature:** Residual in-world risk + **incorrect contract assertion**. NOT a defect in the new source file.
- **What:** BBS forms (ModelForm/BOBJ via `ModelVAORenderer.render`→`glDrawArrays`; MobForm via `consumers.draw()`; non-VAO via `BufferRenderer.drawWithGlobalProgram`) **self-draw synchronously inside `emitOccluder`**, reading the **live** `RenderSystem` modelview at draw time (`ModelVAORenderer.java:42`). `MobFormRenderer.render3D` leaves the live modelview = identity with no restore (`bbs-fs MobFormRenderer.java:352`). The shared wrapper re-establishes the light view/proj **only at `endCasterBatch`** (`ShadowRenderer.java:295-301`), so a MobForm-morphed entity emitted (Arm1) before a model-block/replay form (Arm2/3) in the **same light pass** can make the later form draw through an identity modelview → wrong/absent shadow, no GL error.
- **Why not a blocker:** OLD `renderCaster` had the identical no-inter-caster-reset exposure (git HEAD). The seam did not introduce it; an A/B vs OLD looks identical. Cannot be resolved at compile time.
- **Location:** `bbs-fs MobFormRenderer.java:352` → `ModelVAORenderer.java:42`; orchestration `ShadowRenderer.java:234-256` + `:295-301`; contract `irl-core/docs/shadow-caster-seam-spec.md` INV-1 CLAUSE-1.
- **Exact fix (choose one; do NOT touch the new source file):**
  - **(a) Doc-only, ship now:** Correct the spec INV-1 to state self-drawing BBS form casters read **live** `RenderSystem` matrices at emit time and are **not** covered by the end-of-batch re-establish; add the mixed MobForm + model-block/replay scene to the mandatory in-world gate.
  - **(b) Close the latent hole (optional, also fixes OLD bug):** In shared `emitCaster`, re-establish the light view/proj (the `flushCasterImmediate` matrix prologue, **without** `immediate.draw()`) **immediately before each `source.emitOccluder` call**, so every self-drawing form starts from the clean light modelview.
- **Recommendation:** Do **(a)** before shipping (one-paragraph doc edit). Treat **(b)** as separately-scheduled hardening that also subsumes the OLD latent bug.

### MAJOR-B — INV-5 under-bound for off-vertical (rotate.x/z) model blocks (real defect, partially-closed pre-existing class)
- **Nature:** A genuine geometric defect, but **a pre-existing class only partially closed** by the new rotation-expansion — not a fresh regression (OLD code was rotation-unaware and *more* wrong).
- **What:** `rotationExpandedBox` expands half-extents about the **box center** and returns `[−ehx,0,−ehz]..[ehx,2ehy,ehz]` (`IRLiteBbsCasterSource.java:382-384`); `emitFromBox` centers the cull sphere at `feetY+ehy` (`ShadowBaker.java:1216`). But `drawModelBlock` translates to the **feet** then rotates about that point (`IRLiteBbsCasterSource.java:490-495`, confirmed vs `bbs-fs ModelBlockEntityRenderer.java:113-137`). Feet-pivot rotation displaces geometry by `R·(0,hy,0)−(0,hy,0)`, which the center-anchored sphere ignores. Measured under-bound exceeds the `0.5f` `OVERLAP_MARGIN` once tilt ≳45–60° (cube-ish hbW=hbH=1.8 @ rotX=60° → 0.51; tall hbH=4 @ rotX=45° → 0.93). A tilted+scaled model block near a spot-cone edge or point-cube face seam is silently dropped from the shortlist (`ShadowBaker.java:1024-1025/1029/1041`).
- **Why not a blocker:** Pure body-yaw forms (the common case) are over-bound and correct; entity and replay arms verified correct; the `0.1f`/`OVERLAP_MARGIN` floors are safe. OLD shipped IRLite was under-bound for this case too (and generally worse), so the "identical to before" gate will pass. Affects only off-vertical *and* near-cull-boundary placements.
- **Location:** `IRLiteBbsCasterSource.java:382-384` + `ShadowBaker.java:1216` vs `IRLiteBbsCasterSource.java:490-495`. Also: the `:344` javadoc calling the expansion "a guaranteed over-bound" is **false** for the feet-pivot draw.
- **Exact fix:** Account for the feet pivot. Cheapest correct option: rotate the **8 feet-relative local corners** (x∈[−hx,hx], y∈[0,2hy], z∈[−hz,hz]) by R about the feet origin, bound them to a true feet-AABB, and center the sphere on that AABB — O(8), only when `t!=null` and `rotate` non-zero. Alternatively keep the abs-expansion but add the feet displacement `d=|R·(0,hy,0)−(0,hy,0)|` to the radius. **Also correct the `:344` javadoc.**
- **Recommendation:** Not required before in-world ship (degrades to a missing shadow only in a narrow geometric corner). Schedule the corner-bounding fix + javadoc correction as fast-follow; deliberately probe it in the in-world gate (item 2 below).

---

## 3. MINORS / NITS

- **MINOR (perf, intentional):** All model blocks now `isStatic=false` (`IRLiteBbsCasterSource.java:206`, `staticHash=0L` `:214`) disables the static-layer caching OLD IRLite had → static model blocks near a lamp re-bake the overlay every frame. This is the deliberate, guaranteed-correct INV-2 choice (revoking the old animation freeze) and is flagged in the source TODO (`:203-205`). `modelBlockHash` (`:391-409`) is currently **dead code** reachable only if the TODO is flipped. Action: accept + note in Ф3 memory, or revive `isStatic=true` for provably-static Transform/morph (TODO), or delete the dead hash if (b) is not planned.
- **NIT:** Dormant `modelBlockHash` omits a form's internal morph/animation pose; folds only form-identity + center + Transform scale/rotate/translate (`:391-409`). Harmless today (hash dead). If the probe is ever enabled, it **must** gate `isStatic=true` on a confirmed non-animating morph, not just Transform stability, or the old freeze returns. Add a comment that the hash deliberately omits morph pose.
- **NIT:** `drawModelBlock`'s `immediate` param is dead and `drawReplay` takes none — confirms forms bypass the batched Immediate. Optional: drop the param + add a one-line "BBS forms self-draw" comment.
- **NIT:** Replay arm uses a square (width==depth) box and skips rotation expansion (`:291-301`) — correct only while `drawReplay` applies translate + body-yaw only (`:521`). Latent coupling: if replay forms ever gain non-yaw transforms, mirror the model-block `rotationExpandedBox` treatment.
- **NIT (INV-5 picture, pre-existing):** Both replay and model-block spheres derive from the editable `hitboxWidth/Height`, not the rendered form bounds; a morph extending past its hitbox under-bounds regardless of rotation. Long-standing accepted approximation, not introduced here.

---

## 4. IN-WORLD GATE CHECKLIST (ordered by likelihood of failure given the findings)

Probe these deliberately — standard vertical/single-caster scenes will **not** surface the top two.

1. **[Q2 / INV-1 — mixed-caster form modelview, MOST LIKELY]** Place **one** light whose range simultaneously covers (a) a **MobForm-morphed** mob/player **and** (b) a **BBS model block** and/or a **non-actor Film replay form**. Eyeball whether the model-block/replay form's shadow is correct. If `MobFormRenderer`'s identity-leak corrupts the live modelview the later self-drawing form reads, that form's shadow is **misplaced/absent** while world-block shadows (explicit-matrix path) stay fine — the classic masking pattern. (A/B vs OLD looks identical — judge correctness, not regression.)
2. **[INV-5 — off-vertical model block at cull boundary]** Place a **tall** model block (hbH≈4) with **rotate.x or rotate.z ≈ 60–90°** just inside a spotlight cone near its edge (or near a point-light cube face seam). Move the light/camera so the block crosses the cull boundary; verify the shadow does **not** pop out/disappear. Pure body-yaw forms need no check.
3. **[INV-2 — animation positive case]** Place a model block running a **playing animation** and confirm its shadow **visibly moves/updates every frame** (does not freeze). This confirms the all-dynamic overlay path (line-463 else-branch) actually re-renders — the whole point of revoking the old MODEL_BLOCK==static freeze.
4. **[Coverage — model-block-only light]** A light with **no entities and no world blocks**, only a model block in range: confirm it produces a visible shadow each frame (validates the dynamic overlay path fires when `entInRange>=1` from the model block alone).
5. **[Silhouette correctness]** For a normal BBS Form/Morph caster, confirm the cast silhouette **matches the in-editor preview** and lands in the depth map (orchestration can only confirm geometry was buffered+flushed, not that the silhouette is right). Include a model-block whose **form is a vanilla mob** drawn via `EntityRenderDispatcher` — the MODEL_BLOCK→FormRenderer path the contract exists to enable, not covered by the byte-identical canon.
6. **[INV-2 perf]** On a **model-block-heavy** scene with several static decorations near lamps, confirm frame time is acceptable (all-dynamic re-bake is a measurable per-frame cost — visible as stutter, not wrong pixels).

Items needing **no** in-world check (verified conclusively by static analysis): INV-4 isolation machinery, INV-3 staticHash inertness (compile-time constants), INV-6 drop order, JOML indexing / scale-rotate order / Y-rotation invariance / entity-diagonal fix / degenerate-sphere floor, casterType mapping, SOURCE/SINK wiring, BlockShadowCache exact-rejection, PointShadowArray layer math, orphan-symbol cleanliness.

---

## Pre-ship doc edit (the only thing recommended before in-world)

Apply MAJOR-A fix **(a)**: in `irl-core/docs/shadow-caster-seam-spec.md`, correct the INV-1 CLAUSE-1 wording so it no longer claims the end-of-batch re-establish covers self-drawing BBS form casters, and add the item-1 mixed-caster scene to the in-world gate. Optionally also fix the false `:344` javadoc from MAJOR-B at the same time. Everything else is fast-follow or in-world.

**Relevant files:**
- `C:\prismlauncher\instances\BBS\minecraft\mods\...\IRLite\src\client\java\qualet\irlite\client\light\shadow\IRLiteBbsCasterSource.java` (MAJOR-B `:382-384`/`:490-495`/`:344`; MINOR `:206`/`:214`/`:391-409`)
- `ShadowBaker.java` (`:1216` cull-sphere center; `:1053` static fold gate)
- `ShadowRenderer.java` (`:234-256` emitCaster; `:295-301` re-establish)
- `C:\Users\Qualet\Documents\Project\Minecraft\BBS\irl-core\docs\shadow-caster-seam-spec.md` (MAJOR-A doc fix)
- `bbs-fs MobFormRenderer.java:352` + `ModelVAORenderer.java:42` (INV-1 live-modelview source)