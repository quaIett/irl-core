# ShadowConfig + ShadowCasterSource injection — frozen contract (Ф1)

> **Ф2 IMPLEMENTED 2026-06-25 — this contract is now live in code.** Both seams
> (`ShadowConfig`, `ShadowEngine`) and all 14 orchestration files moved into `irl-core`
> (`org.qualet.irl.light.shadow`); the redactor and addon each keep only their per-mod
> `ShadowCasterSource` impl + a thin config delegate (`LightConfig.SHADOW` /
> `IrliteShadowConfig` — the addon uses a separate client-side class, not a nested
> singleton, because `IrliteConfig` is in the server-safe main source set) and one
> `ShadowEngine.install(...)` call at client init. Both mods build green on MC 1.20.4.
> The frozen spec below is retained as the implemented contract.

> **Status: FROZEN (user sign-off 2026-06-25).** Phase 1 of
> `plan-irl-core-shadow-extraction` (the per-version Loom-core extraction that
> supersedes the Ф4 lockstep verifier — see
> [shadow-orchestration-lockstep.md](shadow-orchestration-lockstep.md)).
>
> **Purpose.** Define the TWO new seams required to physically move the 14
> MC-typed shadow-orchestration files out of *both* mods and into a per-version
> `irl-core` Loom module, leaving each mod with only its per-mod
> `ShadowCasterSource` impl + a thin config adapter. This is a SPEC ONLY — no
> code is touched in Ф1.
>
> **Base commits at freeze (verified 2026-06-25):** core `main@7677d77`,
> redactor `main@318529f` (CANON), addon `master@8d5f3be`. Lockstep verifier
> `PASS: all 14 in lockstep` at these commits.
>
> **Canon orchestration = redactor `main` (1.20.4)** — BBS-free, already cut by
> the `ShadowCasterSource` seam in the prior plan; all 6 perf-opts; not the
> raw-GL port. Ф2 moves THIS code into core verbatim (modulo the two seams below).
>
> This builds on the frozen interface contract in
> [shadow-caster-seam-spec.md](shadow-caster-seam-spec.md) (the `ShadowCasterSource`
> /`OccluderSink`/`OccluderBatch`/`CasterType` shapes + 5 invariants). Those
> signatures are **NOT** reopened here; Ф1-new only freezes how the per-mod
> `ShadowCasterSource` *instance* and the config *accessor* reach the shared
> orchestration once it no longer lives in the mod's own package.

---

## Простое объяснение (для отчётов)

> 14 файлов оркестрации теней сейчас лежат КОПИЕЙ в каждом моде и держатся
> байт-идентичными верификатором. Чтобы переехать на общий код (один файл в
> irl-core вместо двух копий), нормализатору сейчас приходится «прощать» ровно
> два per-mod различия: имя config-класса (`LightConfig` у редактора /
> `IrliteConfig` у аддона) и имя класса-источника каста
> (`RedactorEntityCasterSource` / `IRLiteBbsCasterSource`, захардкоженный внизу
> `ShadowBaker.java`). Эта спека замораживает два шва, которые УБИРАЮТ оба
> различия из общего кода: (1) интерфейс **`ShadowConfig`** вместо прямого
> обращения к статическому config-классу; (2) **инъекция** источника каста (и
> config) в client-init вместо хардкода `new …CasterSource()`. После этого все
> 14 файлов становятся идентичными без подстановок → их можно переместить в core
> как единый исходник. Третье, что прощает нормализатор (пакетные префиксы),
> закрывается самим фактом переезда в общий пакет.

---

## Gate: does the contract cover everything the normalizer forgives?

