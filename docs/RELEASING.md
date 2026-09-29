# Releasing StudioSnap

A release is a GitHub Release with the signed APK attached twice: as `StudioSnap.apk` (the README's
download button points at `releases/latest/download/StudioSnap.apk`, so this name is fixed) and as
`StudioSnap-<version>.apk`.

**Every release must be signed with the StudioSnap release key** (alias `studiosnap`, certificate
SHA-256 `E1:D1:CB:07:3B:BD:58:25:4D:B0:EA:27:AA:31:E1:93:31:A8:2F:20:E1:E9:EC:01:1B:7D:04:1A:9F:79:69:7A`).
Android only installs an update over an existing app when both are signed with the same key; a
release signed with anything else forces everyone to uninstall first. The key lives with the
maintainer (see CLAUDE.md); it is never committed.

## Before building

1. The version bump is on `main`: `versionCode` +1 and `versionName` in `app/build.gradle.kts`.
2. Release notes are in `docs/release-notes/<version>.md` (what's new, and any new permissions,
   since Android asks for them after the update).

## A. Build and publish by hand (whoever holds the key)

With the key at `~/.config/studiosnap/keystore.jks` and its password in
`~/.config/studiosnap/keystore.pass`, `app/build.gradle.kts` signs release builds automatically.

```bash
git checkout main && git pull
./gradlew :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
apksigner verify --print-certs "$APK" | grep SHA-256    # must be E1:D1:CB:07:...:69:7A
cp "$APK" /tmp/StudioSnap.apk && cp "$APK" /tmp/StudioSnap-0.4.apk
gh release create v0.4 --target main --title "StudioSnap 0.4" --notes-file docs/release-notes/0.4.md \
  /tmp/StudioSnap.apk /tmp/StudioSnap-0.4.apk
```

If `apksigner` isn't on PATH, it's in `$ANDROID_HOME/build-tools/<version>/`. If the build prints
`app-release-unsigned.apk` instead, the key wasn't found: stop, don't publish an unsigned or
differently signed APK.

## B. Publish by pushing a tag (GitHub Actions)

One-time setup by the key holder; afterwards anyone with write access releases by pushing a tag,
and the key never leaves GitHub's encrypted secrets.

1. **Add two repository secrets** (Settings > Secrets and variables > Actions > New repository
   secret):
   - `STUDIOSNAP_KEYSTORE_B64`: the keystore, base64-encoded: `base64 -w0 ~/.config/studiosnap/keystore.jks`
   - `STUDIOSNAP_KEYSTORE_PASS`: the keystore password.
2. **Protect release tags** (Settings > Rules > Rulesets > new tag ruleset for `v*`, restrict
   creation to maintainers), so only trusted people can trigger a signed build.
3. **Add the workflow** below as `.github/workflows/release.yml` and merge it.
4. **To release:** bump the version and add `docs/release-notes/<version>.md` on `main`, then
   `git tag v0.5 && git push origin v0.5`. The workflow builds, checks the certificate, and
   publishes.

Secrets aren't exposed to workflows triggered from forks, and this one only runs on tag pushes. The
certificate check stops a release that isn't signed with the StudioSnap key.

```yaml
name: Release

on:
  push:
    tags: ["v*"]

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - uses: android-actions/setup-android@v3

      - name: SDK packages
        run: sdkmanager "platforms;android-37.0" "build-tools;36.0.0"

      - name: Signing key
        env:
          KEYSTORE_B64: ${{ secrets.STUDIOSNAP_KEYSTORE_B64 }}
          KEYSTORE_PASS: ${{ secrets.STUDIOSNAP_KEYSTORE_PASS }}
        run: |
          mkdir -p ~/.config/studiosnap
          echo "$KEYSTORE_B64" | base64 -d > ~/.config/studiosnap/keystore.jks
          printf '%s' "$KEYSTORE_PASS" > ~/.config/studiosnap/keystore.pass

      - name: Build
        run: ./gradlew :app:assembleRelease --no-daemon

      - name: Check the certificate
        run: |
          APK=app/build/outputs/apk/release/app-release.apk
          "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$APK" \
            | grep -q "SHA-256 digest: e1d1cb073bbd58254db0ea27aa31e19331a82f20e1e9ec011b7d041a9f79697a"

      - name: Publish
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          cp app/build/outputs/apk/release/app-release.apk StudioSnap.apk
          cp StudioSnap.apk "StudioSnap-$VERSION.apk"
          gh release create "$GITHUB_REF_NAME" --title "StudioSnap $VERSION" \
            --notes-file "docs/release-notes/$VERSION.md" StudioSnap.apk "StudioSnap-$VERSION.apk"

      - name: Remove the key
        if: always()
        run: rm -rf ~/.config/studiosnap
```

Test the workflow once with a throwaway tag on a fork or a pre-release before relying on it.
