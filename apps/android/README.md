# Android app

The native Android app for Stylists: Kotlin, Jetpack Compose, Material 3. It talks to the tRPC API hosted by `apps/web` and signs in with Clerk. See ADR `0013` for why it is native and what that changes.

## Requirements

- JDK 21
- Android SDK with `platforms;android-37.0`, `build-tools;37.0.0` and `platform-tools`
- For an emulator: `emulator` and a system image such as `system-images;android-35;google_apis;x86_64`

Gradle itself comes from the wrapper (`./gradlew`).

## Configuration

Create `apps/android/local.properties` (it is gitignored):

```properties
sdk.dir=/path/to/Android/Sdk

# Clerk publishable key, the same value as CLERK_PUBLISHABLE_KEY for the web app.
hcm.clerkPublishableKey=pk_test_...

# Origin of the web app that hosts /api/trpc and /api/uploadthing.
# Defaults to http://10.0.2.2:3000, which is the host machine as seen from an emulator.
hcm.apiBaseUrl=http://10.0.2.2:3000

# Origin used in Profile Share links. Defaults to hcm.apiBaseUrl.
hcm.webBaseUrl=
```

Any of these can also be passed as a Gradle property, for example `./gradlew :app:assembleRelease -Phcm.apiBaseUrl=https://example.com`.

Without a Clerk key the app shows a setup screen instead of signing in. Clerk also needs the **Native API** enabled for the application in the Clerk dashboard.

Debug builds allow plain HTTP so they can reach a local backend. Release builds do not, so `hcm.apiBaseUrl` must be HTTPS there. Release signing is not configured yet.

## Commands

Run from `apps/android`:

```sh
./gradlew :app:installDebug          # build and install on the connected emulator or device
./gradlew :app:testDebugUnitTest     # unit tests
./gradlew :app:assembleRelease       # minified release build (unsigned)
```

## Running without provider credentials

`apps/dev-backend` runs the real API against a local Postgres with Clerk and UploadThing's storage replaced by local stand-ins, so the whole app can be used with no accounts.

1. Point `packages/db/.env` at a local or throwaway database and push the schema:

   ```sh
   pnpm db:push
   pnpm --filter @hcm/shared build && pnpm --filter @hcm/api build
   ```

2. Start the backend, and optionally fill it with demo data:

   ```sh
   pnpm --filter @hcm/dev-backend start
   pnpm --filter @hcm/dev-backend seed          # Russian demo workspace for dev user "demo"
   SEED_LANGUAGE=en pnpm --filter @hcm/dev-backend seed
   ```

3. Add the dev sign-in to `local.properties`. It only exists in debug builds and takes priority over Clerk:

   ```properties
   hcm.apiBaseUrl=http://127.0.0.1:3000
   hcm.devAuthUserId=demo
   ```

4. Forward the port so the device reaches the backend on its own loopback, then install:

   ```sh
   adb reverse tcp:3000 tcp:3000
   ./gradlew :app:installDebug
   ```

   `127.0.0.1` with `adb reverse` is used instead of `10.0.2.2` because uploaded photo URLs must resolve to the same address from both the backend and the device.

The dev backend trusts whoever calls it and binds to loopback only. Never deploy it.

## Visual checks from the command line

`scripts/ui.py` drives the running app over `adb`: tap by visible label, type, scroll, and save screenshots. It works on a headless emulator.

```sh
emulator -avd <name> -no-window -no-audio -gpu swiftshader_indirect &
scripts/ui.py launch -- tap "Calendar" -- shot /tmp/calendar.png
scripts/ui.py dump        # list what is on screen
```

## Localization

The app has two languages, English and Russian, handled the standard Android way:

- `app/src/main/res/values/strings.xml` is the English base.
- `app/src/main/res/values-ru/strings.xml` is the Russian translation. It must define every string that is not marked `translatable="false"`.
- Code reads strings with `stringResource(R.string.name)`; no user-facing text is written in Kotlin.

To add or change text, edit both files. `TranslationsTest` fails if a Russian string is missing, a placeholder differs, or an English product term is left in the Russian copy.

The app language is the Stylist's saved setting (Profile → App language). Changing it calls Android's per-app language API (`AppCompatDelegate.setApplicationLocales`), so resources, Material pickers, and date and time formats all switch together, and on Android 13+ the choice also appears under the system's per-app language settings. Before sign-in the app follows the device language.

Two things deliberately do not follow the app language:

- Text sent to a Client, such as the SMS reminder, is written in that Client's language (`Context.inLanguage`).
- The language names "Русский" and "English" are always shown in their own language.

The API reports errors in English only, so `i18n/ErrorMessages.kt` maps them to localized strings instead of showing the server text.

The client-facing web pages keep their own small table in `packages/shared`.

## Layout

```
app/src/main/java/com/northphoenix/hairdresserclientmanager/
  auth/     AuthGateway and its Clerk implementation
  core/     build configuration and dependency wiring
  data/     tRPC client, typed API, wire models, UploadThing client, photo and contact readers
  domain/   pure rules: money, time, client search, contact import, reminder text
  i18n/     app language switching and string helpers (the strings themselves are in res/values*)
  state/    AppViewModel: server data and every mutation
  ui/       theme, shared components, screens
app/src/debug/     dev sign-in, compiled into debug builds only
```

Fonts are Lora and Golos Text, both under the SIL Open Font License. Both were picked for their Cyrillic.
