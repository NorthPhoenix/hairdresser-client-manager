# v1 application stack

In v1, the product uses a Turborepo with an Expo mobile app for authenticated Stylist workflows, a Next.js web app for public Profile Share pages and the tRPC API host, Clerk for email/password Stylist authentication, Prisma with Postgres for app-domain persistence, UploadThing for app-owned binary files, Tailwind CSS for web styling, and NativeWind as the React Native Tailwind layer for mobile styling.

The backend API is tRPC-first so frontend and backend contracts stay type-safe across the monorepo. The intended tRPC package shape follows the `t3-oss/create-t3-turbo` pattern: a shared API package owns router definitions and exported router types, Next.js serves `/api/trpc`, and Expo consumes the API over HTTP while importing only the type contract it needs.

NativeWind is the v1 mobile styling choice because it keeps mobile styling close to Tailwind's utility-class authoring model and matches the `t3-oss/create-t3-turbo` Expo example. Broader cross-platform UI systems such as Tamagui are not ruled out for future needs, but they are not part of the initial stack.

Tailwind configuration should share theme tokens across web and mobile early, such as colors, spacing, typography names, and semantic design tokens. A shared cross-platform UI component package is deferred until real repeated components justify it.

The shared package should remain limited to true cross-runtime primitives such as locale definitions, localization helpers, and pure schemas when both client and server need them. Server workflow logic belongs in the API package or server-only helpers, and persistence belongs in the database package.

Server data in the mobile app should be managed through tRPC's TanStack Query integration. Local React state is enough for form drafts and ephemeral UI state; an additional client-side state store is deferred until there is real cross-screen client-only state that query caching and route state do not cover.
