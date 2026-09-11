# Light pipeline verification

Run `tools/verify-pipeline.ps1 -JavaHome <JDK>` from any directory. It discovers
JOML in the workspace `.gradle-user` cache; `-JomlJar` and `-GradleUserHome` can
override that location. It compiles the real `ClusterGridBuffer`, `IrlSamplers`
and `IrlSamplersBind` classes with the production JOML library. Small GL/Iris test
doubles capture the uploaded bytes and bindings; no graphics context is needed.
This verifies CPU behavior and the upload contract, not driver timing or FPS.

## Compact cluster ABI

The buffer allocation remains large enough for 2048 lights. Only the populated
prefix is uploaded. `header.w = max(1, ceil(packedLightCount / 32))` specifies the
number of uint words per tile in this frame. The first regions never move:

| Region | Byte offset | Size |
| --- | ---: | ---: |
| `uvec4 irlite_clusterHeader` | 0 | 16 |
| Legacy `uvec2 irlite_clusterMasks[576]` | 16 | 4608 |
| Wide `uint irlite_clusterWide[]` | 4624 | `576 * header.w * 4` |

The source audit covers **all 21 shipped variants**, seven families in each of
IRLite's `patches`, IRL-redactor's bundled patches, and IRL+DOF's combo patches:
Bliss, BSL, Complementary Reimagined, IterationRP, Photon, Rethinking Voxels,
and Solas. In every variant, both surface and volumetric passes compute their
wide base as `(ty * gridX + tx) * irlite_clusterHeader.w` and read word
`base + (i >> 5u)` inside a loop bounded by `irlite_lightCount`. Therefore the
largest accessed word belongs to the uploaded prefix. A count of zero reads no
light words. The verification script checks these declarations, both base
functions, both indexed reads and their light-count loops in every source file.

Old-generation readers use only `header.xyz` and the fixed legacy `uvec2[576]`;
they do not interpret `header.w` or read the wide region. The same legacy fetch
functions are retained in all current variants, and the harness verifies every
legacy uint at its original byte offset against the former algorithm. Old
readers continue to handle lights beyond index 63 using their original full-loop
tail. Conversely, current wide readers retain their existing `header.w == 0`
fallback for an old writer. No shader source or existing layout is changed.

The frozen reference rasterizer uses the previous 64-word tile stride and writes
all 576 tiles immediately for every flooded light. The production code uses the
compact stride and combines flooded bits per word before visiting tiles. Tests
compare both legacy and wide uploaded masks against that reference across word
boundaries, 2048 lights, count growth/shrink, full-screen floods, near-plane
straddles, behind-camera and offscreen lights, varied FOV/aspect ratios and
translated/rotated camera matrices. Packed cap/omission sequences are covered.

Additional cases verify snapshot invalidation at begin/disable/empty/delete,
disabled upload idempotence with rebinding, sampler id replacement after texture
recreation, replacement registration order, early exit, both array targets and
leaving zero/unknown/2D textures to Iris's default binding.
