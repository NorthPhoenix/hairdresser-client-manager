# Next.js hosts the tRPC backend API

In v1, the Next.js app hosts both public Profile Share pages and the authenticated tRPC backend API for the Expo mobile app. The web browser UI remains limited to Profile Shares, while mobile management workflows call tRPC procedures that map Clerk Stylist identity to database records instead of importing Prisma into the mobile app or introducing a separate backend service.

The tRPC router lives in a shared API package so Expo can consume router types for end-to-end type safety while Next.js remains the only production runtime that executes backend procedures. The `t3-oss/create-t3-turbo` repository is the reference implementation pattern for using tRPC across Next.js and Expo in a Turborepo.

The shared API package may import the database package and other server-only dependencies. The Next.js app imports the API package at runtime to serve `/api/trpc`, while the Expo app imports only router types such as `AppRouter`, `RouterInputs`, and `RouterOutputs` and calls the hosted endpoint over HTTP.

Protected tRPC procedures should first verify Clerk authentication without automatically loading the full Stylist record. Procedures that need app-domain identity use a narrower Stylist middleware that lazily resolves or creates the database Stylist and exposes it on context; procedures that only need Clerk auth should not pay for a Stylist lookup.

The initial Stylist-loading procedure should be a small bootstrap query that returns the current database Stylist and onboarding status, creating the minimum Stylist record if missing. Settings and onboarding changes should use explicit mutations rather than turning bootstrap into a broad startup command.

tRPC routers should follow the glossary's domain language where practical, such as `stylist`, `client`, `appointment`, and `profileShare`, rather than vague buckets like `user`, `settings`, `records`, or `dashboard`.

tRPC procedures should validate inputs with Zod and return explicit domain-facing output shapes. Prisma models remain internal persistence shapes and should not become public API contracts by accident.

Public server-rendered Profile Share pages do not need to fetch through tRPC by default. They may use direct server-side database reads in the Next.js page or supporting server-only functions, with tRPC reserved for app-client API calls unless shared client behavior requires a procedure.

Expo tRPC requests should send the Clerk session token as a Bearer token, and the Next.js tRPC context should verify Clerk authentication server-side. Adding tRPC does not change the v1 email/password-only auth decision, and Google Calendar remains a separate optional integration rather than an auth method.
