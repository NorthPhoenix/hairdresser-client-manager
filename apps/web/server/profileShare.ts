import { clerkClient } from "@clerk/nextjs/server";
import {
  getUploadThingOrigins,
  loadProfileShareContent,
  resolveProfileShareOptions,
  type SharePhotoImage
} from "@hcm/api";
import { db } from "@hcm/db";

// Appointment Photos are stored by /api/uploadthing, in the UploadThing app of the same token.
export const photoOrigins = getUploadThingOrigins(process.env.UPLOADTHING_TOKEN);

// How long a share waits for the sign-in provider, and how long a name it answered is reused: a
// Profile Share page is public, and not every view of it should ask the provider.
const accountNameTimeoutMs = 3_000;
const accountNameLifetimeMs = 60_000;
const accountNames = new Map<string, { name: string | null; until: number }>();

/**
 * The name on a Stylist's Clerk account: its first and last name, which is what Clients see as
 * the Stylist's identity. Null for an account without a name. The email address is a personal
 * contact detail and is never used in its place.
 */
export async function accountName(clerkId: string) {
  const known = accountNames.get(clerkId);

  if (known && known.until > Date.now()) {
    return known.name;
  }

  let timer: ReturnType<typeof setTimeout> | undefined;

  try {
    const clerk = await clerkClient();
    const user = await Promise.race([
      clerk.users.getUser(clerkId),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error("The sign-in provider did not answer in time.")), accountNameTimeoutMs);
      })
    ]);
    const name = [user.firstName, user.lastName].filter(Boolean).join(" ").trim() || null;

    if (accountNames.size >= 1_000) {
      accountNames.clear();
    }

    accountNames.set(clerkId, { name, until: Date.now() + accountNameLifetimeMs });

    return name;
  } finally {
    clearTimeout(timer);
  }
}

const photoReadTimeoutMs = 8_000;
// The upload route takes files of up to 8 MB.
const photoMaxBytes = 16 * 1024 * 1024;
// A photo column of a Share Image is 476 px wide.
const photoMaxSide = 720;
// A stored file never changes, so a photo that was read once is kept for the next picture: the
// same Share Image is built again for its other language, and anyone with the link can ask for it.
const readPhotos = new Map<string, SharePhotoImage>();
const readPhotosKept = 60;

/**
 * Reads a stored Appointment Photo for a Share Image: turned upright as the camera recorded it
 * (the app's SVG renderer ignores that record), made small enough for a picture that holds
 * several of them, and without the camera's own data such as the place it was taken.
 */
export async function readSharePhoto(url: string): Promise<SharePhotoImage | null> {
  const known = readPhotos.get(url);

  if (known) {
    return known;
  }

  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(photoReadTimeoutMs) });

    if (!response.ok || Number(response.headers.get("content-length") ?? 0) > photoMaxBytes) {
      return null;
    }

    // Loaded here, so that a server without this image library still serves everything else.
    const { default: sharp } = await import("sharp");
    const { data, info } = await sharp(Buffer.from(await response.arrayBuffer()))
      .rotate()
      .resize({ width: photoMaxSide, height: photoMaxSide, fit: "inside", withoutEnlargement: true })
      .jpeg({ quality: 78 })
      .toBuffer({ resolveWithObject: true });

    const photo = {
      dataUri: `data:image/jpeg;base64,${data.toString("base64")}`,
      width: info.width,
      height: info.height
    };

    if (readPhotos.size >= readPhotosKept) {
      // The one that was read first.
      readPhotos.delete(readPhotos.keys().next().value ?? "");
    }

    readPhotos.set(url, photo);

    return photo;
  } catch (error) {
    console.error("A photo could not be read for a Share Image:", error);

    return null;
  }
}

/** What the Profile Share behind a link shows. Null: the link is unknown or was revoked. */
export async function loadProfileShare(token: string) {
  const share = await db.profileShare.findFirst({
    where: {
      token,
      revokedAt: null
    },
    include: {
      client: {
        include: {
          stylist: true
        }
      }
    }
  });

  return (
    share && {
      language: share.client.language,
      content: await loadProfileShareContent({ db, photoOrigins, accountName }, share.client, resolveProfileShareOptions(share))
    }
  );
}
