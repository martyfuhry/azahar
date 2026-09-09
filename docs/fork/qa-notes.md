# Field QA notes

Observations from Marty playing on the AYN Thor, with corrections to agent diagnoses.

## thor-v1-rc1 (2026-09-08 evening / 2026-09-09 morning)

**Controller auto-mapping worked.** The Thor's built-in pad was seeded correctly on first
launch with no manual mapping, and play was reported as working well with performance fine.

**This corrects the stuck-gamepad diagnosis.** `fix/resume-input-focus` concluded from code
that the pad had no bindings, so an unbound `KEYCODE_BUTTON_B` fell through Android's
key-character-map fallback to `BACK`, opening the drawer. Since the pad *was* mapped, that
chain cannot have been the trigger on this device. The likelier cause of the reported
symptom (game running, bottom-screen touch fine, every pad button driving the drawer/menus,
immediately after an autosave resume) is `DrawerLayout` restoring itself **open** from saved
instance state after Android killed the backgrounded process, which commit `4113f2d86`
(`onViewStateRestored` closes a restored-open drawer) addresses directly.

The unbound-key swallowing (`386d4cbd6`) remains correct and worth keeping: it protects any
device or moment where a binding genuinely is missing. But it is not the fix that mattered
here, and a future session should not treat "the pad was unmapped" as established.

**Still unconfirmed:** neither fix has been verified on device. The `uinput` repro harness in
`~/Development/azahar-builds/resume-input-repro/` exercises the unbound-key path; the
restored-open-drawer path needs a real process kill while backgrounded, then a relaunch.

## Savestates DO survive between our release candidates (2026-09-09)

Marty loaded an rc1-created autosave in rc2, in Animal Crossing, with no trouble. The release
notes for both rc1 and rc2 say savestates from another build are rejected by design and tell
him to save in-game before upgrading. **That is wrong for our own builds**, and the reason is
a change we made ourselves.

`ValidateSaveState` (`src/core/savestate.cpp:78-91`) has three outcomes, not two: revision
matches means `OK`; revision differs but `Common::g_build_version` matches means
`RevisionMismatch`, which still loads; only a differing build version is `BuildMismatch`, and
the autosave resume path rejects only that (`:196`). rc1 and rc2 share an upstream base
version and differ only by git revision, so they are compatible.

Before the autosave work, `MakeHeader` left `build_version` blank in **file** headers, so
every cross-revision file state landed in `BuildMismatch` and was refused. `MakeHeader` now
writes it (`:227-229`), which is what makes this work; the commit body called it intended.

**So:** upgrading between our release candidates preserves the autosave. It would only break
on a rebase onto a new upstream release, where `g_build_version` itself changes. Correct this
in the next release's notes and drop the save-in-game-first warning to a footnote about
upstream version bumps.
