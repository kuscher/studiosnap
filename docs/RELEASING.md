# Releasing StudioSnap

A release is a GitHub Release with the signed APK attached twice: as `StudioSnap.apk` (the README's
download button points at `releases/latest/download/StudioSnap.apk`, so this name is fixed) and as
`StudioSnap-<version>.apk`. The same tag also puts the Play bundle on Google Play as a draft.

**The short version, for people and agents:** nobody needs the key file. Bump the version, write the
notes, push a tag (section B), and GitHub builds, signs and publishes.

**Every release must be signed with the StudioSnap release key** (alias `studiosnap`, certificate
SHA-256 `3A:D1:42:18:66:04:86:BC:D4:88:8E:24:D1:C3:4F:16:84:A2:43:B7:89:6F:6A:BD:6C:72:87:93:F2:F4:57:0F`).
Android only installs an update over an existing app when both are signed with the same key; a
release signed with anything else forces everyone to uninstall first. The key lives in the repo's
`release` environment on GitHub and with the maintainer (see CLAUDE.md), with a backup in private storage; it is never committed.

## Before building

1. The version bump is on `main`: `versionCode` +1 and `versionName` in `app/build.gradle.kts`.
2. Release notes are in `docs/release-notes/<version>.md` (what's new, and any new permissions,
   since Android asks for them after the update).
3. Google Play's "What's new" text is in `store-submission/listing/en-US/release-notes.txt`
   (500 characters at most).

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
`.github/workflows/release.yml`. Its secrets live in two GitHub environments that only `main` and
`v*` tags can use: `release` holds the signing key (`SIGNING_KEYSTORE_B64`, the keystore in base64,
and `SIGNING_KEYSTORE_PASS`), and `play` holds the Google Play key (`PLAY_SERVICE_ACCOUNT_JSON`).

1. On `main`: bump `versionCode` and `versionName`, add `docs/release-notes/<version>.md`, and
   update `store-submission/listing/en-US/release-notes.txt`.
2. Tag the commit and push the tag: `git tag v0.5.2 && git push origin v0.5.2`.
3. The workflow then:
   - checks that the notes file exists, that `versionName` matches the tag, and that the Play text
     fits in 500 characters;
   - builds and signs the APK and the Play bundle (.aab);
   - refuses to publish if either isn't signed with the StudioSnap key;
   - publishes the GitHub release with `StudioSnap.apk`, `StudioSnap-<version>.apk` and its `.sha256`;
   - uploads the bundle to Google Play's closed-testing track as a **draft** release
     (`tools/play-upload.mjs`), with the Play text. What is live there stays live.
4. **Google Play, the last step by hand:** a draft is not reviewed or served. In the Play Console,
   open the app › Test and release › Closed testing › the draft › Next › Save, then Publishing
   overview › Send for review. Nothing goes to review on its own.

To test without publishing, press **Run workflow** on the Actions tab (Release), or run
`gh workflow run release.yml --ref main`. It runs the same signed build and certificate checks,
checks that the Play key works, and publishes nothing.

If a tag's run fails after the GitHub release was made (for example at the Play step), fix the
cause and re-run the failed job; re-uploading a version code that is already on Play does nothing.

**Who can do what:**
- **Collaborators:** anyone with write access can push a tag or run the workflow, and so could also
  read the key through a workflow of their own. Give write access only to people you'd trust with
  the key.
- **Everyone else can't:** outsiders can't push tags or branches, and workflows from forks never
  get the secrets.
- **Third-party code:** the job that holds the key runs only GitHub's own actions, pinned to exact
  commits, so no third-party action ever sees it.

**Restoring the secrets** (for example, after a key restore from the backup):
- `base64 -w0 ~/.config/studiosnap/keystore.jks | gh secret set SIGNING_KEYSTORE_B64 --env release`
- `gh secret set SIGNING_KEYSTORE_PASS --env release < ~/.config/studiosnap/keystore.pass`
- `jq -c . play-service-account.json | gh secret set PLAY_SERVICE_ACCOUNT_JSON --env play` (the Play
  Console service account's key; the maintainer has it. One line, so the job log masks the whole
  value and not every brace)
