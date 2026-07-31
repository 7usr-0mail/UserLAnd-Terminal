# Getting this onto GitHub Actions

The build works; the 2 GB sandbox it was built in does not. Pushing this repo
gives you 16 GB runners with the Android SDK preinstalled, which turns a
~25-minute, frequently-OOM-killed build into a reliable ~5-minute one.

## One-time setup

```bash
cd userland
git add -A
git commit -m "UserLAnd Terminal: independent fork"

# Create an empty repo on github.com first, then:
git remote set-url origin git@github.com:<you>/userland-terminal.git
git push -u origin master
```

`.github/workflows/build.yml` runs automatically on every push.

## Getting the APK

- **Per commit:** Actions tab -> latest run -> Artifacts -> `UserLAnd-Terminal-debug`
- **Permanent link:** tag a commit and the workflow attaches the APK to a Release:
  ```bash
  git tag v3.0.0 && git push --tags
  ```

## Reading a crash

The reason a crash was hard to diagnose here is that I have no device and no
logcat. With the app installed:

```bash
adb logcat -c && adb shell am start -n tech.terminal.ula/tech.ula.MainActivity
adb logcat -d AndroidRuntime:E '*:S'
```

That prints the exact exception and line. Paste it back and the fix is usually
a one-liner rather than a guess.

## Signed release builds

The workflow produces a debug-signed APK, which installs fine but cannot be
uploaded to a store. For a release build, add your keystore as repository
secrets (`KEYSTORE_BASE64`, `KEY_ALIAS`, `STORE_PASSWORD`, `KEY_PASSWORD`),
decode it in a step, and swap `assembleDebug` for `assembleRelease`.
