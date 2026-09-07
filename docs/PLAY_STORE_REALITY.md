# Play Store: what actually blocks Mobile Agent, and the ways out

*Written 2026-09-07 against `9813230`.*

> ## Decision: not shipping on Play Store
>
> **Mobile Agent keeps every feature and is distributed through GitHub
> Releases (and F-Droid, which has no targetSdk floor).**
>
> A Play release would require deleting the Ubuntu environment, `apt`, and the
> C/C++, Java and PHP stacks. That is not an acceptable trade — the Linux
> runtime *is* the product. `targetSdk` stays at 28.
>
> The rest of this document records why, so the question does not have to be
> re-litigated. Do not remove features to satisfy Play.

---

## Two independent blockers, both architectural

### 1. targetSdk vs. W^X

Google Play requires new apps and updates to target a recent API level (35+,
rising each year). But since Android 10, apps targeting API 29 or higher
**cannot execute files in their own data directory** — the "W^X" rule, enforced
by SELinux:

> *"Untrusted apps that target Android 10 cannot invoke execve() directly on
> files within the app's home directory."*
> — [developer.android.com](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)

Mobile Agent downloads an Ubuntu rootfs into that exact directory and executes
`bash`, `node` and `claude` from it. So:

| targetSdk | Runtime works | Play accepts |
|---|---|---|
| 28 | ✅ | ❌ |
| 29+ | ❌ | ✅ |

There is no value that satisfies both. Android 14 also raised the "built for an
older version" warning threshold to API 28 — so 28 is simultaneously the last
level where execution works and the first that triggers the warning.

### 2. Play policy forbids downloading executable code

Separately from targetSdk, Play's Device and Network Abuse policy prohibits an
app fetching and running native code that did not ship in the APK. Downloading
`ubuntu-base.tar.gz`, the Node tarball and the `claude` binary is exactly that.

Even if blocker 1 were solved, this one would remain.

---

## Termux is the precedent, and it is not encouraging

Termux hit both blockers years ago and left Play. Maintainer, in the project's
own words:

> *"Termux uses outdated target API level as workaround for SELinux policy
> restrictions regarding user data execution. However the Play Store has a
> requirement that all apps with no exception must target the latest APIs.
> Just forget about Play Store distribution source. It is dead."*

A separate `termux-play-store` project exists and did get an update published,
but the maintainers say it is Android 11+ only and *"we still don't recommend
using it because used solution is flaky."*

Termux ships on **F-Droid**, which imposes no targetSdk floor.

---

## The one genuinely viable Play path

Policy has a specific carve-out: **interpreted code is allowed.** Code running
in a VM or interpreter is exempt from the download-executable-code ban. That
opens a real design:

| Component | Today | Play edition |
|---|---|---|
| Node.js | downloaded tarball, runs in Ubuntu | **built for Android/bionic, shipped as `libnode.so`** |
| Claude Code | native binary (arm64) / npm | **`cli.js` only — downloaded JavaScript is interpreted, so policy-safe** |
| git, ripgrep | apt inside Ubuntu | shipped as `libgit.so`, `librg.so` |
| Ubuntu rootfs | 23 MB download | **gone** |
| PRoot | ptrace supervisor | **gone** |
| apt / dev stacks | Python, PHP, Java, C++ | **gone** |

Everything executable lives in `lib/<abi>/` inside the APK, lands in
`nativeLibraryDir`, which is read-only and **exec-allowed at any targetSdk**.
That defeats both blockers at once.

What it costs: the Linux environment. No apt, no Python/PHP/Java/C++ stacks, no
real shell beyond Android's own `/system/bin/sh`. It becomes "Claude Code on
Android" rather than "a Linux dev box in your pocket".

Effort is substantial — cross-compiling Node for bionic is the hard part; Node
has no official Android target and Termux maintains its own patched build.

---

## Feature-by-feature: what a Play edition keeps and loses

The test for every feature is the same: **does it require executing a native
binary that was downloaded rather than shipped in the APK?**

### Kept, unchanged — no work at all

These touch only Android APIs or interpreted code, so nothing about them
changes.

