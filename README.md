<div align="center">

# ✦ irl-core

**The BBS-free shared engine behind IRLights & IRL-editor**

*A plain-Java library — zero Minecraft, zero Loom — that holds the light SSBO pipeline and the `.irlights` shaderpack patcher. One Java&nbsp;17 jar serves every Minecraft version.*

[![Java](https://img.shields.io/badge/Java-17-f89820?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-MIT-3da639?style=flat-square)](LICENSE)
[![Status](https://img.shields.io/badge/version-1.0--obt-blue?style=flat-square)](#)

</div>

---

## What is irl-core?

`irl-core` is the common heart of two sibling mods:

- **[IRLights](https://github.com/quaIett/bbs-irlights-addon)** — a BBS Mod Studio add-on
- **[IRL-editor](https://github.com/quaIett/irl-editor)** — a standalone ImGui light editor

It contains everything that has **no dependency on Minecraft or Fabric**: the GPU light buffer and the shaderpack patcher. Because it touches zero Minecraft types, a **single Java&nbsp;17 build** runs unchanged on every consumer (MC&nbsp;1.20.1 → 1.21.11, Java&nbsp;17 → 21).

---

## What's inside

| Subsystem | Package | What it does |
|---|---|---|
| **Light pipeline** | `org.qualet.irl.light` | `LightBuffer` — std430 SSBO at **binding 7**, up to **2048 lights/frame**; `LightRegistry` — collects & uploads light data to the GPU |
| **Patcher engine** | `org.qualet.irl.patcher` | Parses & applies anchor-based `.irlights` patch files into shaderpacks (`IrlPatchParser`, `IrlPatchApplier`, `PatchEngine`, `PatchLibrary`, `Shaderpacks`, `Patcher`, `PatcherHost`) |

The patcher is **validate-first**: it aggregates every error before touching a file, is EOL-tolerant, works straight on zipped packs, and supports dry-run + rollback.

---

## How it's consumed

Both mods pull `irl-core` in via a **Gradle composite build**, then bundle it into their jar with Loom's `include` (JiJ):

```groovy
// settings.gradle (each mod)
includeBuild("../irl-core")

// build.gradle (each mod)
dependencies {
    include implementation("org.qualet:irl-core:1.0-obt")
}
```

Environment-specific bindings (game directory, Iris API, "open folder") stay in each mod via a `PatcherHost` implementation — `irl-core` stays pure.

---

## Building

```bash
./gradlew build
# output: build/libs/irl-core-1.0-obt.jar (+ sources jar)
```

Requires a JDK 17+. The library compiles with `--release 17`; the resulting bytecode runs as-is on the Java&nbsp;21 runtime used by the 1.21.x ports.

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
