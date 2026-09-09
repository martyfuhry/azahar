# Comment, not a new issue: a re-sent launch intent restarts the running game (#2329)

**What I found upstream.** #2329 — "Android: black screen when relaunching an already-running title from a pinned shortcut", **open**, no label, no comments, filed 2026-07-20 by an Ayn Thor owner. The body already names `onNewIntent` and `stopEmulation()`, so the diagnosis is correct and I should not repeat it back at them. What I can add is that this is not just pinned shortcuts, it is a session-destroying bug that fires on **every lid open** on the Thor, and a note on what the fix has to be careful about.

**Post on #2329.**

---

## Draft comment for #2329

> Confirming this and adding what I think is the more serious version of it. Your analysis of `onNewIntent` is right — `EmulationActivity.kt:178-200` on current `master` calls `NativeLibrary.stopEmulation()` unconditionally and then rebuilds the nav graph from the intent's extras, whichever title the intent names, including the one already running.
>
> The part I would add: on an AYN Thor this is not limited to pinned shortcuts. The Thor's launcher re-sends the launch intent on **every lid open**. `EmulationActivity` is `singleTop`, so that lands in `onNewIntent`, and the running game is torn down and rebooted from the title screen. You close the lid mid-session, open it a minute later, and you are back at the title with your progress since the last in-game save gone. That is the single most common way I lost a session on that device before I patched it, and I suspect it is behind a chunk of the "Azahar restarted my game while it was in the background" reports that get attributed to the OS killing the process. It is not always the OS; sometimes it is us.
>
> Worth stating plainly for whoever picks this up: this is the only lifecycle path in the Android frontend that intentionally throws away a running game without asking the user.
>
> Two things a fix has to handle, from doing it in my own fork:
>
> 1. The comparison should be cheap first and only expensive when it has to be. Same data URI, or same `SelectedGame` extra, or the same parcelled `Game` — those cover the shortcut and launcher cases without touching the filesystem. Only when all of those differ is it worth opening the incoming file to read its title id and comparing it against the core's.
> 2. A genuinely different title still has to go through the full stop-and-reload path, so the early-out has to be narrow.
>
> There is an existing precedent in the tree, incidentally: the foreground service's return-to-game intent was already special-cased. This is the same idea applied to any intent that names the title already running or booting.
>
> My fork's version is [`d261b47d2`](https://github.com/martyfuhry/azahar/commit/d261b47d2) if it is useful to look at. I am not sending it as a patch — it is 47 lines and your AI policy is clear.

---

## Marty must verify before posting

- [ ] Reproduce the **lid-open case on the Thor** with an **official Azahar build**: start a game, close the lid, open it, and confirm the game restarts from the title screen. This is the claim I am adding to the issue and it has to be mine.
- [ ] Confirm it is the intent and not a process kill, by checking that the pid is unchanged across the lid cycle (`adb shell pidof org.azahar_emu.azahar` before and after) and that logcat shows a fresh "Azahar starting" line on the same pid. If the pid changed, it was a kill and my claim is wrong.
- [ ] Reproduce the reporter's original pinned-shortcut case too, so I am confirming their report and not just talking past it.
- [ ] Re-check `EmulationActivity.kt` line numbers on current `master` on the day I post.

---

*Disclosure line to include at the end of the posted comment:*

> Disclosure: I used an AI assistant while investigating this. I reproduced the behaviour on my own hardware and confirmed the analysis myself.
