# Publishing Eclipse on GitHub (and installing it with Obtainium)

Releases are built by GitHub Actions ([`.github/workflows/release.yml`](../.github/workflows/release.yml)): push a
version tag and a signed APK appears on the repository's Releases page a few minutes later. Obtainium watches that page
and offers each new APK as an update.

## Once: put the project on GitHub

1. On github.com: **New repository** → name it `Eclipse` → leave it empty (no README, licence or .gitignore) → **Create**.
2. Sign git in to GitHub (GitHub doesn't accept your account password for git). The simplest way is the GitHub CLI:

   ```sh
   sudo apt install gh        # or see https://cli.github.com
   gh auth login              # GitHub.com → HTTPS → Yes (authenticate Git) → Login with a web browser
   ```

3. In the project folder on your computer:

   ```sh
   git init
   git add .
   git commit -m "Eclipse 0.1.0"
   git branch -M main
   git remote add origin https://github.com/<your-username>/Eclipse.git
   git push -u origin main
   ```

   The `.gitignore` keeps build output, `local.properties` and the signing key out of the repository.

## Once: make the signing key

Android only installs an update if it is signed with the same key as the version already installed, so every release
must use one key, forever.

1. Run `./tools/make-signing-key.sh` and choose a password. It creates `release.jks`, `keystore.properties` and
   `keystore-base64.txt` in the project folder (none of them are committed).
2. On GitHub: **Settings → Secrets and variables → Actions → New repository secret**, four times:

   | Name | Value |
   | --- | --- |
   | `KEYSTORE_BASE64` | the whole contents of `keystore-base64.txt` |
   | `SIGNING_STORE_PASSWORD` | your password |
   | `SIGNING_KEY_PASSWORD` | your password |
   | `SIGNING_KEY_ALIAS` | `eclipse` |

3. Delete `keystore-base64.txt`. Back up `release.jks` and the password (a password manager is ideal). If the key is
   lost, nobody can update their installed Eclipse any more; they'd have to uninstall it and lose their settings.

With `keystore.properties` present, `./gradlew assembleDebug` signs your own builds with the same key, so builds from your
computer and from GitHub update each other.

## Every release

1. Commit and push your changes.
2. Tag the version and push the tag:

   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```

3. Watch it under the repository's **Actions** tab. When it finishes, the release (with `Eclipse-0.2.0.apk` and notes
   listing the changes since the last tag) is on the **Releases** page.

Version numbers come from the tag: `v1.2.3` becomes version 1.2.3 with version code 10203, so each tag must be higher
than the last. A tag with a dash (`v0.3.0-beta1`) is published as a pre-release, which Obtainium skips unless asked to
include pre-releases.

## Installing with Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium) on the device.
2. **Add App** → paste the repository's address (`https://github.com/<your-username>/Eclipse`) → **Add**.
3. Obtainium downloads the latest release and installs it, then checks for new releases on its own.

## Switching an existing install over

An Eclipse already on the device that was built with the old debug key has a different signature, so the first signed
version can't install over it: uninstall Eclipse once, then install through Obtainium. Uninstalling clears Eclipse's
settings and downloaded artwork (your themes and icon packs in the `Eclipse` folder stay). After that, every update
installs over the last one and keeps everything.
