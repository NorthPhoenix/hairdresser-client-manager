CREATE TYPE "Language" AS ENUM ('ru', 'en');

CREATE TABLE "Stylist" (
  "id" TEXT NOT NULL,
  "clerkId" TEXT NOT NULL,
  "language" "Language" NOT NULL DEFAULT 'ru',
  "timezone" TEXT NOT NULL,
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "Stylist_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "Client" (
  "id" TEXT NOT NULL,
  "stylistId" TEXT NOT NULL,
  "name" TEXT NOT NULL,
  "language" "Language" NOT NULL DEFAULT 'ru',
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "Client_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "ProfileShare" (
  "id" TEXT NOT NULL,
  "clientId" TEXT NOT NULL,
  "token" TEXT NOT NULL,
  "language" "Language" NOT NULL DEFAULT 'ru',
  "revokedAt" TIMESTAMP(3),
  "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "ProfileShare_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Stylist_clerkId_key" ON "Stylist"("clerkId");
CREATE UNIQUE INDEX "ProfileShare_token_key" ON "ProfileShare"("token");

ALTER TABLE "Client" ADD CONSTRAINT "Client_stylistId_fkey" FOREIGN KEY ("stylistId") REFERENCES "Stylist"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "ProfileShare" ADD CONSTRAINT "ProfileShare_clientId_fkey" FOREIGN KEY ("clientId") REFERENCES "Client"("id") ON DELETE CASCADE ON UPDATE CASCADE;
