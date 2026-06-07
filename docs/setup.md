# Local setup

This repo starts as a Turborepo with an Expo mobile app, a Profile Share-only Next.js web app, shared localization, and Prisma/Postgres wiring.

Because this is a new application, use current stable APIs and package names by default. Do not add legacy or compatibility APIs unless a current API is blocked and the reason plus removal path are documented.

## Human setup checkpoints

Run these steps locally when you are ready to wire providers:

1. Use the project Node runtime.

   The project targets the latest stable Node release for local development. On June 5, 2026, the official Node download page lists Node `26.3.0` as the latest release.

   ```sh
   mise install
   node -v
   ```

   If `node -v` does not print `v26.3.0`, activate `mise` in your shell and restart the terminal:

   ```sh
   echo 'eval "$(mise activate zsh)"' >> ~/.zshrc
   ```

   `mise exec -- <command>` is only a fallback for non-interactive shells that do not load the activation hook.

2. Enable the project package manager:

   ```sh
   corepack enable
   corepack prepare pnpm@10.33.0 --activate
   pnpm -v
   ```

3. Install dependencies:

   ```sh
   pnpm install
   ```

   pnpm may report ignored dependency build scripts and suggest `pnpm approve-builds`. Treat that as a human checkpoint: review the listed packages before approving scripts.

4. Create a Clerk application for Stylist auth.

   Configure Clerk for email/password authentication only. Do not enable Google or other social sign-in methods for v1 auth.

   This project is intended to be linked to Clerk application `app_3EmiNCZ348vVMAzBiVMPhvFd0CG`. If you use the Clerk CLI, authenticate first and then initialize the existing project:

   ```sh
   clerk auth login
   clerk init --app app_3EmiNCZ348vVMAzBiVMPhvFd0CG
   clerk doctor
   ```

5. Copy environment variables:

   ```sh
   cp .env.example .env
   ```

6. Fill in Clerk keys:

   ```sh
   EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY=
   CLERK_PUBLISHABLE_KEY=
   NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY=
   CLERK_SECRET_KEY=
   ```

   Clerk CLI writes `CLERK_PUBLISHABLE_KEY`. Expo client code needs `EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY`, so mirror the publishable key into `apps/mobile/.env.local` with that name when testing the mobile app.

7. Create a Prisma Postgres database and fill in:

   ```sh
   DATABASE_URL=
   ```

8. Validate and run the initial Prisma migration:

   ```sh
   pnpm --filter @hcm/db lint
   pnpm --filter @hcm/db db:migrate
   ```

9. Run the apps:

   ```sh
   pnpm --filter @hcm/mobile dev
   pnpm --filter @hcm/web dev
   ```

   The mobile app targets Expo SDK 56. Expo Go must also support SDK 56; if Expo Go reports an older supported SDK, install the SDK 56 client from Expo's official Expo Go download page or use a development build.

## Expected missing-env behavior

The Expo app intentionally renders a clear setup screen when no Clerk publishable key is available.

The web app currently exposes only public Profile Share placeholder routes. Clerk is installed for future shared auth/provider compatibility, but the v1 web app does not create a Stylist management surface.

## Smoke verification

Before installing dependencies, this dependency-free check verifies the scaffolded protected mobile route and public web route exist:

```sh
pnpm smoke
```

After dependencies are installed, use:

```sh
pnpm lint
pnpm --filter @hcm/web build
```
