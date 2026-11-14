import { annotate, increment, recordError, traced } from "@hcm/telemetry";
import type { Db } from "./context";

/**
 * The origins that serve the files of one UploadThing app: `https://<app id>.ufs.sh`. The app id is
 * read from the app's token (`UPLOADTHING_TOKEN`), which is base64 of
 * `{ apiKey, appId, regions }`. Without a usable token there is no photo storage and no origin.
 */
export function getUploadThingOrigins(token: string | undefined) {
  try {
    const { appId } = JSON.parse(atob(token ?? "")) as { appId?: unknown };

    return typeof appId === "string" && /^[a-z0-9-]+$/i.test(appId) ? [new URL(`https://${appId}.ufs.sh`).origin] : [];
  } catch {
    return [];
  }
}

/**
 * Whether a URL is the stored file `fileKey` in the photo storage: `<origin>/.../<fileKey>`, which
 * is what the upload route answers with. The app loads the URL of an Appointment Photo as an image,
 * so nothing else may be stored as one. `origins` are the storage origins of this server.
 */
export function isStoredFileUrl(value: string, fileKey: string, origins: string[]) {
  let url: URL;

  try {
    url = new URL(value);
  } catch {
    return false;
  }

  return fileKey !== "" && origins.includes(url.origin) && url.pathname.endsWith(`/${encodeURIComponent(fileKey)}`);
}

/** Whether a URL is a file in the photo storage of this server, whose origins are `origins`. */
export function isInPhotoStorage(value: string, origins: string[]) {
  try {
    return origins.includes(new URL(value).origin);
  } catch {
    return false;
  }
}

/**
 * Names the stored files of Appointment Photos that were just deleted, so that they are deleted
 * from photo storage once the transaction has committed (`stylistTransactionProcedure`). Call it
 * after the records are gone: a file that another Appointment Photo still names is kept, which can
 * only be a photo recorded before one stored file was one Appointment Photo.
 */
export async function markFilesUnused(ctx: { db: Db; unusedFiles: string[] }, photos: { fileKey: string | null }[]) {
  const fileKeys = [...new Set(photos.flatMap((photo) => photo.fileKey || []))];

  if (fileKeys.length === 0) {
    return;
  }

  const stillNamed = await ctx.db.appointmentPhoto.findMany({
    where: {
      fileKey: {
        in: fileKeys
      }
    },
    select: {
      fileKey: true
    }
  });

  const unused = fileKeys.filter((fileKey) => !stillNamed.some((photo) => photo.fileKey === fileKey));

  ctx.unusedFiles.push(...unused);
  // Added up, as a request may delete several Appointments. A kept file is one that another
  // Appointment Photo still names, which only a photo from before one file was one photo can do.
  increment("photo.unused_file_count", unused.length);
  increment("photo.kept_file_count", fileKeys.length - unused.length);
}

// How long the Stylist's action waits for photo storage before it answers without it.
const storageDeleteTimeoutMs = 5_000;

/**
 * Deletes files that no Appointment Photo names any more from photo storage. The photos are
 * already gone for the Stylist, so a storage failure does not fail the action: it is recorded in
 * the request's event, and those files stay in storage until someone deletes them there.
 */
export async function deleteUnusedFiles(deleteStoredFiles: (fileKeys: string[]) => Promise<unknown>, fileKeys: string[]) {
  if (fileKeys.length === 0) {
    return;
  }

  let timer: ReturnType<typeof setTimeout> | undefined;
  let timedOut = false;

  annotate({ "photo_storage.delete_file_count": fileKeys.length });

  // A span of its own: the Stylist's action waits for this call, up to the timeout.
  await traced("photo_storage.delete_files", async () => {
    try {
      await Promise.race([
        deleteStoredFiles(fileKeys),
        new Promise((_, reject) => {
          timer = setTimeout(() => {
            timedOut = true;
            reject(new Error("Photo storage did not answer in time."));
          }, storageDeleteTimeoutMs);
        })
      ]);
      annotate({ "photo_storage.delete_result": "deleted" });
    } catch (error) {
      // The file keys are not recorded: a key is the address of a Client's photo, and events are
      // kept for good. The count says how many files stay behind in storage.
      annotate({ "photo_storage.delete_result": timedOut ? "timed_out" : "failed" });
      recordError(error, { handled: true, reason: timedOut ? "photo_storage_delete_timed_out" : "photo_storage_delete_failed" });
    } finally {
      clearTimeout(timer);
    }
  });
}
