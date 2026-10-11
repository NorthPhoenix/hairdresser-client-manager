# v1 acceptance hardening

This page is the v1 hardening index for future agents and maintainers. Start with `CONTEXT.md` for domain language, then read the relevant records in `docs/adr/`, especially ADRs `0001`, `0003`, `0005`, `0010`, `0011`, `0012`, and `0013`. The parent PRD is GitHub issue `#1`; child implementation issues are GitHub issues `#2` through `#18`.

## Localization

Russian is the primary language and English is secondary. Stylist language drives app UI copy. Client language drives client-facing Client Reminder messages, Profile Share pages, and Share Images.

The Android app keeps its copy in Android string resources (`apps/android/app/src/main/res/values` for English, `values-ru` for Russian); its `TranslationsTest` fails when a Russian translation is missing. The client-facing web copy lives in `packages/shared` and is covered by `packages/api/src/v1Hardening.test.ts`. In Russian mode the app shows no English product terms.

A Profile Share page states the language it is shown in: the `lang` of the document, its title, its description and the label of its language switch follow the Client language or the `?lang=` of the address (the first one when it is repeated). The page of a link that shows nothing does the same.

## Android QA path

Android is the only device target: the Stylist app is the native Android app in `apps/android`. Without provider credentials, `apps/android/README.md` describes a local dev backend and debug sign-in that cover every step below except real Clerk sign-in. A complete manual pass should cover:

1. Stylist sign-in and lazy Stylist bootstrap.
2. Stylist Onboarding and Settings language/timezone/address changes.
3. Home View unresolved Appointment behavior.
4. Clients View search, create, edit, delete, duplicate phone warning, and one-time Contact Import.
5. Calendar View date navigation and Appointment Creation.
6. Group Appointments, primary Client changes, participant removal, and Google Maps opening.
7. Service Menu Items, Appointment Services, final total override, Color Formulas, and Appointment Copying.
8. Appointment Photos after UploadThing is configured.
9. Profile Share creation, language toggle, Share Image generation, revocation, privacy exclusions, and group scoping.
10. Client Reminder SMS compose behavior.
11. Stylist Reminder push notifications after push notification credentials are configured.
12. Google Calendar Integration after Google OAuth and Calendar credentials are configured.

## Public Profile Share privacy

Profile Share pages and Share Images must show only client-facing shared history. They may show the Client name, the Stylist's account name and salon address, upcoming Appointment timing/location, last completed Appointment timing/location, Services, and what the Stylist chose to include of the last completed Appointment: Color Formulas and stored after photos (both included by default) and stored before and other photos (included when selected). Every date and time is shown in the Stylist's timezone. A Share Image shows the same as the page; what does not fit one picture is counted in it, not dropped silently. Pages and Share Images show text as the Stylist typed it, with characters such as `&`, `<`, `>` and quotes and with the line breaks of a Color Formula; on a Share Image a long text continues on further lines inside the frame, and a character that cannot be drawn becomes a space. On the page a word that is longer than its line, such as a long surname on a phone, is broken: the page is never wider than the screen.

They must not expose Client phone, Client email, Client Note, Appointment note, Service note, prices, internal provider identifiers, database record ids (also not in the page source), Clerk identifiers, the Stylist's email address, or other Stylist-private data. Revoked links must return unavailable behavior: status 404 and a page that says, in the Client's language (or the one the address asks for), that the link is no longer available; the addresses of the picture and of the photos answer 404 with that sentence as text. A link that never existed, was cut short or holds anything but a token (64 hexadecimal digits) answers the same in Russian unless its address asks for English, and is not looked up. Group Appointment history must be scoped to Services and photos for the shared Client. The page serves its photos under the share link itself (`/profile-shares/<token>/photos/<name>`), never from the address of the stored file: a photo is there only as long as the link works and the Stylist includes it, and neither the storage address nor the file key is in the page.

## Optional providers

UploadThing, Google Calendar, and Stylist Reminder push notifications are optional provider surfaces for v1 setup. Missing configuration should not block core Client, Appointment, Service, Color Formula, Contact Import, Profile Share, Share Image, or Client Reminder SMS compose flows.

When provider credentials are absent, the app should show clear setup or unavailable behavior instead of failing silently. Provider adapters should be tested through fakes/mocks so Appointment save behavior remains covered without live UploadThing, Google Calendar, or push notification services.

## Durable checks

Run these before merging v1 acceptance or feature-hardening changes:

```sh
pnpm --filter @hcm/shared build
pnpm --filter @hcm/api test
pnpm lint
pnpm build
(cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug)
```