The Ф1 gate is: *the two seams must cover every divergence the
`verify-shadow-lockstep.py` normalizer currently allowlists.* The normalizer
([source](../tools/verify-shadow-lockstep.py), table in
[shadow-orchestration-lockstep.md](shadow-orchestration-lockstep.md#allowlisted-divergences))
forgives exactly these:

| normalizer allowance | closed by | how |
|---|---|---|
| `package` decl dropped | **Ф2 move** | the 14 files become ONE source in `org.qualet.irl.light.shadow`; no second copy to diverge |
| `import` lines dropped | **Ф2 move** | single package → neighbours need no import; per-mod config/source no longer imported (injected) |
| `org.qualet.irlredactor` → `MOD` | **Ф2 move** | shared file is authored once under the core package; no per-mod root prefix remains |
| `qualet.irlite.client` → `MOD` | **Ф2 move** | ″ |
| `org.qualet.irl` → `MOD` | **Ф2 move** | already the core prefix (`LightRegistry` is shared); becomes the file's own package prefix |
| `qualet.irlite` → `MOD` | **Ф2 move** | only appeared in javadoc `{@link}` to IRLite-side classes; gone once authored in core |
| `LightConfig` ↔ `IrliteConfig` → `Cfg` | **Seam 1 (`ShadowConfig`)** | orchestration calls the `ShadowConfig` interface, not a named static class |
| `RedactorEntityCasterSource` ↔ `IRLiteBbsCasterSource` → `SourceImpl` | **Seam 2 (injection)** | the `SOURCE` field is gone; the impl is injected at client-init, referenced only by interface type |

**Verdict: covered.** The two semantic allowances map 1:1 onto the two seams;
the six package/import allowances are artifacts of *having two copies* and
vanish when the copies become one shared source. There is no residual
allowlisted divergence left unaccounted for. (Verified by reading all
occurrences of `LightConfig`/`IrliteConfig` and
`RedactorEntityCasterSource`/`IRLiteBbsCasterSource` across the 14 files in both
repos — see "Grounding" below.)

---

## Seam 1 — `ShadowConfig` (replaces the named static config class)

### Interface (frozen)

Lives in core, package `org.qualet.irl.light.shadow`:

```java
public interface ShadowConfig {
    /** Shadow resolution preset ordinal (0 LOW .. 3 ULTRA). A change frees +
     *  re-allocates the depth textures, so the orchestration forgets every
     *  cached map when it changes (ShadowBaker.lastQuality). */
    int shadowQuality();

    /** When true, depth maps are re-baked only when the scene changes; when
     *  false every light bakes into its live tile every frame. */
    boolean shadowCache();

    /** Max FULL STATIC bakes started per frame before the rest are deferred to a
     *  later frame (T2.4). &lt;= 0 disables throttling (bake everything every
     *  frame). First bakes / tile-reassign bakes are never deferred; dynamic
     *  overlays and static-&gt;live copies are never budgeted. */
    int shadowBakeBudget();

    /** When true, world blocks cast shadows by their real shape. */
    boolean shadowBlocks();

    /** Block-shadow collection radius in blocks: world blocks farther than this
     *  from a light cast no shadow even when the light's range is larger. Bounds
     *  the per-light bbox walk. */
    int shadowBlockRadius();

    /** ADDED 2026-07-18 (partial-tile AABB): horizontal pose slack of the spot
     *  dyn-rect AABB, as a fraction of the caster's half-height. OPTIONAL — a
     *  {@code default} method (0.9) and an optional Builder setter, so shims
     *  written against the original five getters keep compiling; the redactor
     *  deliberately does not override it. */
    default float shadowPoseReach() { return 0.9f; }
}
```

### Canonical defaults (currently DUPLICATED in both mods — unify in core)

| getter | default | range / notes |
|---|---|---|
| `shadowQuality()` | `1` (MEDIUM) | `0..3`; clamped by `IRLShadowQuality.applyFromSetting` |
| `shadowCache()` | `true` | — |
| `shadowBakeBudget()` | `4` | any int; `<= 0` ⇒ unlimited |
| `shadowBlocks()` | `true` | — |
| `shadowBlockRadius()` | `24` | blocks; `> 0` |
| `shadowPoseReach()` | `0.9` | `>= 0` (addon UI slider `0..2`); interface `default`, NOT duplicated per-mod; NaN/negative sanitized at the read site |

These defaults are identical in `LightConfig` (redactor, mutable static fields)
and `IrliteConfig` (addon, null-safe BBS `Value*` fallbacks) today. Core SHALL
expose them once as a fallback instance:

```java
ShadowConfig DEFAULTS = /* a ShadowConfig returning 1, true, 4, true, 24 */;
```

so a mod that never installs a config still bakes (rather than NPE), and the
canonical default values stop being duplicated across repos.

### Consumption model — PULL (synchronous, side-effect-free, per-frame)

- **Sole consumer: `ShadowBaker`.** Verified: `ShadowConfig`-relevant config is
  read in NO other lockstep file. `ShadowBaker` reads it at exactly these sites
  (redactor `main@318529f` line numbers; addon identical modulo the class name):
  - `shadowQuality()` — L242 (quality preset / texture realloc gate)
  - `shadowCache()` — L256
  - `shadowBakeBudget()` — L259
  - `shadowBlocks()` — L745 (VBO-cache retain gate)
  - `shadowBlocks()` + `shadowBlockRadius()` — L969, L973 (`collectBlocks`)
  - javadoc `{@link …#shadowBakeBudget()}` L160, `{@link …#shadowBlockRadius()}` L963
- The orchestration calls getters **synchronously inside `bake()`** every frame
  and never caches across frames. PULL is mandatory, not a choice: the addon
  side is backed by BBS `Value*` objects read lazily — there is no push/listener
  source to invert. The redactor side is plain mutable fields the ImGui editor
  writes. Both satisfy a pull interface trivially.

### Per-mod implementation (thin delegate to the existing static class)

Each mod keeps its current static config class (it still carries non-shadow
fields — see below) and supplies a `ShadowConfig` instance that delegates into
it. Recommended shape — a singleton next to the static class:

```java
// redactor (org.qualet.irlredactor.light)
public final class LightConfig {                 // unchanged static holder
    public static final ShadowConfig SHADOW = new ShadowConfig() {
        public int shadowQuality()      { return LightConfig.shadowQuality(); }
        public boolean shadowCache()    { return LightConfig.shadowCache(); }
        public int shadowBakeBudget()   { return LightConfig.shadowBakeBudget(); }
        public boolean shadowBlocks()   { return LightConfig.shadowBlocks(); }
        public int shadowBlockRadius()  { return LightConfig.shadowBlockRadius(); }
    };
    ...
}
```

Addon mirrors this with `IrliteConfig.SHADOW` delegating into `IrliteConfig`'s
BBS-`Value*`-backed getters. The exact form (nested singleton vs separate
adapter class) is implementer latitude; the contract requires only that each mod
hand the orchestration ONE `ShadowConfig` whose five getters return that mod's
current values.

### Explicitly OUT of `ShadowConfig` (stays per-mod)

`ShadowConfig` is a strict SUBSET of each mod's config — only the shadow-relevant
getters. These remain in the per-mod static class and are NOT moved, because no
lockstep file reads them (verified by grep):

- `showGuides()` — both mods; consumed by `LightGuideRenderer` (per-mod, not a
  shadow file).
- `autoLights()`, `autoLightShadows()`, `autoLightIntensity()`,
  `autoLightReach()`, `autoLightRadius()`, `autoLightMax()` — redactor only;
  consumed by `AutoLightManager` (per-mod). Note `autoLightShadows` gates whether
  auto-lights are *registered with shadows*, read where lights are fed to
  `LightRegistry`; `ShadowBaker` reads `LightRegistry.getShadows(i)`, never the
  config flag — so it does not belong in `ShadowConfig`.

---

## Seam 2 — `ShadowCasterSource` registration (replaces the hardcoded `SOURCE`)

### What exists today

`ShadowBaker.java` L1195 hardcodes the per-mod impl:

```java
private static final ShadowCasterSource SOURCE = new RedactorEntityCasterSource(); // addon: new IRLiteBbsCasterSource();
```

`SOURCE` is used at exactly three sites, all in `ShadowBaker`:
- `SOURCE.collect(world, cameraPos, tickDelta, SINK)` — L1249 (`collect()`)
- `ShadowRenderer.emitCaster(SOURCE, occ[k], occType[k], tickDelta)` — L1112 (`renderInRangeCone`)
- `ShadowRenderer.emitCaster(SOURCE, …)` — L1137 (`renderInRangeFace`)

The per-mod impl class name appears NOWHERE ELSE in any of the 14 files
(verified by grep: only the `SOURCE` initializer + the class's own declaration).
So the seam isolates a single line.

Both impls are `public final class … implements ShadowCasterSource` with an
**implicit no-arg constructor and no external state** — `RedactorEntityCasterSource`
holds only static constants; `IRLiteBbsCasterSource` reaches BBS statically inside
`collect`/`emitOccluder`. A mod can therefore `new` its impl at client-init and
hand it over with no wiring.

### The `ShadowCasterSource` interface itself is NOT reopened

It stays exactly as frozen in
[shadow-caster-seam-spec.md](shadow-caster-seam-spec.md) (`collect` + `emitOccluder`,
5 invariants). Ф1-new changes only the DELIVERY of the instance: hardcoded `new`
→ injection.

### Injection holder (frozen API) — modeled on `Patcher`

Core already uses this exact pattern for the patcher
([`Patcher.install(host)` / `Patcher.host()`](../src/main/java/org/qualet/irl/patcher/Patcher.java),
a `volatile` field + fail-fast getter). The shadow engine mirrors it, carrying
BOTH injected dependencies (the caster source + the config) in one holder so a
mod makes ONE call at init, symmetric to `Patcher.install`:

```java
// core, package org.qualet.irl.light.shadow
public final class ShadowEngine {
    private static volatile ShadowCasterSource source;          // mandatory
    private static volatile ShadowConfig config = ShadowConfig.DEFAULTS; // optional

    private ShadowEngine() {}

    /** Install at client init, BEFORE the first bake. cfg may be null
     *  (-> DEFAULTS); src is required. */
    public static void install(ShadowCasterSource src, ShadowConfig cfg) {
        source = src;
        config = (cfg != null) ? cfg : ShadowConfig.DEFAULTS;
    }

    /** The injected caster source. Fail-fast (like Patcher.host()) if a mod
     *  forgot to install one — a missing source means no shadows can be cast,
     *  so it is a wiring bug, not a silent no-op. */
    public static ShadowCasterSource source() {
        ShadowCasterSource s = source;
        if (s == null) {
            throw new IllegalStateException(
                "ShadowCasterSource not installed — call ShadowEngine.install(...) at client init");
        }
        return s;
    }

    /** Never null: the installed config, or DEFAULTS. */
    public static ShadowConfig config() { return config; }
}
```

**Fail-fast vs no-op (frozen):** `source()` throws if absent (a missing caster
source is always a wiring bug — there is nothing to fall back to). `config()`
never throws — it returns `DEFAULTS` — because canonical defaults exist and a
mod legitimately might not customize them. This asymmetry matches the semantics:
no source ⇒ broken; no config ⇒ run with defaults.

### Injection point (Ф2) — client-init, beside `Patcher.install`

Both mods already have a `ClientModInitializer.onInitializeClient()` that calls
`Patcher.install(...)`. The shadow install goes on the next line:

- redactor — [`IRLRedactorClient.onInitializeClient()`](../../irlights/src/client/java/org/qualet/irlredactor/client/IRLRedactorClient.java) L51:
  ```java
  Patcher.install(new RedactorPatcherHost());
  ShadowEngine.install(new RedactorEntityCasterSource(), LightConfig.SHADOW);
  ```
- addon — [`IrliteClient.onInitializeClient()`](../../bbs-irlights-addon/src/client/java/qualet/irlite/client/IrliteClient.java) L12:
  ```java
  Patcher.install(new BbsPatcherHost());
  ShadowEngine.install(new IRLiteBbsCasterSource(), IrliteConfig.SHADOW);
  ```

**Ordering guarantee (load-bearing).** `onInitializeClient()` runs during Fabric
client init — before any world exists and before the first frame.
`ShadowBaker.bake(...)` is only ever invoked from `GameRendererLightMixin` at
renderWorld HEAD (redactor L75 / addon L72), which requires a live world. So
`install` always precedes the first `bake`. If a mod ever forgets the install,
the first `bake` hits `ShadowEngine.source()` and fails fast with the message
above — a loud, immediate, correctly-attributed error, not a `NullPointerException`
deep in the bake.

### `ShadowBaker` delta in Ф2 (informative — not Ф1 code)

To make `ShadowBaker` free of per-mod symbols (so it moves to core as one file):
- delete `import …LightConfig;` and the `SOURCE` field (L1195);
- read the source once per bake: `ShadowCasterSource source = ShadowEngine.source();`
  (held in a static field or threaded to `renderInRangeCone`/`renderInRangeFace`
  for the two `emitCaster` sites);
- replace the five `LightConfig.x()` calls with `ShadowEngine.config().x()` (or a
  `ShadowConfig cfg = ShadowEngine.config()` local at the top of `bakeInner`);
- retarget the two javadoc `{@link LightConfig#…}` to `{@link ShadowConfig#…}`.

After these edits `ShadowBaker` contains zero per-mod identifiers, so it (and the
other 13 files, already free of them) normalize away the last two allowlist
entries → the move to a single shared source is byte-clean.

---

## Frozen decisions (do not reopen without cause)

1. **`ShadowConfig` is a 5-getter PULL interface**, a strict subset of each mod's
   config. Forced by code: addon config is lazily-read BBS `Value*`; there is no
   push source to invert. *(Not a real fork in the road — recorded for the audit
   trail.)*
2. **One holder, `ShadowEngine`, carries both injected deps** (`source` + `config`)
   with a single `install(source, config)` call, symmetric to `Patcher.install`.
   Rationale: one init call per mod; future shadow-wide deps slot into the same
   holder; mirrors the established core pattern.
3. **`source()` fail-fast, `config()` defaults.** A missing source is a wiring
   bug (no fallback exists); a missing config has canonical `DEFAULTS`.
4. **Canonical defaults move to core** (`ShadowConfig.DEFAULTS` = 1/true/4/true/24),
   ending the cross-repo duplication. Mods still override (both have UIs that
   mutate the values) — DEFAULTS is the floor, not the norm.
5. **The `ShadowCasterSource` interface is untouched** — frozen by
   [shadow-caster-seam-spec.md](shadow-caster-seam-spec.md); this spec only
   reroutes how the instance is delivered.
6. **Package of the moved files = `org.qualet.irl.light.shadow`** (mirrors the
   current `…light.shadow`); `ShadowEngine` + `ShadowConfig` live there too.

## Resolved at sign-off (2026-06-25)

The three review items below were decided in favor of the recommendations:

- **Holder name / shape → `ShadowEngine`** (single holder, one
  `install(source, config)` call). Rejected: terser `Shadows`, split
  `ShadowSource.install` + `ShadowConfigHolder.set`.
- **Config adapter form → nested `LightConfig.SHADOW` / `IrliteConfig.SHADOW`
  singleton** delegating into the existing static class. Rejected: standalone
  `*ShadowConfig` adapter class.
- **Default duplication → leave per-mod literals as-is** for Ф2 minimalism;
  `ShadowConfig.DEFAULTS` is the core fallback only. Revisit only if the per-mod
  defaults ever drift.

---

## Risks specific to this freeze

- **Silent default drift.** The 1/true/4/true/24 defaults live in three places
  after Ф2 (core DEFAULTS + both mods) unless deduped. Centralizing in
  `DEFAULTS` removes the risk; deferring (open item above) keeps it. Low impact
  (defaults rarely change) but worth a note in the move commit.
- **Install-before-bake assumption.** Holds today (client-init precedes
  renderWorld). Any future path that bakes outside `GameRendererLightMixin` must
  re-check this; the fail-fast `source()` converts a violation into a clear
  exception rather than a wrong shadow.
- **Pull timing unchanged.** Reading `ShadowEngine.config()` per frame is the
  same call pattern as today's `LightConfig.x()`; no new per-frame allocation
  (DEFAULTS and the per-mod adapter are singletons).

---

## Grounding (what was read to freeze this, 2026-06-25)

- Every `LightConfig`/`IrliteConfig` occurrence in both `light/shadow/`
  directories → all in `ShadowBaker.java`, identical line-for-line modulo the
  class name; 5 distinct getters.
- Every `RedactorEntityCasterSource`/`IRLiteBbsCasterSource` occurrence → only
  the `SOURCE` initializer (L1195) + the class declarations.
- `LightConfig.java` (redactor) + `IrliteConfig.java` (addon) → getter set +
  identical defaults.
- `IRLRedactorClient` + `IrliteClient` → symmetric `onInitializeClient` with
  `Patcher.install`.
- `Patcher.java` (core) → the holder pattern this spec mirrors.
- `verify-shadow-lockstep.py` + lockstep doc → the allowlist this contract must
  (and does) cover.
- Verifier run at freeze commits → `PASS: all 14 in lockstep`.
