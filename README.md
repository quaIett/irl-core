<div align="center">

# ✦ irl-core

**The BBS-free shared engine behind IRLights & IRL-editor**

*A Fabric/Loom module (per-version MC-typed) that holds the light SSBO pipeline, shadow orchestration, and the `.irlights` shaderpack patcher. Built as Java&nbsp;17 for MC&nbsp;1.20.4; remapped to intermediary and bundled by both consumers (IRLights & IRL-editor).*

[![Java](https://img.shields.io/badge/Java-17-f89820?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-MIT-3da639?style=flat-square)](LICENSE)
[![Status](https://img.shields.io/badge/built%20with-Loom%201.9-4060ff?style=flat-square)](#)

</div>

---

## What is irl-core?

`irl-core` is the common heart of two sibling mods:

- **[IRLights](https://github.com/quaIett/bbs-irlights-addon)** — a BBS Mod Studio add-on
- **[IRL-editor](https://github.com/quaIett/irl-editor)** — a standalone ImGui light editor

It bundles the GPU light buffer, shadow orchestration (MC-typed), and the shaderpack patcher. Built with Loom 1.9 (the oldest consumer's version; consumers reject newer Loom jars) and remapped to intermediary format. Because intermediary names are MC-version–specific, this artifact pairs exclusively with MC&nbsp;1.20.4 consumers; the name mappings are re-targeted by each mod's Loom at build time (addon uses `-Pmc=1.20.4`, editor uses native 1.20.4).

---

## What's inside

| Subsystem | Package | What it does |
|---|---|---|
| **Light pipeline** | `org.qualet.irl.light` | `LightBuffer` — std430 SSBO at **binding 7**, up to **2048 lights/frame**; `LightRegistry` — collects & uploads light data to the GPU |
| **Patcher engine** | `org.qualet.irl.patcher` | Parses & applies anchor-based `.irlights` patch files into shaderpacks (`IrlPatchParser`, `IrlPatchApplier`, `PatchEngine`, `PatchLibrary`, `Shaderpacks`, `Patcher`, `PatcherHost`) |

The patcher is **validate-first**: it aggregates every error before touching a file, is EOL-tolerant, works straight on zipped packs, and supports dry-run + rollback.

---

## How it's consumed

The core publishes a remapped (intermediary) jar to the local Maven repository. Each consumer builds it first:

```bash
./gradlew publishToMavenLocal
```

Then both mods pull `irl-core` from mavenLocal and bundle it into their jar with Loom's `include` (JiJ):

```groovy
// build.gradle (each mod)
dependencies {
    modClientImplementation("org.qualet:irl-core:${irl_core_version}") // Loom remaps intermediary → named
    include("org.qualet:irl-core:${irl_core_version}")
}
```

The version is read from `gradle.properties` (currently `1.1`). Environment-specific bindings (game directory, Iris API, "open folder") stay in each mod via a `PatcherHost` implementation — `irl-core` stays pure.

---

## Building

```bash
./gradlew build
# output: build/libs/irl-core-1.1.jar (+ sources jar)
./gradlew publishToMavenLocal
# publishes remapped jar to ~/.m2/repository/org/qualet/irl-core/1.1/
```

Requires a JDK 17+. Compiles with `--release 17`; the resulting (remapped) bytecode runs unchanged when consumers re-target it to their own named mappings (addon → 1.20.4 with Loom 1.15.5, editor → 1.20.4 with Loom 1.9).

---

## The trilogy

| Repo | Role |
|---|---|
| **irl-core** *(this repo)* | Shared engine: light SSBO + `.irlights` patcher |
| [bbs-irlights-addon](https://github.com/quaIett/bbs-irlights-addon) | IRLights — BBS Mod Studio add-on |
| [irl-editor](https://github.com/quaIett/irl-editor) | IRL-editor — standalone ImGui editor |

---

## License

Released under the [MIT License](LICENSE) — © 2026 qualet.
