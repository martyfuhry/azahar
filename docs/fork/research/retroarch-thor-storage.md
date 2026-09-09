# RetroArch on the AYN Thor: "No Items" in Load Content, and frontend launches crashing

Research note, 2026-09-09. Diagnosis only — nothing here was run against the Thor.
Every claim about RetroArch's history and code is cited. Anything unverified is marked
**uncertain**.

---

## 0. Bottom line, and the two checks to do first

**Most likely cause: RetroArch's core directory is empty. It has no cores installed.**

That single fact explains both symptoms, and I can show it in the source of the exact build
he is running (v1.22.2, commit 69a4f0e).

With zero cores installed, RetroArch's list of "extensions I know how to open" collapses to
`7z|zip|`. "Filter Unknown Extensions" is **on by default**, and the file browser lists
directories unconditionally but filters files against that list. So a directory full of
`.sfc`/`.nes`/`.gba`/`.chd`/`.iso` files renders as **"No Items"** while the tree above it
still browses perfectly. It looks exactly like a permissions problem and is not one.

The same empty core directory makes the frontends fail: Argosy and Cocoon pass
`LIBRETRO=/data/data/<pkg>/cores/<core>_libretro_android.so`, that file no longer exists,
RetroArch logs a warning, *drops the argument*, and then dies in init with no core path set.

### Check 1 — are there any cores? (5 seconds)

RetroArch → **Main Menu → Load Core**. If the only entries are
"Download a Core" / "Install or Restore a Core" and there is no list of cores below them,
**the cores are gone**. That is the answer.

