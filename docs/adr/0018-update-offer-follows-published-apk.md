# The update offer follows the published APK, not the deploy

The API tells the Android app which release is the latest (`app.releases`), and the app offers it with a link to download. Until now the latest release was simply the newest entry in `packages/api/src/releases.json`, so it changed with the deploy of `main`. The APK is published separately: built by hand and copied to the download folder on the maintainer's server, which Tailscale Funnel serves. When that copy came late, or never (1.6.0 was deployed on 2026-11-07 and its APK was never published; 1.7.0 replaced it twelve hours later), every app from 1.4.0 offered "Version 1.6.0 is out" and its button downloaded 1.5.0 again. Installing that changed nothing, and the offer stayed.

## The download folder says what is published

Publishing an APK (`apps/android/scripts/publish-apk.sh`) writes three files to the download folder, in this order: the APK under its own name (`client-manager-1.7.0.apk`, never changed afterwards), the same file as `client-manager.apk` for links that name no version, and last `latest.json`:

```json
{ "versionCode": 9, "versionName": "1.7.0", "file": "client-manager-1.7.0.apk", "sha256": "…", "publishedAt": "…" }
```

The server reads `latest.json` from the folder of `APP_DOWNLOAD_URL`, at most once a minute per server instance, and:

- counts only the releases up to the one it names: the latest it offers is the newest of those, and a newer entry in releases.json is left out as if it did not exist yet;
- links to that release's own file, so the download is always the version offered, also while a newer one is being copied in;
- when it cannot read the file, keeps what it read last for an hour, and after that offers nothing newer than the app that asks. It never offers what it cannot see.

So the deploy and the APK can be published in either order. Nothing changes in the app: every app from 1.4.0 reads the same answer, with the same fields, and apps before 1.4.0 never asked.

## Alternatives

- **Mark a release as published in releases.json** with a second deploy after the APK is copied. No network read on the server, but every release takes two deploys, and a forgotten second one leaves the release unannounced.
- **Keep the APK where the server is**, in the repository or at the hosting provider. The repository is public and the APK would be a 3 MB binary in every clone; the hosting plan has no file storage for it.
- **Let the app check the download itself** (a HEAD request, or the file's size). That needs a new app version, so it would help no app that is installed now, which is the point of the change.
