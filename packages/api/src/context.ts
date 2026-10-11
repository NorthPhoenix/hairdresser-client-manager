import { db, type Prisma } from "@hcm/db";

export type ClerkAuth = {
  userId: string | null;
};

export type CreateTRPCContextOptions = {
  auth: () => Promise<ClerkAuth>;
  /**
   * Origins that hold stored Appointment Photos besides UploadThing's own. Only the dev backend
   * sets it, for the storage service it emulates.
   */
  photoOrigins?: string[];
};

export async function createTRPCContext({ auth, photoOrigins = [] }: CreateTRPCContextOptions) {
  const clerkAuth = await auth();

  return {
    db,
    clerkAuth,
    photoOrigins
  };
}

export type TRPCContext = Awaited<ReturnType<typeof createTRPCContext>>;

/** The database client, or a transaction opened on it. */
export type Db = Prisma.TransactionClient;
