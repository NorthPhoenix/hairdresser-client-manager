# Mobile local storage is not domain storage

In v1, mobile local storage is limited to device-local session and client state, such as Clerk token persistence and narrow non-domain UI preferences if needed. Canonical Stylist, Client, Appointment, Service, Appointment Photo, and Profile Share data lives in the backend database, so any current SecureStore persistence of domain records is temporary scaffold state to remove when backend CRUD is wired.

Stylist settings such as language, timezone, salon address, and onboarding completion are server-backed profile state, not local device preferences. Device locale and timezone can supply first-run defaults, but saved Stylist settings should follow the Stylist across devices after sign-in.

Existing local Client or Stylist persistence may remain only as temporary scaffold while tRPC-backed flows are introduced. New domain behavior should be built against server-backed tRPC procedures rather than extending the local-storage scaffold.
