import "dotenv/config";
import { defineConfig, env } from "prisma/config";

export default defineConfig({
  schema: "prisma/schema.prisma",
  datasource: {
    // Schema pushes need a direct connection. DATABASE_URL may be a pooled one (Neon's "-pooler"
    // host), which is right for the app and wrong here, so the direct one wins when it is set.
    url: process.env.DATABASE_URL_UNPOOLED || env("DATABASE_URL")
  }
});
