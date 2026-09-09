# Comment, not a new issue: top-panel touch injects touchscreen input (#2020 / #1965)

**What I found upstream.** `gh issue list -R azahar-emu/azahar --search "single screen layout touch top"`:

- **#2020** — "(AYN Thor-Android) Internal screen (top) touch inputs are being passed to external screen (bottom)". **Closed** on 2026-04-11 as a duplicate of #1965, with DavidRGriswold's "yes this is a known issue, it will be fixed in the next release or two". It has not been. Also note the second comment there: it reproduces on the **desktop AppImage** in Single Screen layout too, so this is not Thor-specific.
- **#1965** — "Option to disable the touch screen on the top display (android)". **Open**, labelled `enhancement`.

So the live thread is **#1965**, and it is filed as a feature request when it is actually a bug with a specific cause. That is the useful thing I can add. **Post the comment on #1965.** A one-line pointer on #2020 is optional and probably unnecessary.

---

## Draft comment for #1965

> This is filed as an enhancement but I think it is a straightforward bug, and I can point at the line. I hit it on an AYN Thor and then reproduced the mechanism on a Galaxy Z Fold5; #2020 was closed as a duplicate of this and there is a report in it of the same thing on the desktop AppImage in Single Screen layout, so it is not dual-screen-specific either.
>
> `EmuWindow::IsWithinTouchscreen` (`src/core/frontend/emu_window.cpp:79`) decides whether a touch landed on the DS touchscreen purely from the `bottom_screen` rectangle of that window's layout. The only exception is a desktop-only guard for `SeparateWindows`:
>
> ```cpp
> #ifndef ANDROID
>     // If separate windows and the touch is in the primary (top) screen, ignore it.
>     if (Settings::values.layout_option.GetValue() == Settings::LayoutOption::SeparateWindows && ...
> #endif
> ```
>
> The problem is that a single-screen layout still publishes a full-window `bottom_screen` rectangle even for the screen it is not showing. `SingleFrameLayout` (`src/core/frontend/framebuffer_layout.cpp:86`) says so in its own comment — "The drawing code needs at least somewhat valid values for both screens so just calculate them both even if the other isn't showing" — and then assigns both rectangles unconditionally. So on a window that is displaying only the top screen, `layout.bottom_screen` covers the whole window, and `IsWithinTouchscreen` answers true for every touch on it. On the Thor the default arrangement is exactly that: the activity window shows the top screen and the bottom screen lives in a `Presentation` on the second panel, so touching the upper panel injects touch into the game.
>
> Every layout already computes the right answer to the question `IsWithinTouchscreen` is trying to ask — `bottom_screen_enabled`. Asking that instead makes the `#ifndef ANDROID` guard unnecessary: for the desktop `SeparateWindows` case the two are exactly equivalent, since `SeparateWindowsLayout` hands each window a `SingleFrameLayout` whose `swapped` flag is the old condition, folding `swap_screen` the same way.
>
> Two details that cost me time and that I would not want someone else to rediscover:
>
> 1. `TouchMoved` (`emu_window.cpp:201`) needs the same guard, not just `TouchPressed`. Touch state is global across windows, so a press on the window that owns the bottom screen leaves every other window's `TouchMoved` live, and a drag over the top panel will otherwise clamp the touch point the bottom panel set.
> 2. `SingleFrameLayout` should also stop publishing a rectangle for the screen it does not show. It still needs to *size* both — the maths is shared — but a hidden screen must not claim window pixels. The renderers already skip a screen whose `{top,bottom}_screen_enabled` is false, so they never read the emptied rectangle.
>
> I have this working in my own fork; the commit is [`a4e13b451`](https://github.com/martyfuhry/azahar/commit/a4e13b451) if anyone wants to look, and it is covered by tests. I am not sending a patch — mine is bigger than the minimum because it does `TouchMoved` and the layout as well, and your AI policy is clear about where the line is. The one-line version of the fix, if you want it small, is `IsWithinTouchscreen` consulting `layout.bottom_screen_enabled`.

---

## Marty must verify before posting

- [ ] Reproduce on the **Thor** with an **official Azahar build**: default layout, launch ACNL or SMT4, touch the top panel, confirm it registers in game and that the bottom panel keeps working.
- [ ] Reproduce the **desktop** half too, since #2020's second comment claims it: Single Screen layout in the Qt build, click the window, see whether it registers as touch. If it does, say so in the comment — a desktop repro raises this well above "Thor thing".
- [ ] Re-read `emu_window.cpp` and `framebuffer_layout.cpp` on current `master` on the day I post and re-confirm the line numbers.
- [ ] Check whether #1965 should be relabelled from `enhancement` to `bug` — worth suggesting in the comment, politely, once rather than twice.

---

*Disclosure line to include at the end of the posted comment:*

> Disclosure: I used an AI assistant to help me trace this through the layout code. I reproduced the behaviour on my own device and verified the analysis myself.
