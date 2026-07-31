# UserLAnd Terminal — fork changes

An independent fork of [CypherpunkArmory/UserLAnd](https://github.com/cypherpunkarmory/userland).

## Identity
- App name: **UserLAnd Terminal**; launcher label: **Terminal** (`android:label` on the launcher activity).
- `applicationId` changed `tech.ula` → `tech.terminal.ula`; document provider authority updated to match.
- Launcher icon replaced with the supplied artwork, generated at all five densities plus adaptive-icon foreground; adaptive background set to black.

## Independence (no third-party runtime dependencies)
- **Sentry telemetry removed.** `SentryLogger` reimplemented as a local logcat-only logger; the `io.sentry` dependency is gone. Nothing leaves the device.
- **Google Play Billing removed.** `BillingManager.kt` deleted, `ContributionPrompter` removed, `billing-ktx` and `play-services-base` dependencies dropped.
- **proot/busybox engine vendored.** The build no longer downloads `*-assets.zip` from a third-party GitHub release at build time. All four ABIs are committed under `app/src/main/jniLibs/` (~27 MB).
- **Per-distro support assets vendored** under `app/src/main/assets/distro/<distro>/<arch>/` for ubuntu, debian, alpine, arch × arm, arm64, x86, x86_64.
- **App catalog vendored** under `app/src/main/assets/apps/`. `GithubAppsFetcher` → `LocalAppsFetcher`, reading the bundled catalog; the app works offline and never queries github.com.
- `GithubApiClient` deleted.

## Resources from official OS archives
`OfficialArchiveResolver` resolves root filesystems from each distribution's own archive:

| Distro | Source |
|---|---|
| Ubuntu | `cdimage.ubuntu.com/ubuntu-base` (+ `archive.ubuntu.com` / `ports.ubuntu.com` for apt) |
| Debian | official debuerreotype rootfs (+ `deb.debian.org` for apt) |
| Alpine | `dl-cdn.alpinelinux.org` minirootfs |
| Arch   | `os-archive.archlinuxarm.org` / `geo.mirror.pkgbuild.com` |

Newest point releases are discovered by parsing the archive's own directory index, with pinned fallbacks when offline.

Because official tarballs are pristine (they assume a real kernel, systemd and root), `assets/bootstrap/bootstrapOfficialRootfs.sh` patches them on first boot: DNS/resolv.conf, hosts, mtab, apt sandbox and `policy-rc.d`, `ischroot` diversion, the non-root user, login profile, and `sources.list` pointed at the official archive. `extractFilesystem.sh` was rewritten to sniff the real compression (gzip/xz/zstd/bzip2) and to flatten nested bootstrap layouts.

## New "Android" app
- New catalog entry **Android** with an Android-robot thumbnail in the matrix palette.
- `AndroidShellActivity` + `AndroidShellLauncher` run a shell **directly on the device** in the built-in terminal view — no proot, no rootfs download, instant start.
- Busybox applet symlinks are exported and prepended to `PATH`, so GNU-style tools work alongside toybox.
- Root is auto-detected: if a working `su` exists the shell is elevated, otherwise it runs in the app sandbox.

## Matrix colour scheme
Phosphor green (`#00FF41`) on black throughout: `colors.xml` rewritten, `AppTheme` moved to a dark base with matrix control/text colours, plus a dedicated `TerminalTheme` and `MatrixAlertDialog`. Hardcoded `#FFFFFF` text in layouts replaced with theme colours.

## Permissions
Added: `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`, granular `READ_MEDIA_*` (Tiramisu+), `MANAGE_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `VIBRATE`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `EXPAND_STATUS_BAR`, `SYSTEM_ALERT_WINDOW`. Storage permissions are version-scoped with `maxSdkVersion`. Service declares `foregroundServiceType`. Removed `com.android.vending.BILLING`.

## Illegal State fix
Three distinct root causes:
1. **`state.value!!` NPE.** Both FSMs seeded their `MutableLiveData` with `postValue`, which is asynchronous — an event arriving first read `null` and threw. Now seeded synchronously (`value =`) and read through a null-safe `currentState()`.
2. **Permissions that can never be granted.** `PermissionHandler` demanded `READ/WRITE_EXTERNAL_STORAGE` unconditionally; on Android 13+ these are never granted, so the startup FSM waited forever and reported an illegal state. Permissions are now chosen per API level, and `grantResults[1]` is no longer indexed blindly (it threw `ArrayIndexOutOfBounds` on partial answers).
3. **Missing filesystem.** `findFilesystemForSession` used `!!` and crashed when a session outlived its filesystem. It now returns null and emits a recoverable `SessionIsMissingFilesystem` state with a user-facing message.

## Paywall / unlocks
- All Play Billing code removed; nothing can gate on a purchase.
- `LocalAppsFetcher` forces `isPaidApp = false` for every catalog entry.
- The full app catalog (including previously gated entries) is bundled and available offline.

## systemd equivalent (honest scope)
Real systemd **cannot** run under proot: it requires PID 1, cgroup v2 write access and kernel namespaces that an unrooted Android app cannot obtain. Instead `assets/bootstrap/installServiceManager.sh` installs a drop-in **`systemctl` / `service` / `journalctl` shim** that parses real `.service` unit files, honours `ExecStartPre`/`ExecStart`, supervises via PID files, supports `start/stop/restart/status/enable/disable/is-active/list-units`, and auto-starts enabled units at session launch. This is labelled as a shim rather than presented as systemd.

---

## Build

Upstream pinned Gradle 5.1.1 + AGP 3.4.3 + **jcenter**, which is shut down — the original project can no longer resolve dependencies at all. The toolchain was migrated to:

- Gradle 7.6.4, AGP 7.4.2, Kotlin 1.7.21 (last release supporting `kotlin-android-extensions` synthetics, used in 8 files), JDK 17
- `jcenter()` → `mavenCentral()`; `namespace` added to all four modules; `package` removed from manifests
- AndroidX alphas/betas → stable (`lifecycle-extensions` split into `livedata/viewmodel/runtime-ktx`), okhttp 3→4, compile/target SDK 34
- `ViewModelProviders.of()` → `ViewModelProvider()`; `ViewModelProvider.NewInstanceFactory` → `ViewModelProvider.Factory`
- `android:exported` added where required by Android 12+; `sharedUserId` removed (blocked on API 34)

**Result: `BUILD SUCCESSFUL` — `UserLAnd-Terminal-debug.apk` (35.8 MB).**

Verified in the built APK: package `tech.terminal.ula`, app label `UserLAnd Terminal`, launcher label **`Terminal`**, all permissions present, 192 bundled distro asset entries, 116 native lib entries, the Android app assets and both bootstrap scripts.

### Installing
The APK is signed with the standard debug key, so it installs directly via `adb install UserLAnd-Terminal-debug.apk` or by opening it on-device. For a release build, supply your own keystore in `app/build.gradle` (`signingConfigs`) and run `./gradlew assembleRelease`.

---

## Note on the rebuild (second pass)

The first build's artifacts were lost when the workspace snapshot exceeded its
budget: the JDK and Android SDK had been unpacked *inside* `/home/user`, and
those ~45,000 files evicted the actual deliverables. The toolchains now live in
`/opt/tools` (outside the snapshot root) and only the project and the APK are
kept in the workspace.

Two extra build-host fixes were needed the second time around, both caused by
the 2 GB RAM ceiling on the builder:

- `mergeExtDexDebug` repeatedly OOM-killed the Gradle daemon. Resolved with
  `-Xmx1100m -XX:+UseSerialGC -XX:MaxRAM=1600m` plus `android.enableArtProfiles=false`.
- Several git-tracked files (`gradle-wrapper.properties`, the termux module
  `build.gradle` files and their manifests) reverted to upstream on eviction and
  had their namespace / `android:exported` / Gradle-8 changes reapplied.
