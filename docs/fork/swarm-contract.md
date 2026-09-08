
## Wave 2 (started 2026-09-08 ~19:30, all agents on Opus; wave 1 merged at 009cfe333)

| Agent | Branch | Items | Device? |
|---|---|---|---|
| agent-measure-rc1 | (none; measures) | baseline b8aa5f893 vs candidate 009cfe333 with tools/thor/measure.sh | yes, first |
| agent-secondary-window | `perf/secondary-window` | G-1 | host only now |
| agent-trim | `perf/trim-memory` | M-2 | host only now |
| agent-boot | `perf/boot` | L-1, G-3, L-2 | host only now |
| agent-thor-display | `fix/thor-display` | T-2, T-3, T-4 | host only now |

Wave 2 rule: agents verify on the host (build, tests, Kotlin compile, APK builds) and leave device measurement to a later measurement pass; the phone belongs to agent-measure-rc1. Release flow: after measurement, an Opus release agent writes `docs/fork/releases/thor-v1-rc1.md` from the measurement table and the commit bodies, tags `thor-v1-rc1`, and copies the APK to `~/Development/azahar-builds/azahar-thor-v1-rc1.apk`.
