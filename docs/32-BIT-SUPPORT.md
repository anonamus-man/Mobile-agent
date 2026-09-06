# 32-bit ARM support

Mobile Agent builds and runs on `armeabi-v7a` (ARMv7, 32-bit) in addition to
`arm64-v8a`. This document explains what had to change, what works, and what is
genuinely different on a 32-bit device.

---

## Why the architecture cannot simply be "added"

PRoot supervises the guest with `ptrace`. A 32-bit tracer cannot single-step a
64-bit tracee, so **the guest userspace must match the bitness of the app
process, not the phone**. An `armeabi-v7a` APK installed on an ARM64 phone still
reports `arm64-v8a` in `Build.SUPPORTED_ABIS` while running as a 32-bit process —
so architecture detection is based on `Process.is64Bit()`, not the device ABI
list. See `HostArchitecture.detect`.

Everything the installer downloads follows from that one decision:

| Component | `arm64-v8a` | `armeabi-v7a` |
| :--- | :--- | :--- |
| Ubuntu rootfs | `ubuntu-base-20.04.5-base-arm64` | `ubuntu-base-20.04.5-base-armhf` |
| Node.js | v24.19.0 (`linux-arm64`) | v22.20.0 (`linux-armv7l`) |
| Claude Code | signed native binary (`linux-arm64`) | npm tarball, run by the guest Node.js |
| ripgrep | vendored in Claude Code | installed from the Ubuntu archive |
| PRoot loader address | `0x2000000000` | `0x20000000` |
| Minimum RAM check | 4 GB | 2 GB |

---

## The Claude Code problem (read this one)

Anthropic's release manifest publishes binaries for `linux-x64`, `linux-arm64`,
their musl variants, macOS, and Windows. **There is no `linux-arm` build**, and
there is no way to run the arm64 binary from a 32-bit PRoot.

From version **2.1.113** the npm package `@anthropic-ai/claude-code` also stopped
being real code: it is now a postinstall script that downloads one of those
native binaries. Its platform map has no 32-bit ARM entry either.

Version **2.1.112 is the last release whose `bin.claude` points at a runnable
`cli.js`** — 9 MB of bundled JavaScript that runs on any Node.js ≥ 18, including
the 32-bit `armv7l` build.

So on ARMv7 the installer:

1. reads the abbreviated npm packument in a single request,
2. picks the newest version whose `bin.claude` still ends in `cli.js`
   (falling back to the pinned `2.1.112` if the lookup fails),
3. verifies the tarball against the registry's SHA-512 `integrity` hash,
4. extracts it to `/usr/local/lib/claude-code`, and
5. installs a `/usr/local/bin/claude` shell launcher that execs it through Node.

**Consequence:** ARMv7 users are on an older agent than ARM64 users, and they
stay there unless Anthropic publishes a 32-bit binary. Every CLI flag the app
passes (`--bare`, `-p`, `--output-format stream-json`,
`--include-partial-messages`, `--verbose`, `--model`, `--max-turns`) exists in
2.1.112, so the bridge protocol is unchanged.

Two smaller knock-on effects:

* Claude Code vendors ripgrep for arm64/x64 only, so the runtime sets
  `USE_BUILTIN_RIPGREP=0` and installs the distro `ripgrep` package instead.
* The launcher caps V8 at `--max-old-space-size=${MH_NODE_HEAP_MB:-1024}`. A
  32-bit process has roughly 3 GB of address space in total; leaving V8 to guess
  invites an allocation failure in the middle of a long agent turn.

---

## Native build (PRoot)

PRoot itself supports 32-bit ARM upstream (`ARCH_ARM_EABI`), so `CMakeLists.txt`
only had to stop hard-coding ARM64 details:

* **Loader address.** The loader is linked at a fixed `-Ttext`, and
  `0x2000000000` is unmappable in a 32-bit address space. The value now tracks
  `LOADER_ADDRESS` in `third_party/proot/src/arch.h` — `0x20000000` for ARMv7.
* **`pokedata_workaround`.** `HAS_POKEDATA_WORKAROUND` is defined for ARM64 only;
  the stub lives behind `#if defined(__aarch64__)` in `loader/assembly.S`, and
  `loader-info.c` exists purely to record its offset. Both the
  `-u,pokedata_workaround` link flag and the generated file are now ARM64-only,
  matching what upstream's makefile does.
* **Page alignment.** `-Wl,-z,max-page-size=16384` implements Android's 16 KB
  page-size requirement for 64-bit libraries. ARMv7 kernels are 4 KB, so the
  32-bit link uses 4096 and does not pay the padding.

Verified output:

```
libprootloader.so   ELF32 ARM   EXEC   entry 0x20000001   (Thumb bit set; PRoot
                                                           clears PSR_T in
                                                           execve/exit.c)
libproot.so         ELF32 ARM   DYN
libtalloc.so        ELF32 ARM   DYN
libandroid-shmem.so ELF32 ARM   DYN
libpocketspawn.so   ELF32 ARM   DYN
```

The ARM64 output is byte-for-byte unchanged in configuration: entry still
`0x2000000000`, workaround still linked in.

---

## Building

```bash
# Both ABIs (default)
./gradlew assembleDebug

# One ABI
./gradlew assembleDebug -PmhAbis=armeabi-v7a
./gradlew assembleDebug -PmhAbis=arm64-v8a

# Unit tests
./gradlew testDebugUnitTest
```

The extra ABI adds roughly 1.3 MB of native libraries to a universal APK, which
is noise next to the ~500 MB runtime the app downloads on first launch.

To sanity-check just the native layer without Gradle:

```bash
cmake -S app/src/main/cpp -B /tmp/mh-v7a -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release
ninja -C /tmp/mh-v7a
```

---

## Upgrade behaviour

The rootfs marker embeds the Debian architecture (`ubuntu-20.04.5-armhf` vs
`ubuntu-20.04.5-arm64`), so a device that somehow switches ABI — a user moving
from a universal APK to a split, for instance — invalidates the old guest and
re-provisions instead of executing binaries of the wrong machine type.
`isInstalled()` additionally checks that the JavaScript payload is present, so an
install interrupted between "launcher written" and "payload extracted" repairs
itself rather than failing at the first prompt.

---

## What is *not* different

Terminal, file browsing and editing, diffs and checkpoints, web preview, the
direct provider API path (`ProviderApiClient`), Keystore-backed secrets, and the
Python / C++ / PHP / Java stacks all work the same way — Ubuntu ships all of them
for armhf. Only the agent binary channel and the toolchain versions differ.

## Known limitations on ARMv7

* The agent is pinned to the last JavaScript Claude Code release (see above).
* Node.js is capped at the 22 LTS line; Node 24 dropped 32-bit ARM.
* The guest stays on Ubuntu 20.04 to match the ARM64 path. armhf `ubuntu-base`
  tarballs do exist for 22.04 and 24.04, so moving both architectures forward
  later is a version bump in `RuntimeArtifacts`, not a port.
* Heavy toolchains (Android SDK builds, large `node_modules` installs) will hit
  the ~3 GB per-process address-space ceiling long before an ARM64 device does.
