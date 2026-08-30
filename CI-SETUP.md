# Ship PugetFlow to your phone via GitHub Actions + Obtainium

This gets you: `git push` a tag → GitHub builds a **signed APK** → publishes a
**Release** → **Obtainium** on your Pixel 9a installs/updates it. No Android SDK
needed on your computer.

## 1. Make a signing keystore (once)

Android won't let Obtainium *update* the app if the signing key ever changes, so
you create **one** keystore now and reuse it forever. Keep the `.jks` file safe —
if you lose it, you can't publish compatible updates (you'd have to uninstall +
reinstall). It is git-ignored so it won't be committed.

```bash
cd PugetFlow
keytool -genkeypair -v \
  -keystore pugetflow-release.jks \
  -alias pugetflow \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 'PICK_A_PASSWORD' -keypass 'PICK_A_PASSWORD' \
  -dname "CN=PugetFlow, O=Personal, C=US"

# Base64-encode it so it can live in a GitHub secret:
base64 -w0 pugetflow-release.jks > pugetflow-release.jks.b64
```

## 2. Add GitHub secrets

In your repo: **Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret.**
Add these four:

| Secret name         | Value                                             |
|---------------------|---------------------------------------------------|
| `KEYSTORE_BASE64`   | the entire contents of `pugetflow-release.jks.b64`|
| `KEYSTORE_PASSWORD` | the store password you chose above                |
| `KEY_ALIAS`         | `pugetflow`                                        |
| `KEY_PASSWORD`      | the key password you chose above                  |

## 3. Push the code and tag a release

```bash
cd PugetFlow
git init
git add .
git commit -m "PugetFlow: live USGS river gauges on OsmAnd"
git branch -M main

# Create the repo on GitHub (either on the website, or with the gh CLI):
gh repo create pugetflow --public --source=. --remote=origin --push
# ...or if you made it on github.com manually:
# git remote add origin https://github.com/<you>/pugetflow.git
# git push -u origin main

# Cut a release — this is what triggers the build:
git tag v1.0.0
git push origin v1.0.0
```

Watch it under the repo's **Actions** tab. When it finishes, the APK is under
**Releases**. (You can also run the workflow manually from the Actions tab first to
test-build — a manual run just uploads the APK as an artifact instead of releasing.)

For each future update: bump nothing in code if you don't want to — just push a new
tag (`v1.0.1`, `v1.1.0`, …). The workflow sets `versionCode` from the CI run number,
so every tag is a valid upgrade.

## 4. Install with Obtainium (Pixel 9a)

1. Install **Obtainium** (from its own GitHub releases, or F-Droid/Accrescent).
2. **Add App** → paste your repo URL: `https://github.com/<you>/pugetflow`.
3. Obtainium detects the GitHub source and the APK asset; tap **Add**, then **Install**.
   Allow "install unknown apps" for Obtainium when Android prompts.
4. Later, Obtainium shows an update whenever you push a new tag.

## Also install OsmAnd

PugetFlow needs OsmAnd on the same phone (OsmAnd+, OsmAnd, or nightly). Then open
PugetFlow → **Start live updates** → **Open OsmAnd**.

---

### Local build instead? (optional)

- **Android Studio:** `File ▸ Open` the `PugetFlow/` folder → Run. It bundles the
  JDK + SDK. A local debug build is self-signed and installable over USB.
- **Command line:** install the Android SDK (`cmdline-tools`, `platforms;android-35`,
  `build-tools;35.0.0`), set `ANDROID_HOME`, JDK 17, then `./gradlew assembleDebug`.
  Release builds need the keystore env vars from step 1 (`KEYSTORE_FILE`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
