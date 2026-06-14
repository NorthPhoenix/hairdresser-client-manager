import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "@prisma/client";
import { config } from "dotenv";

config({ path: new URL("../.env", import.meta.url) });

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
