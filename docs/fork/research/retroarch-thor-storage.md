# RetroArch on the AYN Thor: the cores vanished across an update

Research note, 2026-09-09. Diagnosis only — nothing here was run against the Thor.
Every claim about RetroArch's history and code is cited. Anything unverified is marked
**uncertain**.

---

## 0. Status: diagnosed and fixed, with one question left

**CONFIRMED ON DEVICE.** Main Menu → Load Core showed **"No Items"** — zero cores installed.
Running Online Updater → Core Downloader restored it. Both symptoms had one cause.

- **"No Items" in every ROM directory** — with no cores installed, RetroArch's list of
  extensions it can open collapses to exactly `7z|zip|`, "Filter Unknown Extensions" is on by
  default, and the browser lists directories unconditionally while filtering files. So the tree
  browsed fine and every ROM folder rendered empty. It looked like a permissions problem and
  was not one. Mechanism proven in v1.22.2 source in §3.
- **Argosy / Cocoon crashing on launch** — same cause, confirmed in §4: the frontends hand over
  `LIBRETRO=/data/data/<pkg>/cores/<core>_libretro_android.so`, that file did not exist,
  RetroArch warned below the default log level, *discarded the argument*, and then died in init
  with no core path set.

**The remaining question — why did the cores disappear? — is answered in §2.** Short version:

> They were never in the APK he now has. Cores ship "stock" **only** with the Google Play
> builds, where they arrive as Dynamic Feature Modules and are symlinked into the cores
> directory by code that returns early on any non-Play build. The libretro buildbot / GitHub
> APK that Obtainium installed contains **one** native library — the frontend itself — and zero
> cores. And because swapping a Play build for the buildbot build requires an **uninstall**,
> the swap also took `/data/data/<pkg>/cores/` and `retroarch.cfg` with it.

§6 is what he should do so it does not happen again. §5 Step 5 has one thing he must still
check: **which GBA core**, because his RomM saves are filed under `mgba`.

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

## 2. Why the cores vanished — the answer

His words: *"i didn't have to set up these cores before, they came stock standard with the
install of retroarch."* That sentence is the whole diagnosis, because it is only true of one
kind of RetroArch build.

### 2.1 The buildbot / GitHub APK ships no cores. At all.

I pulled the tail of `https://buildbot.libretro.com/stable/1.22.2/android/RetroArch_aarch64.apk`
(184 MB) and read its zip central directory. The complete `lib/` contents:

```
lib/arm64-v8a/libretroarch-activity.so
```

One library — the frontend itself. The 184 MB is assets, not cores:

| prefix | entries |
|---|---|
| `assets/shaders` | 4221 |
| `assets/overlays` | 1906 |
| `assets/assets` | 1783 |
| `assets/info` | 292 |
| `assets/autoconfig` | 212 |
| `assets/database` | 146 |
| `assets/filters` | 80 |

Note `assets/info` — those 292 files are core **`.info` metadata** (`mgba_libretro.info`,
`snes9x_libretro.info`, … and, amusingly, `azahar_libretro.info`). They describe cores that
*could* be installed. They are not cores, and they are exactly why the Core Downloader has a
list to show you on a fresh install. Mistaking them for cores is easy and they are not.

### 2.2 Cores come "stock" only on Google Play, as Dynamic Feature Modules

`RetroActivityCommon.java` at v1.22.2 — this is the mechanism, and the gate is one line:

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    cleanupSymlinks();
    updateSymlinks();
    PlayCoreManager.getInstance().onCreate(this);
    super.onCreate(savedInstanceState);
}

private String getCorePath() {
    String path = getApplicationInfo().dataDir + "/cores/";
    new File(path).mkdirs();
    return path;
}

/** Cleans up existing symlinks before new ones are created. */
private void cleanupSymlinks() {
    File[] files = new File(getCorePath()).listFiles();
    for (int i = 0; i < files.length; i++) {
        try { Os.readlink(files[i].getAbsolutePath()); files[i].delete(); }
        catch (Exception e) { /* File is not a symlink, so don't delete. */ }
    }
}

/** Triggers a symlink update in the known places that Dynamic Feature Modules
 *  are installed to. */