Fix: **Main Menu → Online Updater → Core Downloader**, and install the cores he uses
(snes9x, mgba, nestopia, beetle-psx, etc.). The Android arm64 core feed his build points at is
`http://buildbot.libretro.com/nightly/android/latest/arm64-v8a/`
(`DEFAULT_BUILDBOT_SERVER_URL` for `__aarch64__` Android in
[config.def.h at v1.22.2](https://github.com/libretro/RetroArch/blob/v1.22.2/config.def.h)) —
I confirmed today that this URL returns HTTP 200 and is live.

### Check 2 — prove it without downloading anything (5 seconds)

RetroArch → **Settings → File Browser → "Filter Unknown Extensions" → OFF**.
While in there, also confirm **"Filter by Current Core" → OFF** (it is the next entry down).

Go back to Load Content and browse to `/storage/emulated/0/roms/<system>/`. If the ROMs
**appear immediately**, the diagnosis is confirmed: it was never a permissions problem, it was
the extension filter with nothing to filter *for*. (He should turn the filter back on after
installing cores — it is the setting that keeps the browser usable.)

### Check 3 — the freebie discriminator

If any of his ROM folders contain `.zip` files, those **will still be listed** even now, while
`.sfc`/`.chd`/etc. in the same folder are not. `7z` and `zip` are hardcoded into the extension
list regardless of installed cores (see §3). A folder that shows the zips and hides everything
else is a positive identification of this bug and rules out storage permissions outright.

---

## 1. What he is actually running — established facts

| | |
|---|---|
| Version | **1.22.2** |
| Git hash | **69a4f0e** |
| Install source | **Obtainium** → official libretro build (not Play Store) |
| Storage permission | granted (his confirmation) |
| Device | AYN Thor, Android 13 |

`69a4f0e` resolves to `69a4f0ea1e8aaf442ae4858f2e7f2b31a1776576`, dated **2025-11-20T00:17:08Z**,
message "Fetch translations from Crowdin". That commit **is the `v1.22.2` tag**:

```
GET /repos/libretro/RetroArch/git/ref/tags/v1.22.2
  -> {'object': {'sha': '69a4f0ea1e8aaf442ae4858f2e7f2b31a1776576', 'type': 'commit'}}
```

<https://github.com/libretro/RetroArch/releases/tag/v1.22.2> (published 2025-11-20T03:12:17Z).

So he is on **the stable v1.22.2 release from November 2025**, not a 2026 nightly. This matters
enormously, because it rules out the entire 2026 Android storage rework (§7). v1.22.2 ships
`targetSdkVersion 28`, which means Android still grants it **legacy external storage** — it does
not live under scoped storage at all, and "All files access" is irrelevant to it.

Note also: **the v1.22.2 GitHub release carries no APK assets** — only
`retroarch-sourceonly-1.22.2.tar.xz`. So Obtainium is not tracking libretro's GitHub *releases*
for the APK; it must be on an HTML/direct-APK source (buildbot or retroarch.com). That is
relevant in §6.

---

## 2. Ranked causes

1. **The cores directory is empty** (`/data/data/<pkg>/cores/`), so the browser's extension
   filter reduces to `7z|zip|` and the frontends' core path is dangling. Explains both
   symptoms with one cause. **This is the leading hypothesis.** §3, §4.
2. **The update was an uninstall + reinstall, which is why the cores are gone** — and it also
   wiped `retroarch.cfg`, so his directory settings are back to defaults. §6.
3. **"Filter by Current Core" is on with a core loaded** whose extensions don't match the
   folder. Same visible symptom, different trigger, same 5-second check. §3.
4. **The frontends are pointed at the wrong package** (`com.retroarch` vs
   `com.retroarch.aarch64` vs `com.retroarch.ra32`) after a variant switch, so their
   `startActivity` throws in *their* process. §4.
5. RetroArch's configured `core_directory`/`libretro_directory` in `retroarch.cfg` points
   somewhere the cores aren't (only possible if the cfg survived; §5 Step 4).
6. **Ruled out as the primary cause:** scoped storage / `MANAGE_EXTERNAL_STORAGE`. v1.22.2
   targets SDK 28 and he has the permission. Kept in §7 only because it is a live landmine for
   his *next* update.

---

## 3. The mechanism, proven in v1.22.2's source

Three files, all at tag `v1.22.2`.

### 3.1 The extension list is built only from *installed* cores

`core_info.c`, `core_info_list_new()` builds its list by listing the **cores directory** for
`.so` files — `core_info_list->count = path_list->core_list->size` — and marks each
`info->is_installed = true`. Then:

```c
static size_t core_info_list_resolve_all_extensions(
      core_info_list_t *core_info_list)
{
   for (i = 0; i < core_info_list->count; i++)
      if (core_info_list->list[i].supported_extensions)
         all_ext_len += (strlen(...) + 2);

   all_ext_len += STRLEN_CONST("7z|") + STRLEN_CONST("zip|");
   ...
#ifdef HAVE_7ZIP
   _len += strlcpy(core_info_list->all_ext + _len, "7z|",  all_ext_len - _len);
#endif
#ifdef HAVE_ZLIB
   _len += strlcpy(core_info_list->all_ext + _len, "zip|", all_ext_len - _len);
#endif
```

<https://github.com/libretro/RetroArch/blob/v1.22.2/core_info.c>

**With `count == 0`, `all_ext` is exactly `"7z|zip|"`.** Not empty — which is the crux; an empty
string would have disabled the filter entirely and hidden this bug.

On Android the cores directory is `<DATADIR>/cores`, i.e. `/data/data/<pkg>/cores`
(`platform_unix.c` v1.22.2: `fill_pathname_join(g_defaults.dirs[DEFAULT_DIR_CORE], app_dir, "cores", …)`).

### 3.2 The Load Content browser uses that list as its filter

`menu/cbs/menu_cbs_deferred_push.c`, `PUSH_DETECT_CORE_LIST` — the id behind
`deferred_push_detect_core_list`, which is what Load Content / Favorites / the content browser
push:

```c
case PUSH_ARCHIVE_OPEN_DETECT_CORE:
case PUSH_DETECT_CORE_LIST:
{
   struct retro_system_info *sysinfo = &runloop_state_get_ptr()->system.info;
   bool filter_by_current_core       = settings->bools.filter_by_current_core;

   if (sysinfo && !string_is_empty(sysinfo->valid_extensions) && filter_by_current_core)
      ...use the currently loaded core's extensions...      /* ← cause #3 */
   else
   {
      core_info_list_t *list = NULL;
      core_info_get_list(&list);
      if (list && !string_is_empty(list->all_ext))
         ...use all_ext...                                   /* ← "7z|zip|" */
   }
}
...
if (!string_is_empty(newstr2)) { free(info->exts); info->exts = newstr2; }
```

<https://github.com/libretro/RetroArch/blob/v1.22.2/menu/cbs/menu_cbs_deferred_push.c>

### 3.3 Directories are listed unconditionally; files are not

`menu/menu_displaylist.c`, `filebrowser_parse()`:

```c
ret = dir_list_initialize(&str_list, full_path,
      filter_ext ? exts : NULL,
      true,                    /* <- include directories, always */
      show_hidden_files, true, false);
```

and the caller:

```c
bool filter_supported_extensions_enable =
   settings->bools.menu_navigation_browser_filter_supported_extensions_enable;

filebrowser_parse(info->list, info->path, info->exts, info->label,
      info->type_default, type, show_hidden_files, …,
      filter_supported_extensions_enable);
```

<https://github.com/libretro/RetroArch/blob/v1.22.2/menu/menu_displaylist.c>

`include_dirs = true` is why **the tree still navigates**. `filter_ext ? exts : NULL` is why
**the files vanish**. When nothing survives the filter, the list is empty and the menu renders
`MENU_ENUM_LABEL_VALUE_NO_ITEMS` — which in `intl/msg_hash_us.h` at v1.22.2 is the string
**"No Items"**, verbatim what he is seeing.

### 3.4 The setting, verbatim

`menu/menu_setting.c` v1.22.2, `case SETTINGS_LIST_MENU_FILE_BROWSER:` — group
`MENU_ENUM_LABEL_VALUE_MENU_FILE_BROWSER_SETTINGS`, i.e. **Settings → File Browser**:

| setting | UI label (en_US) | default |
|---|---|---|
| `menu_navigation_browser_filter_supported_extensions_enable` | **"Filter Unknown Extensions"** | `true` |
| `filter_by_current_core` | **"Filter by Current Core"** | next entry down |

---

## 4. Why the frontends crash

### 4.1 Argosy's intent — verified from source

"Argosty" is **Argosy**, the RomM Android client, `rommapp/argosy-launcher`, package
`com.nendo.argosy` <https://github.com/rommapp/argosy-launcher>. (**Uncertain**: the spelling in
the report; if it is a different app, §4.3 still holds.)

`EmulatorRegistry.kt` defines three RetroArch targets — `com.retroarch`,
`com.retroarch.aarch64`, `com.retroarch.ra32` — all with `launchAction = Intent.ACTION_MAIN` and

```kotlin
data class RetroArch(
    val activityClass: String = "com.retroarch.browser.retroactivity.RetroActivityFuture"
) : LaunchConfig() {
    override val defaultIntentFlags: Int =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
```

`GameLauncher.kt::commandForRetroArch` builds these extras (verbatim from source):

| extra | value |
|---|---|
| `ROM` | `romFile.absolutePath` — a **plain path**, not a URI |
| `LIBRETRO` | **`/data/data/<pkg>/cores/<core>_libretro_android.so`** |
| `CONFIGFILE` | `/storage/emulated/0/Android/data/<pkg>/files/retroarch.cfg` |
| `IME` | `com.android.inputmethod.latin/.LatinIME` |
| `DATADIR` | `/data/data/<pkg>` |
| `SDCARD` | primary external root |
| `EXTERNAL` | `/storage/emulated/0/Android/data/<pkg>/files` |

plus a `FileProvider` `content://` URI attached as `clipData` with
`FLAG_GRANT_READ_URI_PERMISSION`, added (per its own code comment) because "On Android 11+
scoped storage, RetroArch reading `/storage/<vol>/...` raw can fail in the receiving UID". The
ROM is still handed over as a **path**; the URI is belt-and-braces that RetroArch's loader never
consumes.

The `LIBRETRO` value is the interesting one: **a hardcoded path into RetroArch's private data
directory.** Argosy cannot check whether it exists — that directory is `0700` and belongs to
another uid. If the cores are gone, Argosy has no way to know and hands over a dangling path.

### 4.2 What v1.22.2 does with a dangling `LIBRETRO`

`retroarch.c` v1.22.2, line ~6910:

```c
else
   RARCH_WARN("--libretro argument \"%s\" is not a file, core name"
         " or directory. Ignoring.\n", path ? path : "");
```

It **warns at a level invisible at default verbosity and then discards the argument**. Init then
reaches the point where a dynamic-core build has no core path, and `retroarch_fail()` longjmps
out of `retroarch_main_init()`. From the outside: the activity comes up and dies, or sits on a
black window. That is the user-visible "it crashes".

This exact behaviour is an open upstream issue filed against master:
**#19357 "(Android) External launch with an unresolvable `LIBRETRO` core path hangs on a black
screen with no error at default log level"**, 2026-08-05,
<https://github.com/libretro/RetroArch/issues/19357>. Its analysis applies verbatim to v1.22.2 —
same code — and it makes the same point: *"RetroArch's cores directory is private and 0700, so a
calling app has no way to check whether a core is installed before launching."*

### 4.3 The other way a frontend crashes: the package moved

The frontends target an **explicit** `ComponentName(package, class)`. If the Obtainium update
installed a *different variant* — `RetroArch.apk` is `com.retroarch`, `RetroArch_aarch64.apk` is
`com.retroarch.aarch64`, `RetroArch_ra32.apk` is `com.retroarch.ra32` — then the package the
frontend was configured for may no longer be installed, `startActivity` throws
`ActivityNotFoundException` **inside the frontend's process**, and an uncaught one is a frontend
crash. This is easy to tell apart from 4.2: check `adb logcat -b crash` for a stack in
`com.nendo.argosy` (frontend's fault, §5 Step 6) versus RetroArch dying on its own.

The *class* name is safe either way. Upstream's 2026-08-20 launcher rework deliberately kept
`com.retroarch.browser.mainmenu.MainMenuActivity` alive as an `activity-alias`, and
`RetroActivityFuture` is still `android:exported="true"`
(commit [d67a52655](https://github.com/libretro/RetroArch/commit/d67a52655)) — and none of that
is even in his November-2025 build.

### 4.4 Cocoon — not verified

Cocoon (<https://cocoon-shell.com/wiki/getting-started/>, setup guide at
<https://www.joeysretrohandhelds.com/guides/cocoon-setup-guide/>) is an Android frontend
explicitly built for retro handhelds **including the AYN Thor**. The public repo
`inssekt/CocoonFE` <https://github.com/inssekt/CocoonFE> is Python/theme assets, not the app
source, and contains no launch code. **I could not verify Cocoon's intent shape.** The
ROM/LIBRETRO/CONFIGFILE extra set above is the universal Android→RetroArch convention (ES-DE,
Daijishō and Dig all use it), and Cocoon is documented as launching RetroArch with a
per-system configurable core — its GBA default is `vba_next` — so it is near-certain to be doing
the same thing. Treat that as inference, not fact. The practical consequence is identical: if the
core it names isn't installed, the launch dies.

---

## 5. What to do, in order

### Step 1 — Load Core (Check 1 above)

Main Menu → **Load Core**. Empty ⇒ diagnosis confirmed.

### Step 2 — Core Downloader

Main Menu → **Online Updater → Core Downloader** → install the cores he needs.
While there, also run **Online Updater → Update Core Info Files** — the `.info` files carry the
per-core extension lists that feed the filter, and a wiped install can have stale ones.

### Step 3 — turn the filter off temporarily (Check 2 above)

Settings → File Browser → **Filter Unknown Extensions** OFF, **Filter by Current Core** OFF.
Confirm the ROMs appear. Turn Filter Unknown Extensions back on once cores are installed.

### Step 4 — check the directory settings survived

Settings → **Directory**. Verify **File Browser**, **Core**, **Core Info**, **System/BIOS**,
**Saves**, **Save States**, **Playlists**. If the config was wiped (§6) these are all back to
defaults and his ROM start directory is gone. Point File Browser at
`/storage/emulated/0/roms`, then **Main Menu → Configuration File → Save Current Configuration**.

### Step 5 — rescan, and re-point the frontends

- Main Menu → **Import Content → Manual Scan**: set Content Directory to
  `/storage/emulated/0/roms/<system>`, set System Name and Default Core, Start Scan.
- In **Argosy** and **Cocoon**, re-check which RetroArch package each is configured for and
  which core each system is set to. A frontend pointing at a core he did not re-download will
  still fail after everything above is fixed.

### Step 6 — with a computer: `adb`

Identify the package(s). **More than one line here is itself a finding** (§4.3):

```bash
adb shell pm list packages | grep -i retroarch
PKG=com.retroarch.aarch64      # set to whichever line came back
```

Was it reinstalled, or updated in place? This is the decisive question for §6:

```bash
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode|targetSdk|installerPackageName|firstInstallTime|lastUpdateTime"
```

- `firstInstallTime` **also** in early September ⇒ it was **uninstalled and reinstalled**, and
  `/data/data/$PKG` (the cores) and `/sdcard/Android/data/$PKG/files/retroarch.cfg` were
  destroyed. That is the whole story.
- `firstInstallTime` months older, only `lastUpdateTime` recent ⇒ it was a true in-place update
  and the cores should have survived; look harder at Step 4 and at `core_directory` in the cfg.
- `targetSdk=28` confirms v1.22.2 and confirms storage is not the issue.
  `targetSdk=36` would mean he is *not* on v1.22.2 after all — go read §7.
- `installerPackageName` should name Obtainium (`dev.imranr.obtainium` or similar); if it says
  `com.android.vending` he is on a Play build and §7 applies.

Storage sanity, even though it should be fine:

```bash
adb shell appops get "$PKG" MANAGE_EXTERNAL_STORAGE      # irrelevant on targetSdk 28, but free
adb shell dumpsys package "$PKG" | grep -A 30 "runtime permissions"
```

Look directly at the cores directory. `/data/data` is not readable from `adb shell` without root,
but the config in external storage is:

```bash
adb shell ls -l /sdcard/Android/data/$PKG/files/
adb pull      /sdcard/Android/data/$PKG/files/retroarch.cfg ./retroarch.cfg
grep -E "libretro_directory|libretro_info_path|rgui_browser_directory|system_directory|savefile_directory|savestate_directory" retroarch.cfg
adb shell ls -l /sdcard/roms/ | head
```

Catch the frontend launch failure:

```bash
adb logcat -c
# now launch a game from Argosy/Cocoon on the device
adb logcat -v time -d | grep -Ei "retroarch|retroactivity|androidruntime|nendo\.argosy|cocoon|libc|ActivityManager.*(died|crash|ANR)"
adb logcat -b crash -v time -d
```

Reading the result:

| what you see | meaning |
|---|---|
| `--libretro argument "…" is not a file, core name or directory. Ignoring.` | §4.2 — **the missing core.** Only visible with verbose logging on. |
| `ActivityNotFoundException` with a `com.nendo.argosy` stack | §4.3 — the frontend is pointed at a package that isn't installed |
| `SIGSEGV` under `com.retroarch*` | a genuine RetroArch crash; capture the tombstone |
| log stops after `[ENV] Default screenshot folder: …`, black screen | §4.2 again (this is the #19357 signature) |

Turn verbose logging on first, or you will not see the line that matters:
Settings → **Logging** → Logging Verbosity **ON**, Log to File **ON**, Timestamped Log Files
**ON**. Files land in the directory shown at Settings → Directory → **Logs**, named
`retroarch__YYYY_MM_DD__HH_MM_SS.log`.

You can also reproduce the frontend's launch by hand, which isolates the core path from
everything else:

```bash
adb shell am start -a android.intent.action.MAIN \
  -n $PKG/com.retroarch.browser.retroactivity.RetroActivityFuture \
  -e ROM "/storage/emulated/0/roms/snes/Some Game.sfc" \
  -e LIBRETRO "/data/data/$PKG/cores/snes9x_libretro_android.so" \
  -e DATADIR "/data/data/$PKG"
```

If that fails the same way, it is RetroArch's side, not the frontend's.

---

## 6. What the "update" most likely did

He is on the November-2025 stable. Something a week ago removed his cores. Two mechanisms, both
of which force an **uninstall**, and an uninstall on Android deletes `/data/data/<pkg>/`
(**all downloaded cores**) and `/sdcard/Android/data/<pkg>/` (**including `retroarch.cfg`**):

1. **A downgrade.** libretro's sideload flavors set
   `versionCode = System.currentTimeMillis() / 1000` — the build's epoch seconds
   ([build.gradle](https://github.com/libretro/RetroArch/blob/master/pkg/android/phoenix/build.gradle)).
   A stable APK built in November 2025 therefore has a *lower* versionCode than any 2026
   nightly. If Obtainium's source moved from the nightly directory to the stable directory
   (or he changed it), Android refuses the install with `INSTALL_FAILED_VERSION_DOWNGRADE` and
   Obtainium offers to uninstall first. Tapping through that wipes everything.
2. **A variant or signature switch** — `com.retroarch` ↔ `com.retroarch.aarch64`, or a Play
   build replaced by the libretro-signed build. Different signature ⇒ no in-place update ⇒
   uninstall required. In the variant case the *old* app may still be installed and the
   frontends may still be pointed at it (§4.3).

Both are **inference**, marked uncertain — but `firstInstallTime` in Step 6 settles it in one
command. Supporting evidence for "Obtainium is not on the GitHub-releases source": the v1.22.2
release has no APK assets at all, only `retroarch-sourceonly-1.22.2.tar.xz`, so his Obtainium
entry must be an HTML/direct-link source pointed at `buildbot.libretro.com` or `retroarch.com` —
exactly the kind of config whose regex can start matching a different file.

Worth telling him: **whatever he does, check the Obtainium app entry** and pin it to one
directory — either `https://buildbot.libretro.com/stable/1.22.2/android/` or
`https://buildbot.libretro.com/nightly/android/` — and to one filename
(`RetroArch_aarch64.apk` for the Thor, which is arm64).

---

## 7. Version guidance and the trap waiting on his next update

**He is already on the last stable.** There is no newer one: v1.22.2 (2025-11-20) is still the
most recent tag; there has been **no stable release in 2026**
(<https://github.com/libretro/RetroArch/releases>). So "rollback" is not the move — he should
stay where he is and reinstall his cores.

Direct APK links (verified live today):

- `https://buildbot.libretro.com/stable/1.22.2/android/RetroArch_aarch64.apk` — arm64, `com.retroarch.aarch64` ← the Thor
- `https://buildbot.libretro.com/stable/1.22.2/android/RetroArch.apk` — `com.retroarch`
- `https://buildbot.libretro.com/stable/1.22.2/android/RetroArch_ra32.apk` — `com.retroarch.ra32`
- Nightlies: `https://buildbot.libretro.com/nightly/android/<YYYY-MM-DD>-RetroArch_aarch64.apk`

### If he ever moves to a 2026 nightly, read this first

The 2026 nightlies are a different animal from v1.22.2, and moving to one without preparation
will produce the storage failure that this report originally assumed:

- **Commit [53021d40b](https://github.com/libretro/RetroArch/commit/53021d40b), 2026-07-14,
  "android: update as per 2026 requirement for play store (#19205)"** raised the sideload
  flavors from **`targetSdkVersion 28` to `36`**, deleted `READ_EXTERNAL_STORAGE` /
  `WRITE_EXTERNAL_STORAGE` from the manifest and added `MANAGE_EXTERNAL_STORAGE`. That ends the
  legacy-storage exemption v1.22.2 relies on. After such an update he **must** grant
  Settings → Apps → **Special app access → All files access → RetroArch**, and Android will not
  carry anything over, because there was nothing to carry over.
- **The permission prompt is currently broken in nightlies:**
  **#19449 "Android nightly: permission issue after install"**, 2026-08-22,
  <https://github.com/libretro/RetroArch/issues/19449> — *"Open retroarch and request permission
  popup. Okay, but i can't get permission storage or etc. **Stable 1.22.2 version is no
  problem.**"* A maintainer-side reply: *"you should have been taken to the permissions screen…
  This was working before."*
- **A frontend launch never prompts at all.** In master's
  `RetroActivityCommon.resolveStartupPermissions()`:
  ```java
  boolean viaLauncher = getIntent() != null && getIntent().hasExtra("CONFIGFILE");
  if (viaLauncher || isPlayStoreBuild() || hasStoragePermission()) {
      startupPermissionResolved(hasStoragePermission());
      return;
  }
  ```
  That test was written to recognise RetroArch's own Java launcher, which was **deleted** on
  2026-08-20 ([8075cbe77](https://github.com/libretro/RetroArch/commit/8075cbe77)). Today the
  only senders of `CONFIGFILE` are third-party frontends — Argosy passes it (§4.1) — so every
  frontend launch silently produces a RetroArch with no storage access and no way to be asked
  for it. See §8.
- **A genuinely broken nightly window, 2026-08-31 → 2026-09-02:**
  **#19481 "Retroarch aarch64 nightly crashing on Odin 3 at launch"**,
  <https://github.com/libretro/RetroArch/issues/19481> — crash at startup with an existing
  `retroarch.cfg`, also reported on Ayaneo Pocket Mini and RG556, *"launching RetroArch directly
  or launching via frontend"*. Cause was the new threaded audio pipeline; fixed and confirmed on
  the **2026-09-03** nightly.
- The Thor specifically already has an open nightly complaint:
  **#19240 "Latest nightly causing crashes on AYN Thor"**, 2026-07-21,
  <https://github.com/libretro/RetroArch/issues/19240>.
- **Uncertain, but worth knowing:** the Android core buildbot builds against current master. A
  November-2025 frontend loading September-2026 cores is normally fine (the libretro API is
  stable), but master moved to NDK 29 and 16 KB page alignment for some variants in July 2026
  ([#19230](https://github.com/libretro/RetroArch/pull/19230)). If a freshly downloaded core
  fails to load on v1.22.2 with a `dlopen` error, that mismatch is the first thing to suspect.

### Backup paths, before any uninstall

```bash
adb pull /sdcard/Android/data/$PKG/files  ./ra-appdata-backup   # retroarch.cfg lives here; DELETED on uninstall
adb pull /sdcard/RetroArch                ./ra-backup           # saves, states, system, playlists, thumbnails
adb pull /sdcard/Android/media/$PKG       ./ra-media-backup      # only exists on newer/scoped builds
```

`/storage/emulated/0/RetroArch/` is outside app scope and normally survives an uninstall.
`/sdcard/Android/data/<pkg>/` does **not** — that is where `retroarch.cfg` lives, and that is
why his config went with the cores. `/data/data/<pkg>/cores/` is unreachable without root and is
always lost; cores are re-downloadable, which is the good news here.

---

## 8. Is this fixable by us?

For him, right now, it is local: reinstall the cores and the "No Items" and the frontend crashes
both go away. Nothing is broken in the RetroArch he is running.

But there are two upstream reports worth making, and one of them is ours to file.

**Already filed, worth a +1 with his details:** #19357
<https://github.com/libretro/RetroArch/issues/19357> — an unresolvable `LIBRETRO` path from an
external launch is logged at `RARCH_WARN` and then *silently dropped*, so a missing core, a
typo'd core name and a wiped core directory are indistinguishable to both the user and the
calling frontend, and the failure presents as a hang or crash rather than an error. His case is a
textbook instance: an app update emptied the cores directory and every frontend launch became an
unexplained crash. Worth adding that this is not master-only — the identical code is in stable
v1.22.2 (`retroarch.c` ~line 6910).

**Not filed anywhere I could find, and cleanly evidenced:**
`RetroActivityCommon.resolveStartupPermissions()` treats any intent carrying a `CONFIGFILE`
extra as "launched by the Java launcher" and skips the storage-permission request entirely — but
the Java launcher was deleted on 2026-08-20 (8075cbe77), so the only remaining senders of
`CONFIGFILE` are third-party frontends. Every frontend launch on a current nightly therefore
starts a RetroArch with no filesystem access and no prompt. It is a two-line fix (drop the
`viaLauncher` shortcut, or gate it on the caller's identity). It does not affect him today, but
it will the moment anyone on a nightly launches from Argosy, Cocoon, Daijishō or ES-DE.

A third, softer suggestion for upstream, arising directly from this diagnosis: when the core
list is empty, `all_ext` becoming `"7z|zip|"` turns "you have no cores" into "your ROM folders
are empty", which is a maximally confusing way to say it. Either disabling the extension filter
when no cores are installed, or surfacing a "No cores installed" hint in the content browser,
would have saved this entire investigation.

---

## 9. What I ruled out, and what stays uncertain

**Ruled out**

- Scoped storage / `MANAGE_EXTERNAL_STORAGE`. v1.22.2 targets SDK 28 and keeps Android's legacy
  external storage; he has the permission; and the symptom (tree browses, files hidden, "No
  Items") is fully explained by the extension filter without invoking permissions at all.
- A Play Store build. Confirmed Obtainium / libretro build. (Play builds *would* be a dead end:
  `MANAGE_EXTERNAL_STORAGE` is stripped from them entirely —
  [88ee3cc03](https://github.com/libretro/RetroArch/commit/88ee3cc03) — and their storage moved
  to `Android/media` — PR #19261.)
- A bad nightly. He is on a stable tag, not a nightly.
- A `content://` URI RetroArch cannot resolve. Argosy passes the ROM as an absolute **path**;
  the content URI is only an extra grant.

**Uncertain**

- The identity of "Argosty" as `rommapp/argosy-launcher` (high confidence, not certain).
- Cocoon's exact intent shape — the app is not open source (§4.4).
- Exactly which of the two uninstall mechanisms in §6 fired. `firstInstallTime` answers it.
- The 16 KB-page / NDK-29 core-compatibility caveat in §7 — plausible, untested.
