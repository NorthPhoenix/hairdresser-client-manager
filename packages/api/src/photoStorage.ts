// Where UploadThing serves uploaded files: `https://<app id>.ufs.sh/f/<key>`, and `https://utfs.io`
// for files uploaded through its older URLs.
const uploadThingOrigin = /^https:\/\/(utfs\.io|[a-z0-9-]+\.ufs\.sh)$/;

/**
 * Whether a URL points at the photo storage. The app loads the URL of an Appointment Photo as an
 * image, so nothing else may be stored as one. `otherOrigins` is for a storage emulator.
 */
export function isPhotoStorageUrl(value: string, otherOrigins: string[] = []) {
  let origin: string;

  try {
    origin = new URL(value).origin;
  } catch {
    return false;
  }

  return uploadThingOrigin.test(origin) || otherOrigins.includes(origin);
}
