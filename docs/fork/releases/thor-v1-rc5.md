# thor-v1-rc5

Release candidate 5 of the Thor fork, built for the AYN Thor (Android, Snapdragon 8 Gen 2).

## What this fixes

**Switching 3DS games from the Argosy launcher, while Azahar is already running, left a dead
session.** The running game was stopped and the new one never loaded. Cold starts always
worked, which is why it went unnoticed until Majora's Mask was launched over a running
Pokémon X.

Everything in `thor-v1-rc4`, plus the two changes below.

### The artifact

| | |
|---|---|
| File | `azahar-thor-v1-rc5.apk` |
| Built from | `a5c799e9c` (clean tree) |
| versionName | `thor-v1-rc5-thor` |
| versionCode | `33739579` |
| sha256 | `102232d30a5f51105abedc8f6555beaca871533cdec9686fba2643d25c81cc18` |
| Size | 50 311 851 bytes |
| ABIs | arm64-v8a, x86_64 |

`a5c799e9c` is this commit before the artifact rows were filled in; the tag is on the amended
commit, as with rc1 through rc4. Unstripped symbols are at
`~/Development/azahar-builds/azahar-symbols-rc5/`, build id
`3254dce386cdf5dddc59cb3a531d5afa6bf0bc35`, matching the shipped library.

## What changed

### (a) A launcher title switch loaded nothing

The cause is upstream and, on this version of AndroidX Navigation, has never worked.
`onNewIntent` ended with:

    navHostFragment.navController.setGraph(inflate(R.navigation.emulation_navigation), extras)

`NavController.setGraph(NavGraph, Bundle)` compares the graph it is handed against the current
one with `Intrinsics.areEqual`, and `NavGraph`/`NavDestination` override `equals`
**structurally** — route, arguments, deep links, actions and every child node, with no identity
check. A second inflation of the same navigation resource is therefore *equal* to the first, so
`setGraph` takes its equality branch: it replaces the graph's nodes, retargets the back-stack
entries, and returns. `onGraphCreated` is never called, the start destination is never
re-navigated to, no second fragment is created, and the arguments are discarded outright. The
core had already been stopped four lines earlier, so the session was simply left dead.

Verified from `javap -c` on `navigation-runtime` and `navigation-common` 2.8.0, independently
by the implementer and the reviewer, rather than from documentation.

The switch now hands off to the live `EmulationFragment`, sharing the cold path's intent
resolution. The ROM file descriptor is owned explicitly across the handover, which matters
because the core **dups** the descriptor at `IOFile::Open` — the front-end copy has to outlive
every reopen, and be closed exactly once. Descriptor ownership was traced at all six call sites
during review rather than left to the unit tests.

Two properties that fall out of doing it this way. The incoming game is opened and validated
*before* anything is torn down, so a revoked permission grant or an unbootable file leaves the
running game running with a message, instead of a black screen. And the outgoing title's
autosave goes through `EmulationState.stop()` — the same request-and-wait the exit path uses —
which also removes a second, redundant state write the old code made whenever `onPause` had
already saved on the way to the launcher.

### (b) A relaunch is identified by its path or title id, not its descriptor

The other half of the same problem. Deciding whether an incoming intent names the title already
running compared the file descriptor, which is fresh on every launch, so a launcher re-sending
the *same* game was never recognised as a resume. It now compares the `SelectedGame` path — the
stable half of what a launcher sends — and falls back to reading the title id through a
descriptor opened on the content URI when the path cannot be resolved, which is the case for a
FileProvider URI. Anything unidentifiable still falls through to a reload, so the failure
direction is unchanged.

Together these cover both directions: (b) decides resume-in-place versus switch, and (a) makes
the switch actually load.

## Known, and deliberately not in this build

- **A launcher relaunch can still leave the process without its foreground service.** Real,
  observed in the field, and unfixed: the branch that attempted it failed review with two
  confirmed critical defects, and the device logs then showed that both Android activity
  orderings occur, so any replacement must be correct under each.
- **Play time is not tracked for a launcher switch**, because launcher intents carry no parcelled
  game. Unchanged from a launcher cold start; noted so it is not mistaken for a regression.

## Standard of evidence

**No runtime behaviour in this build has been verified on hardware.** The bug it fixes was
reproduced on the owner's device in both directions — a failing switch and a working cold start
— but the fix itself has only been reviewed and built. Host verification: the Catch2 suite (87
cases, 1624 assertions; the C++ is untouched), `compileThorReleaseKotlin`, `ktlintCheck`, and
the JVM unit tests including seven new ones covering descriptor handover.

Save in-game before installing.
