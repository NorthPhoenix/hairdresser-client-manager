# Native Android app replaces the Expo app

The authenticated Stylist app is a native Android app written in Kotlin with Jetpack Compose, in `apps/android`. It replaces the Expo/React Native app that ADR `0012` chose, and it is the only Stylist client: there is no iOS app. Everything else in ADR `0012` stands, including the Next.js-hosted tRPC API, Clerk email/password auth, Prisma with Postgres, and UploadThing.

This supersedes the mobile parts of earlier records. Where ADRs `0002`, `0008`, `0009`, `0010`, `0011`, and `0012` say "Expo app" or "mobile app", read "the native Android app". NativeWind, Expo Router, and TanStack Query are no longer part of the stack.

## Consequences

The Android app is built with Gradle, not pnpm, so it sits outside the Turborepo task graph and cannot import TypeScript. That removes the compile-time type sharing ADR `0010` relied on, and three things replace it:

- **API contract.** The app calls `/api/trpc` over plain HTTP with hand-written Kotlin wire models (`data/Models.kt`, `data/HcmApi.kt`). A change to a procedure's input or output must be mirrored there by hand.
- **Localization.** The app uses standard Android string resources: English in `res/values/strings.xml` and Russian in `res/values-ru/strings.xml`, switched through Android's per-app language API from the Stylist's saved language. `packages/shared` now holds only the copy for the client-facing web pages and Share Images. A unit test in the app fails when a Russian translation is missing.
- **Theme tokens.** The Android palette repeats the colour values from `themeTokens`; `packages/api/src/v1Hardening.test.ts` checks they still match.

Clerk is used through its Android SDK and prebuilt `AuthView`, and the app still sends the Clerk session token as a Bearer token, so the backend is unchanged.

UploadThing publishes no Android SDK. The app implements the v7 client upload protocol itself in `data/UploadThingClient.kt` against the unchanged `/api/uploadthing` route. Upgrading the `uploadthing` package means re-checking that client against the new version.

Shared rules that used to live in `packages/shared` for the client, such as Contact Import normalization, Client Reminder text, and price parsing, are reimplemented in Kotlin under `domain/` with unit tests. The TypeScript versions remain for the web app and API tests.

Shipping a change now always means a new build through the Play Store; there are no over-the-air JavaScript updates.

## Local development without provider credentials

`apps/dev-backend` serves the real tRPC router and the real UploadThing route handler against a local database, replacing only Clerk (with a `Bearer dev_<name>` header) and UploadThing's storage service (with a local emulator). The Android debug build has a matching dev sign-in that exists only in the debug source set. This is for local development and visual checks only and must never be deployed.
