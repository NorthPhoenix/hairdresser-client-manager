import { db, type Prisma } from "@hcm/db";
import type { SharePhotoReader } from "./shareImage";

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
  /**
   * The name on a Stylist's account at the sign-in provider, by Clerk user id, or null when the
   * account has none. It is shown to Clients on Profile Shares and Share Images, so it is the
   * name and never the email address. Left out: no Stylist name is shown.
   */
  accountName?: (clerkId: string) => Promise<string | null>;
  /**
   * Reads one stored Appointment Photo for a Share Image, see `SharePhotoReader`. It is only
   * called with URLs in `photoOrigins`. Left out: a Share Image embeds no photo and says so.
   */
  readSharePhoto?: SharePhotoReader;
};

export async function createTRPCContext({
  auth,
  photoOrigins = [],
  deleteStoredFiles = async () => undefined,
  accountName = async () => null,
  readSharePhoto = async () => null
}: CreateTRPCContextOptions) {
  const clerkAuth = await auth();

  return {
    db,
    clerkAuth,
    photoOrigins,
    deleteStoredFiles,
    accountName,
    readSharePhoto
  };
}

export type TRPCContext = Awaited<ReturnType<typeof createTRPCContext>>;

/** The database client, or a transaction opened on it. */
export type Db = Prisma.TransactionClient;