public void updateSymlinks() {
    if (!isPlayStoreBuild()) return;                    // <-- the whole story
    traverseFilesystem(getFilesDir());
    traverseFilesystem(new File(getApplicationInfo().nativeLibraryDir));
}
```

<https://github.com/libretro/RetroArch/blob/v1.22.2/pkg/android/phoenix-common/src/com/retroarch/browser/retroactivity/RetroActivityCommon.java>

On a **Play** build, cores are delivered by Play as Dynamic Feature Modules into the app's
native-library tree, and every launch symlinks them into `/data/data/<pkg>/cores/`. That is
"stock standard": you install RetroArch Plus and the cores are simply there. Upstream keeps
scaling this — PR #19320, "android: for play plus version increase min SDK for **100 feature
modules**", 2026-08-01.

On a **sideload** build `updateSymlinks()` returns on its first line. `/data/data/<pkg>/cores/`
is populated by exactly one thing: **Online Updater → Core Downloader**.

Two consequences worth naming:

- `cleanupSymlinks()` runs unconditionally on every start and deletes every *symlink* in the
  cores directory. It leaves regular files alone — so Online-Updater cores are safe, and it is
  not what removed his.
- On a Play build the cores are **symlinks, not files**. There is nothing there to back up.

### 2.3 Ranked

1. **He was on a Play Store build (or a vendor preload) and Obtainium replaced it with the
   libretro buildbot APK.** The only hypothesis that explains "they came stock standard".
   The applicationIds collide exactly — Play "RetroArch" and buildbot `RetroArch.apk` are both
   `com.retroarch`; Play "RetroArch Plus" and buildbot `RetroArch_aarch64.apk` are both
   `com.retroarch.aarch64` (v1.22.2 `build.gradle`: `playStorePlus` and `aarch64` both carry
   `applicationIdSuffix '.aarch64'`) — but the signing keys differ, so Android refuses the
   in-place update and Obtainium offers to uninstall first. Accepting that deletes
   `/data/data/<pkg>/` (the cores, or on a Play build the symlinks *and* the delivered modules)
   and `/sdcard/Android/data/<pkg>/` (**including `retroarch.cfg`**). The new app then has no
   cores, by design. **This is the answer.**
   *AYN handhelds commonly ship with emulators preloaded; whether the Thor specifically ships
   RetroArch preinstalled I could not verify — if it does, that is the same story with the
   vendor's build in place of Play's.* **Uncertain** which of the two.
2. **A nightly → stable downgrade through Obtainium.** Same uninstall, same wipe, and it is a
   documented, reported workflow: **#19325 "[Android] Allow APK downgrades"**, 2026-08-02,
   <https://github.com/libretro/RetroArch/issues/19325>, whose reproduction steps literally
   include *"Scenario C (Nightly to Stable Via Obtainium)"* and whose failure is
   `App not installed as package appears to be invalid`. The reply: *"it is not possible to
   downgrade an APK directly over an existing installation without uninstalling the app first"*.
   The sideload flavors set `versionCode = System.currentTimeMillis() / 1000`, so any 2026
   nightly outranks the November-2025 stable and going back is always a downgrade. This
   explains the wipe but not "came stock", so it is second — and it may well have happened
   *as well*.
3. **A variant / ABI switch** — `com.retroarch` ↔ `com.retroarch.aarch64` ↔ `com.retroarch.ra32`
   are three *different* applicationIds (v1.22.2 `build.gradle`: `normal` has no suffix and no
   `abiFilters`; `aarch64` filters `arm64-v8a, x86_64`; `ra32` filters `armeabi-v7a, x86`). If
   Obtainium's file-matching started picking a different filename, the result is a **brand-new
   app** with an empty cores directory, while the old one may still be installed with its cores
   intact. That would also strand the frontends on a package that is no longer the one he uses.
   One command settles it: `adb shell pm list packages | grep -i retroarch` — more than one line
   means this happened. Does not explain "came stock" either.
4. **Also possible in the mix:** F-Droid. F-Droid ships a stripped RetroArch (~50 MB — see
   #18756, which refers to "glui_minimal_assets.zip (used by the F-Droid release)") under
   F-Droid's own signing key, so F-Droid ↔ buildbot is another signature swap requiring an
   uninstall. Obtainium users mix these freely; **#19228 "Offer clean APKs, just like F-Droid,
   on buildbot.libretro"**, 2026-07-18, <https://github.com/libretro/RetroArch/issues/19228>, is
   an Obtainium user asking for exactly that, and confirms the buildbot APKs are the big ones.

**Ruled out**

- *"1.22.x moved the core directory."* It did not. `platform_unix.c` at v1.22.2 sets
  `DEFAULT_DIR_CORE` to `<app_dir>/cores` — `/data/data/<pkg>/cores` — the same as it has been
  for years.
- *"An APK upgrade clears the extracted cores."* It does not. An in-place update leaves
  `/data/data/<pkg>/` alone, and `cleanupSymlinks()` only removes symlinks (§2.2). Only an
  **uninstall** wipes it — which is precisely why the mechanism in cause 1 and 2 matters.
- Scoped storage / `MANAGE_EXTERNAL_STORAGE`. See §9.

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

**Confirmed: the same single cause.** The frontends hand RetroArch an absolute path to a core
file inside RetroArch's private data directory. With the cores directory empty, that path was
dangling on every launch, and v1.22.2 turns a dangling `--libretro` into a silent init failure
(§4.2). No second explanation is needed for the crashes, and none of the storage or intent
theories in §9 survive the fact that Core Downloader fixed it.

The one thing still worth checking is §4.3 — whether the frontends are also pointed at a package
that no longer exists — because that failure looks identical from the outside and would still be
there after the cores came back. `adb shell pm list packages | grep -i retroarch` answers it.

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

### Steps 1-3 — done

Load Core showed "No Items"; Core Downloader fixed it. For the record, the two checks that
settled it in ten seconds were **Main Menu → Load Core** (empty list ⇒ no cores) and
**Settings → File Browser → Filter Unknown Extensions → OFF** (ROMs reappear instantly ⇒ it was
the filter, not permissions). Turn that filter back **on** now that cores are installed — it is
what keeps the browser usable.

Worth also running once: **Online Updater → Update Core Info Files**. The `.info` files carry
the per-core extension lists that feed the filter, and the ones baked into the APK
(`assets/info/`, §2.1) are from November 2025.

### Step 3b — install the *right* GBA core: mGBA

His RomM saves are filed under an **`mgba`** directory, so mGBA is the core he was on. This
matters beyond preference: RetroArch names save and state directories after the core, and
Argosy resolves its save paths the same way (`LibretroSavePathResolver.kt`,
`LibretroStatePathResolver.kt` in `rommapp/argosy-launcher`). If he installs a *different* GBA
core — vba_next, gpsp, mgba's own variants — saves land under a different directory name and
RomM's sync silently stops matching his existing saves.

So: **Core Downloader → mGBA**, and then make sure both frontends are set to mGBA for GBA.
Cocoon's documented default for GBA is `vba_next`, so it very likely needs changing; check
Argosy's per-platform core setting too. Argosy builds the core filename from its own core id —
`"$dataDir/cores/${coreName}_libretro_android.so"` — so whatever it is set to must be a core he
has actually downloaded, or §4 happens again.

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

Was it reinstalled, or updated in place? This is the decisive question for §2.3:

```bash
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode|targetSdk|installerPackageName|firstInstallTime|lastUpdateTime"
```

- `firstInstallTime` **also** in early September ⇒ it was **uninstalled and reinstalled**, and
  `/data/data/$PKG` (the cores) and `/sdcard/Android/data/$PKG/files/retroarch.cfg` were
  destroyed. That is the whole story.
- `firstInstallTime` months older, only `lastUpdateTime` recent ⇒ it was a true in-place update
  and the cores should have survived; look harder at Step 4 and at `core_directory` in the cfg.
- `installerPackageName` names the source that last wrote the app. `com.android.vending` means
  a Play build is what is installed *now*; an Obtainium package id means the sideload build won.
  Either way, compare it against what he expects — §2.3 cause 1 is exactly a change here.
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

## 6. How he stops this happening again

The root problem is that he has two update channels fighting over one app: whatever originally
put a cores-included RetroArch on the device, and Obtainium. Pick one.

**Option A — he wants cores to keep coming stock.** Then the app must come from **Google Play**
(RetroArch, or RetroArch Plus for the 64-bit build), because Dynamic Feature Module delivery is
the only mechanism that installs cores for you (§2.2). In that case **remove RetroArch from
Obtainium entirely**, or set that entry to track-only so it never offers to install. Obtainium
cannot update a Play-signed app in place; every "update" it offers is an uninstall in disguise.

**Option B — he wants Obtainium and the libretro buildbot.** Perfectly reasonable, and it is
what he has now. The deal is that **cores are his responsibility**: Online Updater → Core
Downloader once, after which they are ordinary files in `/data/data/<pkg>/cores/` that survive
in-place updates. To keep it stable:

- **Pin the Obtainium entry to one directory and one filename.** For the Thor (arm64):
  `https://buildbot.libretro.com/stable/1.22.2/android/RetroArch_aarch64.apk`
  (applicationId `com.retroarch.aarch64`), *or* the nightly directory
  `https://buildbot.libretro.com/nightly/android/` with the `*-RetroArch_aarch64.apk` pattern.
  **Never let one entry span both.** Nightly → stable is always a downgrade (§2.3 cause 2,
  issue #19325) and Obtainium will offer to uninstall, which is what costs him the cores.
- Note that libretro's GitHub *releases* carry **no APK assets** — v1.22.2 ships only
  `retroarch-sourceonly-1.22.2.tar.xz`. So the Obtainium entry cannot be a GitHub-release
  source; it is an HTML/direct-link source, and those are exactly the kind whose filename regex
  can start matching a different variant. Worth opening the entry and reading what it is set to.
- Never accept an Obtainium prompt that says the app must be uninstalled first. That prompt
  *is* the bug. Back up, then decide deliberately.

**What is worth backing up, and what cannot be.**

```bash
PKG=com.retroarch.aarch64     # or whatever `pm list packages | grep -i retroarch` reports
adb pull /sdcard/Android/data/$PKG/files  ./ra-appdata-backup   # retroarch.cfg; DELETED on uninstall
adb pull /sdcard/RetroArch                ./ra-backup           # saves, states, system, playlists
```

`/storage/emulated/0/RetroArch/` sits outside app scope and normally survives an uninstall.
`/sdcard/Android/data/<pkg>/` does **not** — that is where `retroarch.cfg` lives, and that is
why his configuration went with the cores. `/data/data/<pkg>/cores/` is unreachable without
root and is always lost; on a Play build there is nothing there to save anyway, because they
are symlinks (§2.2). Re-downloading cores is a two-minute job — the config and saves are the
part actually worth protecting.

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

**Ruled out** (all of these were live hypotheses before Load Core came back empty)

- Scoped storage / `MANAGE_EXTERNAL_STORAGE`. v1.22.2 targets SDK 28 and keeps Android's legacy
  external storage; he has the permission; and the symptom (tree browses, files hidden, "No
  Items") is fully explained by the extension filter without invoking permissions at all.
- A Play Store build being what is installed *now*. It is the Obtainium / libretro build —
  which is the whole reason the cores are gone (§2). Note for later: had he ended up on a
  *current* Play build instead, it would have been a different dead end, because
  `MANAGE_EXTERNAL_STORAGE` is stripped from Play builds entirely
  ([88ee3cc03](https://github.com/libretro/RetroArch/commit/88ee3cc03)) and their storage moved
  to `Android/media` (PR #19261) — so a Play build cannot browse `/storage/emulated/0/roms` at
  all. Cores stock, ROMs unreachable. That trade-off is worth knowing before choosing Option A
  in §6.
- A bad nightly. He is on a stable tag, not a nightly.
- A `content://` URI RetroArch cannot resolve. Argosy passes the ROM as an absolute **path**;
  the content URI is only an extra grant.

**Uncertain**

- **Which cores-included build he was on before** — Google Play (RetroArch or RetroArch Plus)
  or an AYN vendor preload. Both produce the identical outcome via the identical mechanism
  (§2.3 cause 1); `firstInstallTime` and `installerPackageName` in §5 Step 6 would have
  distinguished them, and may still if the old package is also still installed.
- Whether an Obtainium variant/filename change (§2.3 cause 3) happened *as well*.
  `pm list packages` answers it.
- Whether the AYN Thor ships RetroArch preinstalled from the factory.
- The identity of "Argosty" as `rommapp/argosy-launcher` (high confidence, not certain).
- Cocoon's exact intent shape — the app is not open source (§4.4).
- The 16 KB-page / NDK-29 core-compatibility caveat in §7 — plausible, untested.
