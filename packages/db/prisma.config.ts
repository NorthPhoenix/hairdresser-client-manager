import "dotenv/config";
import { defineConfig } from "prisma/config";

export default defineConfig({
  schema: "prisma/schema.prisma",
  datasource: {
    // Schema pushes need a direct connection. DATABASE_URL may be a pooled one (Neon's "-pooler"
    // host), which is right for the app and wrong here, so the direct one wins when it is set.
    // Read without env(), which fails when the variable is missing: generating the client and
    // checking the schema need no database, so a fresh checkout builds and lints without one.
    // A command that does need it, such as db push, still says the address is missing.
    url: process.env.DATABASE_URL_UNPOOLED || process.env.DATABASE_URL
  }
});
