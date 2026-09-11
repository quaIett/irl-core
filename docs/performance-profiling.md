# Shadow profiling, redesign stage 0

`ShadowBakeProbe.detailedTimings()` is an optional, default-false extension.
Existing hosts keep their old coarse partition. An opting-in host also gets
`bake-spot-copy` / `bake-point-copy` around static-to-live restoration, then the
existing `bake-spot` / `bake-point` name resumes. Copy includes full/partial spot
rectangles and full/per-face point copies. The remainder includes draw, clear
and pass setup; it is not exclusively triangle work.

**A section name may repeat many times in one frame.** Sum all its GPU query
results by issue-frame before computing statistics. A mean over individual
queries understates the per-frame cost. Compute the whole bake by summing all
`bake-*` sections of each complete frame. Reject frames with missing queries;
never report a partial bake as a faster complete bake. Queries must remain
non-nested and nonblocking. Enable detailed timing only for diagnostics because
the extra query boundaries have overhead.

The change adds no texture, filter, shadow-cache or caster behavior. The five
frozen caster-seam invariants and the static bake budgets are unchanged. No
per-light GPU scheduling or silhouette-version contract has been implemented.

The reference host, capture tools, build commands and ordered redesign plan are
in `../bbs-irlights-addon/docs/performance-redesign.md` (relative to the core
repository root). The implementation targets the current main/master MC 1.20.4
line; other repositories and version branches are outside this stage.
