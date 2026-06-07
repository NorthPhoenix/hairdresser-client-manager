import { readFile } from "node:fs/promises";

const checks = [
  {
    file: "apps/mobile/app/_layout.tsx",
    includes: ["ClerkProvider", "EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY", "MissingEnvironmentScreen"]
  },
  {
    file: "apps/mobile/app/(app)/home.tsx",
    includes: ["useUser", "isSignedIn", "protectedHomeTitle"]
  },
  {
    file: "apps/mobile/app/(auth)/sign-in.tsx",
    includes: ["useSignIn", "signIn.password", "emailAddress"]
  },
  {
    file: "apps/mobile/app/(auth)/sign-up.tsx",
    includes: ["useSignUp", "verifications.sendEmailCode", "verifications.verifyEmailCode"]
  },
  {
    file: "apps/web/app/profile-shares/[token]/page.tsx",
    includes: ["ProfileSharePage", "params", "profileSharePlaceholder"]
  },
  {
    file: "packages/shared/src/index.ts",
    includes: ["ru", "en", "profileSharePlaceholder"]
  },
  {
    file: "packages/db/prisma/schema.prisma",
    includes: ["datasource db", "provider = \"postgresql\"", "model ProfileShare"]
  },
  {
    file: "packages/db/prisma.config.ts",
    includes: ["defineConfig", "env(\"DATABASE_URL\")", "prisma/migrations"]
  }
];

for (const check of checks) {
  const source = await readFile(check.file, "utf8");

  for (const expected of check.includes) {
    if (!source.includes(expected)) {
      throw new Error(`${check.file} is missing ${expected}`);
    }
  }
}

console.log("Smoke check passed: mobile auth slice, web Profile Share route, i18n, and Prisma scaffold are present.");
