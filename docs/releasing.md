# Releasing the Android app

The app knows its own version (`versionCode` and `versionName` in `apps/android/app/build.gradle.kts`, read through
`BuildConfig`). The server knows what every release changed (`packages/api/src/releases.json`) and which release is
published: the one `latest.json` names, in the download folder next to the APK. `app.releases` serves the releases up
to the published one, and the address of that release's own APK. When the published release is newer than the app,
Home offers the update; after an update, Home shows what changed ("Что нового"). See App Update and Release Notes in
`CONTEXT.md`, and docs/adr/0018 for why the published APK, and not the deploy, decides what is offered.

## Steps

1. **Bump the version** in `apps/android/app/build.gradle.kts`: `versionCode` + 1, and the new `versionName`.
   Android refuses to install a lower `versionCode` over a higher one.
2. **Add the release notes** as a new entry in `packages/api/src/releases.json`:

   ```json
   {
     "versionCode": 7,
     "versionName": "1.5.0",
     "date": "2026-11-02",
     "notes": {
       "ru": ["Одна строка на то, что видно в приложении"],
       "en": ["One line per thing you can see in the app"]
     }
   }
   ```

   Write for the Stylist: short, plain, one line per thing she can see, Russian first, English the same lines in the
   same order. Russian about a Client stays gender-neutral (CONTEXT.md, Language Support). Leave out what she cannot
   see (refactors, server fixes). The order of entries in the file does not matter; the highest `versionCode` is the
   latest. The API tests check the file, and that its latest entry is the version `build.gradle.kts` builds.
3. **Merge to `main`.** The deploy brings the new notes, but offers nothing new yet: the published APK is still the
   old one.
4. **Build the APK** at `main` (`./gradlew :app:assembleRelease`, see `apps/android/README.md`) and **publish it**:

   ```sh
   apps/android/scripts/publish-apk.sh app/build/outputs/apk/release/app-release.apk ~/hcm-downloads
   ```

   It refuses an APK signed with another key than the published one, or one older than it. It writes the APK as
   `client-manager-<versionName>.apk`, the same file as `client-manager.apk` (where `APP_DOWNLOAD_URL` and `/download`
   point), and then `latest.json`. Within a minute the server offers the new release, with a link to its own file.

Steps 3 and 4 can happen in either order. An APK published before the deploy is offered under the newest release the
server knows until the deploy brings its notes; a deploy before the APK offers nothing new until the APK is there. If
the server cannot read `latest.json` (the download server is off), it keeps what it read last for an hour, and after
that offers no update at all: never one it cannot see. Apps before 1.4.0 offer nothing either way.

A download server without `latest.json` publishes nothing as far as the server is concerned: run the script once for
the APK that is there before deploying a server that reads it.

## Which version a Stylist uses

From 1.4.0 the app sends `X-App-Version-Code` and `X-App-Version-Name` with every request, and the server keeps the
last one on the Stylist (`appVersionCode`, `appVersionName`, `appVersionSeenAt`, written at most about once an hour).
An app before 1.4.0 sends neither, and those columns stay as they were.
