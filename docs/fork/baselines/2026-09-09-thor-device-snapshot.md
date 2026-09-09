# AYN Thor: first real device snapshot

Read-only `adb` snapshot taken 2026-09-09 with Marty's consent, one minute, nothing installed
or changed. This is the **first data ever taken from the target device**; every other number
in this repository comes from the Galaxy Z Fold5 lab phone.

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

| When | Reason | Importance | RSS |
|---|---|---|---|
| 2026-08-29 20:22:06 | **`reason=5 APP CRASH (NATIVE)`, status 11 (SIGSEGV)** | **100 (foreground)** | 438 MB |
| 2026-08-29 20:24:37 | **`reason=5 APP CRASH (NATIVE)`, status 11 (SIGSEGV)** | **100 (foreground)** | 547 MB |
| 2026-09-02 ×3, 2026-09-07 ×3 | `reason=2 SIGNALED`, status 9 (SIGKILL) | 230 / 400 | 0 (mostly) |

**There is not one `reason=3 LOW_MEMORY` record, and not one excessive-CPU record.**

This contradicts the working theory. The lab phone demonstrated that stock melonDS *can* be
reclaimed under induced memory pressure, and the maintainer's own explanation to another Thor
owner was memory. But on this device the losses were **native crashes in the foreground**, two
of them two and a half minutes apart, which is the signature of crash-relaunch-crash rather
than a background reclaim. The installed build is the **2.0.1 release**, which does not carry
the fix for upstream issue #1624 ("Game Crashes When Device Goes to Sleep", filed from an AYN
Thor, traced to the external-display path, shipped **nightly only**).

The later SIGKILL entries have `rss=0` and `state=empty`, i.e. ordinary reclaim of an already
empty process, not a live session dying.

**Conclusion:** for this user on this device, the primary fix is the #1624 crash fix (present
in upstream master, therefore inherited by the fork), not the foreground service. The service
and the autosave remain worth having as defence in depth, but they were not the cause.

### Azahar (`org.azahar_emu.azahar.thor`)

One record: 2026-09-08 21:11:37, `reason=5 APP CRASH (NATIVE)`, status 11. That is the rc1
session, and it lines up with the crash he reported after changing the texture filter mid-game
— the upstream race documented in `research/texture-filter-crash.md`. **Nothing since rc2 was
installed** (2026-09-09 09:27), across a full day of play.

Also installed: `me.magnum.melondualds` 0.7.0.rc5 — WatermelonDS, the Vulkan fork.

## What this changes

- The melonDS fork's headline claim should be stated as **defence in depth**, not as the fix
  for what he experienced. Correct `melonds-android/RELEASE-NOTES.md`, which frames the
  foreground service as the answer to the lid problem.
- `docs/fork/upstream-candidates.md`: the melonDS entry should note that the crash this user
  hit is already fixed upstream and only needs a nightly, which may make the fork unnecessary
  for him.
- The Thor defaults allowlist can be completed from the identifiers above.
