# v1 acceptance hardening

This page is the v1 hardening index for future agents and maintainers. Start with `CONTEXT.md` for domain language, then read the relevant records in `docs/adr/`, especially ADRs `0001`, `0003`, `0005`, `0010`, `0011`, and `0012`. The parent PRD is GitHub issue `#1`; child implementation issues are GitHub issues `#2` through `#18`.

## Localization

Russian is the primary language and English is secondary. Stylist language drives app UI copy. Client language drives client-facing Client Reminder messages, Profile Share pages, and Share Images.

The shared localization table is covered by `packages/api/src/v1Hardening.test.ts`, which fails when any v1 message key is missing an English or Russian value.

## Android QA path

Use Android as the primary device target for v1 acceptance. A complete manual pass should cover:

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

Profile Share pages and Share Images must show only client-facing shared history. They may show the Client name, upcoming Appointment timing/location, last completed Appointment timing/location, Services, Color Formulas, and selected Appointment Photos after the photo provider is implemented.

They must not expose Client phone, Client email, Client Note, Appointment note, Service note, prices, internal provider identifiers, Clerk identifiers, or other Stylist-private data. Revoked links must return unavailable behavior, and group Appointment history must be scoped to Services and photos for the shared Client.

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
```
