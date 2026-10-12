import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "@prisma/client";
import { config } from "dotenv";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// Local development keeps the database address in packages/db/.env. A host such as Vercel sets it
// in the environment and has no such file, which is fine: a missing file is skipped.
// The first path is put together at run time on purpose. Written as
// `new URL("../.env", import.meta.url)`, a bundler takes it for a file it has to find while
// building, and the build fails where there is none.
config({ path: join(dirname(fileURLToPath(import.meta.url)), "..", ".env") });
config({ path: "packages/db/.env" });
config({ path: "../../packages/db/.env" });

const globalForPrisma = globalThis as unknown as {
  hcmPrisma?: PrismaClient;
};

const databaseUrl = process.env.DATABASE_URL;

if (!databaseUrl) {
  throw new Error("DATABASE_URL is required. Set it in packages/db/.env.");
}

const adapter = new PrismaPg({
  connectionString: databaseUrl
});

export const db = globalForPrisma.hcmPrisma ?? new PrismaClient({ adapter });

if (process.env.NODE_ENV !== "production") {
  globalForPrisma.hcmPrisma = db;
}

export type { Language, Prisma, Stylist } from "@prisma/client";
