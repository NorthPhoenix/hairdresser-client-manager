import { db, type Prisma } from "@hcm/db";

export type ClerkAuth = {
  userId: string | null;
};

export type CreateTRPCContextOptions = {
  auth: () => Promise<ClerkAuth>;
};

export async function createTRPCContext({ auth }: CreateTRPCContextOptions) {
  const clerkAuth = await auth();

  return {
    db,
    clerkAuth
  };
}

export type TRPCContext = Awaited<ReturnType<typeof createTRPCContext>>;

/** The database client, or a transaction opened on it. */
export type Db = Prisma.TransactionClient;