| Feature | Why it survives |
|---|---|
| Chat, streaming responses, tool-call display | Kotlin + HTTP |
| Providers: Anthropic, OpenAI, Kimi, router, custom endpoint | Kotlin + HTTP |
| Model discovery and picker | Kotlin + HTTP |
| Projects, project location choice, per-project folders | Kotlin file I/O |
| Diffs, checkpoints, change tracking | Kotlin file I/O |
| Device access: `/sdcard` files, app list/launch, notifications, screen control | Android APIs |
| Settings, themes, onboarding | Compose |
| **Claude Code itself** | `cli.js` is JavaScript — interpreted, so policy-safe |

### Kept, but must be rebuilt — real engineering

Each of these has to be compiled for Android/bionic and shipped inside the APK
as `lib<name>.so`. They then live in `nativeLibraryDir`, which is read-only and
executable at any targetSdk.

| Feature | Effort |
|---|---|
| Node.js runtime | **Hard.** Node has no official Android target; needs Termux-style patches |
| `bash` | Moderate — Termux builds it |
| `git` | Moderate |
| `ripgrep` (Claude Code's search) | Easy — static Rust binary |
| Terminal screen | Works, but on a much smaller command set |

Android already ships `toybox` at `/system/bin`, giving roughly a hundred
standard commands (`ls`, `cat`, `grep`, `find`, `sed`…), so the base environment
is thinner than Ubuntu but not empty.

### Reduced

| Feature | What is left |
|---|---|
| Python | CPython is an interpreter, so it can be shipped. Pure-Python `pip` packages work. Anything with C extensions — numpy, pandas, lxml — **cannot**, because installing them compiles or downloads native code. |
| Local dev servers | Node servers still run. Anything needing a compiler does not. |

### Gone — and not a matter of effort

| Feature | Why it cannot come back |
|---|---|
| Ubuntu 20.04 rootfs | A downloaded filesystem full of native binaries; both blockers apply |
| `apt` / `apt-get`, any package install | Installing a package means downloading and running native code |
| **C / C++ stack** (gcc, g++, make, cmake, gdb) | **Fundamental.** A compiler's whole job is to produce a native executable at runtime. Even if gcc itself were shipped, its output could never be executed. No amount of work fixes this. |
| **Android / Java stack** (OpenJDK) | `aapt2`, `d8` and the rest of the build tools are native binaries; a full JDK cannot realistically ship as `.so` files |
| PHP stack | PHP could in principle be shipped, but it is another full interpreter port for a small audience |
| Arbitrary Linux software | The entire premise of a package manager is gone |

**The short version:** it stops being "a Linux computer in your pocket" and
becomes "Claude Code with a small toolbox". Editing code, running Node, using
git, searching, and everything the phone-control layer does all still work.
Compiling anything does not.

---

## Options

**A · F-Droid + GitHub, keep everything** ← **CHOSEN**
Ship what exists. targetSdk 28, full Linux environment, all features. Users on
Android 14+ tap "Install anyway" past the Play Protect warning. This is exactly
what Termux does and has done for five years. Zero further engineering.

**B · Build the Play edition as a second variant** *(rejected — costs the Linux runtime)*
Node as `libnode.so`, JS-only Claude Code, no rootfs. Large effort, reduced
product, but genuinely publishable.

**C · Both** *(possible later; would never replace A)*
`com.sid.agent` on F-Droid with the full runtime, `com.sid.agent.lite` on Play
with the interpreted runtime. Most work, widest reach.

---

## What is already done, and what carries over

The Play checklist is otherwise complete: icon, feature graphic, privacy policy,
data safety, content declarations, foreground-service declarations, upload
signing, screenshots, API 36 build switch.

If option B or C is chosen, **none of the app layer is wasted** — Compose UI,
MainViewModel, providers and model picker, chat and streaming, project
management, and the whole `device/` package (files, apps, notifications,
screen control) all carry over unchanged. Only `runtime/` is replaced.

Option A is in effect. B remains documented only so the trade-off is on record;
it would be an additional edition, never a replacement.
