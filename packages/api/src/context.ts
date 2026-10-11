import { db, type Prisma } from "@hcm/db";

export type ClerkAuth = {
  userId: string | null;
};

export type CreateTRPCContextOptions = {
  auth: () => Promise<ClerkAuth>;
  /**
   * The origins of this server's photo storage: the only places a stored Appointment Photo may
   * point at. The web app names its UploadThing app (`getUploadThingOrigins`), the dev backend the
   * storage service it emulates. Left out: no stored photo is accepted.
   */
  photoOrigins?: string[];
  /**
   * Deletes files from this server's photo storage, by file key. It is called with the files of
   * deleted Appointment Photos; see `deleteUnusedFiles`. Left out: no file is deleted.
   */
  deleteStoredFiles?: (fileKeys: string[]) => Promise<unknown>;
};

export async function createTRPCContext({
  auth,
  photoOrigins = [],
  deleteStoredFiles = async () => undefined
}: CreateTRPCContextOptions) {
  const clerkAuth = await auth();

  return {
    db,
    clerkAuth,
    photoOrigins,
    deleteStoredFiles
  };
}

export type TRPCContext = Awaited<ReturnType<typeof createTRPCContext>>;

/** The database client, or a transaction opened on it. */
export type Db = Prisma.TransactionClient;
