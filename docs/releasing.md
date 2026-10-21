# Releasing the Android app

The app knows its own version (`versionCode` and `versionName` in `apps/android/app/build.gradle.kts`, read through
`BuildConfig`). The server knows the latest release and what every release changed: `packages/api/src/releases.json`,
which `app.releases` serves. When the latest release there is newer than the app, Home offers the update; after an
update, Home shows what changed ("Что нового"). See App Update and Release Notes in `CONTEXT.md`.

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
3. **Merge to `main`.** The deploy of the API now names the new version as the latest.
4. **Build and publish the APK right after that deploy** (`./gradlew :app:assembleRelease`, see
   `apps/android/README.md`), to the address `APP_DOWNLOAD_URL` names, which `/download` and the app's "Обновить"
   button open. Until the new APK is there, an app that offers the update would download the old one; apps before
   1.4.0 offer nothing either way.

## Which version a Stylist uses

From 1.4.0 the app sends `X-App-Version-Code` and `X-App-Version-Name` with every request, and the server keeps the
last one on the Stylist (`appVersionCode`, `appVersionName`, `appVersionSeenAt`, written at most about once an hour).
An app before 1.4.0 sends neither, and those columns stay as they were.
