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
