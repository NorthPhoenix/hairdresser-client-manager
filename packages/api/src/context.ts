import { db, type Prisma } from "@hcm/db";
import type { AppVersion } from "./appReleases";
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
  /**
   * The version of the Android app that sent the request (`appVersionFromHeaders`). Left out or
   * null, as for every app before 1.4.0: the Stylist's last seen app version is not written.
   */
  appVersion?: AppVersion | null;
  /**
   * Where the Android app is downloaded from (APP_DOWNLOAD_URL), which `app.releases` hands to
   * the app for its "Update" button. Left out or empty: the app offers no download.
   */
  appDownloadUrl?: string | null;
};

/**
 * What a request tells about the app that sent it. Optional in the context too: a context made
 * without it (a test, a host that does not read the headers) is one of no app version and no
 * download address.
 */
type AppRequestContext = {
  appVersion?: AppVersion | null;
  appDownloadUrl?: string | null;
};

export async function createTRPCContext({
  auth,
  photoOrigins = [],
  deleteStoredFiles = async () => undefined,
  accountName = async () => null,
  readSharePhoto = async () => null,
  appVersion = null,
  appDownloadUrl = null
}: CreateTRPCContextOptions) {
  const clerkAuth = await auth();
  const app: AppRequestContext = {
    appVersion,
    appDownloadUrl: appDownloadUrl?.trim() || null
  };

  return {
    db,
    clerkAuth,
    photoOrigins,
    deleteStoredFiles,
    accountName,
    readSharePhoto,
    ...app
  };
}

export type TRPCContext = Awaited<ReturnType<typeof createTRPCContext>>;

/** The database client, or a transaction opened on it. */
export type Db = Prisma.TransactionClient;
