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
