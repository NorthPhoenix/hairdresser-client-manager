import { findSharedPhoto } from "@hcm/api";
import { loadProfileShare, openSharePhoto } from "../../../../../server/profileShare";

/**
 * A photo of a Profile Share page, served under the share's own link: it is there only as long as
 * the link works and the Stylist includes the photo, so a revoke takes the photos with it. The
 * address of the stored file never reaches the Client.
 */
export async function GET(request: Request, { params }: { params: Promise<{ token: string; photo: string }> }) {
  const { token, photo: name } = await params;
  const share = await loadProfileShare(token);
  const photo = share && findSharedPhoto(share.content, token, name);

  if (!photo) {
    return new Response("Profile Share is unavailable.", {
      status: 404
    });
  }

  // A stored file never changes, so the photo's name stands for what it shows. The browser may
  // keep the photo, but has to ask before each use: the answer above ends that with the share.
  const headers = {
    "Cache-Control": "private, no-cache",
    ETag: `"${name}"`,
    "X-Content-Type-Options": "nosniff"
  };

  if (request.headers.get("if-none-match") === headers.ETag) {
    return new Response(null, {
      status: 304,
      headers
    });
  }

  const file = await openSharePhoto(photo.url);

  if (!file) {
    return new Response("The photo could not be read.", {
      status: 502
    });
  }

  return new Response(file.body, {
    headers: {
      ...headers,
      "Content-Type": file.type
    }
  });
}
