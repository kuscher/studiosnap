# Releasing StudioSnap

A release is a GitHub Release with the signed APK attached twice: as `StudioSnap.apk` (the README's
download button points at `releases/latest/download/StudioSnap.apk`, so this name is fixed) and as
`StudioSnap-<version>.apk`.

**Every release must be signed with the StudioSnap release key** (alias `studiosnap`, certificate
SHA-256 `3A:D1:42:18:66:04:86:BC:D4:88:8E:24:D1:C3:4F:16:84:A2:43:B7:89:6F:6A:BD:6C:72:87:93:F2:F4:57:0F`).
Android only installs an update over an existing app when both are signed with the same key; a
release signed with anything else forces everyone to uninstall first. The key lives with the
maintainer (see CLAUDE.md) and in the repo's Actions secrets; it is never committed.

## Before building

1. The version bump is on `main`: `versionCode` +1 and `versionName` in `app/build.gradle.kts`.
2. Release notes are in `docs/release-notes/<version>.md` (what's new, and any new permissions,
   since Android asks for them after the update).

## A. Build and publish by hand (fallback, whoever holds the key)

With the key at `~/.config/studiosnap/keystore.jks` and its password in
`~/.config/studiosnap/keystore.pass`, `app/build.gradle.kts` signs release builds automatically.

```bash
git checkout main && git pull
./gradlew :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
apksigner verify --print-certs "$APK" | grep SHA-256    # must be 3A:D1:42:18:...:57:0F
cp "$APK" /tmp/StudioSnap.apk && cp "$APK" /tmp/StudioSnap-0.4.apk
gh release create v0.4 --target main --title "StudioSnap 0.4" --notes-file docs/release-notes/0.4.md \
  /tmp/StudioSnap.apk /tmp/StudioSnap-0.4.apk
```

If `apksigner` isn't on PATH, it's in `$ANDROID_HOME/build-tools/<version>/`. If the build prints
`app-release-unsigned.apk` instead, the key wasn't found: stop, don't publish an unsigned or
differently signed APK.

## B. Publish by pushing a tag (GitHub Actions)

This is the usual way. Anyone with write access to the repo can release. The workflow is
`.github/workflows/release.yml`, and the key lives in the repo's Actions secrets
`STUDIOSNAP_KEYSTORE_B64` (the keystore, base64) and `STUDIOSNAP_KEYSTORE_PASS`.

1. On `main`: bump `versionCode` and `versionName`, and add `docs/release-notes/<version>.md`.
2. Tag the commit and push the tag: `git tag v0.5 && git push origin v0.5`.
3. The workflow then:
   - checks that the notes file exists and that `versionName` matches the tag;
   - builds and signs the APK;
   - refuses to publish if the certificate isn't the StudioSnap key;
   - publishes the release with `StudioSnap.apk`, `StudioSnap-<version>.apk` and its `.sha256`.

To test without publishing, press **Run workflow** on the Actions tab (Release). It runs the same
signed build and certificate check, and doesn't publish.

**Who can do what:**
- **Collaborators:** anyone with write access can push a tag or run the workflow, and so could also
  read the key through a workflow of their own. Give write access only to people you'd trust with
  the key.
- **Everyone else can't:** outsiders can't push tags or branches, and workflows from forks never
  get the secrets.
- **Third-party code:** the job that holds the key runs only GitHub's own actions, pinned to exact
  commits, so no third-party action ever sees it.

**Restoring the secrets** (for example, after a key restore from the backup):
- `base64 -w0 ~/.config/studiosnap/keystore.jks | gh secret set STUDIOSNAP_KEYSTORE_B64`
- `gh secret set STUDIOSNAP_KEYSTORE_PASS < ~/.config/studiosnap/keystore.pass`
