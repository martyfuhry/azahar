# AYN Thor: first real device snapshot

Read-only `adb` snapshot taken 2026-09-09 with Marty's consent, one minute, nothing installed
or changed. This is the **first data ever taken from the target device**; every other number
in this repository comes from the Galaxy Z Fold5 lab phone.

> **Superseded in two places — read `2026-09-09-thor-diagnostics.md` alongside this.** A
> longer read-only session later the same day pulled the full `exit-info` history and the
> Azahar crash stack, and it corrects this note twice:
>
> 1. **melonDS has eight foreground native crashes, not two** (2026-08-24 through 08-29),
>    across **three different fatal signals** — 6× SIGSEGV, 1× SIGTRAP, 1× SIGABRT. This note
>    saw only the newest pair.
> 2. **The conclusion below that "the primary fix is the #1624 crash fix" is wrong and is
>    struck.** `cc6c3ba3` is a one-line *Kotlin* change, so a failure there is recorded as
>    `reason=4 APP CRASH(EXCEPTION)`; every melonDS loss here is `reason=5 APP CRASH(NATIVE)`,
>    a fatal signal in native code. This very device records both reasons hours apart, so the
>    framework is distinguishing them. And one null dereference at one source line cannot
>    produce three different fatal signals.
>
> The identifiers, the driver version, the absence of `LOW_MEMORY` and excessive-CPU records,
> and the Azahar crash record are all unaffected and stand.

## Identifiers (closes the open item in the plan)

| Property | Value |
|---|---|
| `ro.product.manufacturer` | `AYN` |
| `ro.product.brand` | `qti` |
| `ro.product.model` | `AYN Thor` |
| `ro.product.device` / `ro.product.name` | `kalama` |
| Android | 13, SDK 33 |
| `ro.soc.model` | `QCS8550` |
| GPU / driver | Adreno (TM) 740, Qualcomm Adreno Vulkan Driver **512.676.53** |

`ThorDefaults.knownDevices` can now match on real strings. Match on **manufacturer `AYN` plus
model `AYN Thor`**; do **not** match `ro.product.device` alone — `kalama` is Qualcomm's
platform name for the SM8550 and is shared with unrelated hardware. Note the brand is `qti`,
not `AYN`, so a brand-based match would fail. Driver is 512.676.**53**, newer than the Fold5's
512.676.1, which is worth knowing before assuming a driver workaround transfers.

## Kill and crash history

### melonDS (`me.magnum.melonds`, version **2.0.1 GH**, the release build)

**This table is incomplete — the full sixteen-row history is in
`2026-09-09-thor-diagnostics.md` §1.** It records the newest two of **eight** foreground
native crashes, and it misses that the eight span three different fatal signals.

| When | Reason | Importance | RSS |
|---|---|---|---|
| 2026-08-29 20:22:06 | **`reason=5 APP CRASH (NATIVE)`, status 11 (SIGSEGV)** | **100 (foreground)** | 438 MB |
| 2026-08-29 20:24:37 | **`reason=5 APP CRASH (NATIVE)`, status 11 (SIGSEGV)** | **100 (foreground)** | 547 MB |
| 2026-09-02 ×3, 2026-09-07 ×3 | `reason=2 SIGNALED`, status 9 (SIGKILL) | 230 / 400 | 0 (mostly) |

**There is not one `reason=3 LOW_MEMORY` record, and not one excessive-CPU record.** That
holds across the full sixteen-row history too.

This contradicts the working theory. The lab phone demonstrated that stock melonDS *can* be
reclaimed under induced memory pressure, and the maintainer's own explanation to another Thor
owner was memory. But on this device the losses were **native crashes in the foreground**,
several of them in crash-relaunch-crash pairs minutes apart, rather than a background reclaim.

The later SIGKILL entries have `rss=0` and `state=empty`, i.e. ordinary reclaim of an already
empty process, not a live session dying.

~~**Conclusion:** for this user on this device, the primary fix is the #1624 crash fix (present
in upstream master, therefore inherited by the fork), not the foreground service.~~

**Struck 2026-09-09.** #1624 does not fit and is ruled out: `cc6c3ba3` is a one-line Kotlin
change in `ExternalPresentation.kt`, whose failure mode is an uncaught exception
(`reason=4 APP CRASH(EXCEPTION)`), and every melonDS loss here is `reason=5 APP CRASH(NATIVE)`
— a fatal signal. Independently, eight crashes across three different fatal signals cannot come
from one null dereference at one line; that pattern reads as memory corruption or a
use-after-free. See `2026-09-09-thor-diagnostics.md` §1.

What survives: **the foreground service was not the cause either**, and the service and the
autosave remain defence in depth against a reclaim this device has never recorded. The actual
melonDS bug is **unknown, still unfixed, and not addressed by anything in the fork**. The
stack is unrecoverable — DropBox retention is ~3 days and the crashes are eleven days old — so
the only way forward is to capture a fresh one.

### Azahar (`org.azahar_emu.azahar.thor`)

One record: 2026-09-08 21:11:37, `reason=5 APP CRASH (NATIVE)`, status 11. That is the rc1
session, and it lines up with the crash he reported after changing the texture filter mid-game
— the upstream race documented in `research/texture-filter-crash.md`. **Nothing since rc2 was
installed** (2026-09-09 09:27).

**Confirmed since:** the stack for that crash *was* recovered from DropBox — `VulkanWorker`,
`qglinternal::vkCmdEndRenderPass`, `Vulkan::Scheduler::WorkerThread`, fault address `0xb4`, an
exact match for signature #1 in the research note, with `texture_filter = xBRZ` at
`resolution_factor = 4` persisted at crash time. See `2026-09-09-thor-diagnostics.md` §2. The
"nothing since rc2" observation is an absence over part of one day, not a soak result.

Also installed: `me.magnum.melondualds` 0.7.0.rc5 — WatermelonDS, the Vulkan fork.

## What this changes

- The melonDS fork's headline claim should be stated as **defence in depth**, not as the fix
  for what he experienced. Correct `melonds-android/RELEASE-NOTES.md`, which frames the
  foreground service as the answer to the lid problem. **Done.**
- ~~`docs/fork/upstream-candidates.md`: the melonDS entry should note that the crash this user
  hit is already fixed upstream and only needs a nightly, which may make the fork unnecessary
  for him.~~ **Struck** — see the banner at the top. The crash is *not* established as fixed
  upstream, and "take a nightly" must not be told to Marty. What `upstream-candidates.md`
  records instead is that the melonDS losses are native crashes of unknown cause.
- The Thor defaults allowlist can be completed from the identifiers above. **Done** in
  `ThorDefaults.kt`.
- The Azahar crash stack was recovered and confirms the texture-filter race on the Thor's own
  driver; see `2026-09-09-thor-diagnostics.md` §2 and `research/texture-filter-crash.md`.
