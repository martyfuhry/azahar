# Comment, not a new issue: an unmapped controller button opens the emulation drawer (#1101)

**What I found upstream.** #1101 — "B Button on physical controller opens menu", **closed** 2025-07-29 on the assumption it had been fixed by #1122, with 18 comments and people still reporting it as recently as 2026-07-25. Reports from Retroid Pocket 4/5, GameSir X5, 8BitDo Ultimate 2C, Switch Pro controller, and **two separate AYN Thor owners** (2025-11-03 and 2025-11-08). DavidRGriswold's comment of 2026-03-20 has the mechanism right and ends with "We could conceivably catch the B button press and block it opening the menu, but there's a chance that might break other things, which is why we have not done that… by all means add more comments here if it happens to you so we can watch for it."

That is an explicit invitation to comment, so a comment is the right shape. What I can add is the exact chain, why "just remap B" does not always work, and a way to catch the key that I do not think breaks the other things.

**Post on #1101.** It is closed; whether it gets reopened is up to a maintainer.

---

## Draft comment for #1101

> Still happening, and I think the mechanism is worth writing down precisely because "remap the B button" does not always fix it and the thread has three years of people discovering that.
>
> The chain: a gamepad button that no binding claims leaves `EmulationActivity.dispatchKeyEvent` (`EmulationActivity.kt:351`) unhandled. An unhandled key does not stop there — Android's key character map defines fallback actions for the gamepad range, so `KEYCODE_BUTTON_B` and `KEYCODE_BUTTON_Y` come back as `BACK`, and `KEYCODE_BUTTON_A` and `KEYCODE_BUTTON_X` come back as `DPAD_CENTER`. In this activity `BACK` opens the emulation drawer (`:367-372`). And once the drawer is open, the branch at `:357` sends every subsequent key to `super.dispatchKeyEvent`, which walks the menu. So an unmapped pad does not merely do nothing: the first press opens the menu and the pad is then stuck inside it, navigating it. That is why people describe it as the controller "not working" rather than as one button misbehaving.
>
> Why remapping does not always fix it, which is the part the thread keeps going round on: a fresh install has **no** controller bindings at all. The frontend translates host keycodes through SharedPreferences (`InputMapping_HostAxis_<keycode>`) and falls back to nothing when a key is absent. So a device with a built-in pad — the Thor, the Retroids — does nothing in game until the user either maps every button by hand or finds Controls → Auto-Map and presses A. Until then *every* button is unmapped, and the first one pressed is usually the one that opens the menu, which then eats the rest. Users who "fixed it by remapping" fixed it; users who report it still broken after remapping generally have one button they did not get to, or a pad that re-enumerates with different keycodes (the Thor's pad reports as vendor `0x2020` product `0x0111` in Thor mode and `0x0112` in Xbox mode, with the south button moving to `KEYCODE_BUTTON_A`).
>
> On the "might break other things" concern from March: what I did in my fork was narrow it as far as I could. While the game is on screen **and the drawer is closed**, a key that carries `SOURCE_GAMEPAD` or `SOURCE_JOYSTICK` and is either a gamepad button keycode or a d-pad keycode is answered `true` even when no binding wanted it, so Android never synthesises the fallback in the first place. Nothing behind the game wants those keys. What still works:
>
> - the back gesture and the menu button still open the drawer;
> - a hotkey bound to the drawer still opens it;
> - a real `BACK` key on a pad still opens it, because that one arrives **without** `FLAG_FALLBACK` — that flag is the thing that distinguishes a synthesised fallback from a genuine press, and it is what makes this safe;
> - the drawer stays fully navigable by pad while it is deliberately open, because the `isDrawerOpen()` branch is untouched.
>
> I also log the first swallowed key with its keycode and device name, because "the buttons do nothing" now has an answer in the log.
>
> Two other things I found on the Thor that are worth someone knowing, since they land in this same pile of reports:
>
> - `DrawerLayout` saves whether it was open and reopens itself from `onRestoreInstanceState`. When Android kills the app and later restores the task, a session that was backgrounded with the menu open boots the title from scratch **behind an open drawer** — over the loading screen, with the menu holding the focus a gamepad navigates with. That looks exactly like "my pad drives the menu instead of the game" and it is a different bug. Closing it in `onViewStateRestored` fixes that one.
> - `DrawerLayout` also stops offering the menu's rows to focus search once closed, but never takes focus *away* from the row that already had it, so a closed drawer can keep the focus that a d-pad or `DPAD_CENTER` acts on, and a press activates a menu item behind the game.
>
> Commits in my fork if anyone wants to read them: [`386d4cbd6`](https://github.com/martyfuhry/azahar/commit/386d4cbd6) (swallow the unbound key), [`4113f2d86`](https://github.com/martyfuhry/azahar/commit/4113f2d86) (close a restored-open drawer), [`6b2f7f89f`](https://github.com/martyfuhry/azahar/commit/6b2f7f89f) (clear the drawer's focus on close). I am not sending patches; the first one is 72 lines and your AI policy is clear about that.
>
> I also seed a default mapping the first time a physical pad is seen, which removes the "fresh install has no bindings" precondition entirely, but that is a new file and a design decision rather than a bug fix, so I am leaving it in my fork unless someone wants it.

---

## Marty must verify before posting

- [ ] Reproduce on the **Thor** with an **official Azahar build** and a **fresh install** (or with all bindings cleared): launch a game, press B on the built-in pad, confirm the drawer opens and that the pad then navigates the menu instead of the game.
- [ ] Confirm the `FLAG_FALLBACK` claim on the Thor specifically — dump the key events and check that the synthesised `BACK` carries it and a real pad `BACK` does not. That claim is load-bearing for the "this is safe" argument and I should not post it unverified on someone else's hardware.
- [ ] Confirm the restored-open-drawer case on the Thor: background with the menu open, force-stop, relaunch, see whether the drawer comes back open over the loading screen. I saw this in my own rc1 field testing; I need it once more on an unmodified build.
- [ ] Check the Thor's pad vendor/product IDs myself before quoting `0x2020` / `0x0111` / `0x0112` — `adb shell dumpsys input | grep -A5 -i odin`.
- [ ] Keep the comment short enough that it reads as help rather than a lecture. The thread is three years old and full of frustrated people; the tone matters more here than in the other reports.

---

*Disclosure line to include at the end of the posted comment:*

> Disclosure: I used an AI assistant while working this out. I reproduced the behaviour on my own device and verified the analysis myself.
