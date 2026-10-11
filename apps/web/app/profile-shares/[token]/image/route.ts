import { buildShareImage } from "@hcm/api";
import { normalizeLocale } from "@hcm/shared";
import { loadProfileShare, readSharePhoto } from "../../../../server/profileShare";

export async function GET(request: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const url = new URL(request.url);
  const share = await loadProfileShare(token);

  if (!share) {
    return new Response("Profile Share is unavailable.", {
      status: 404
    });
  }

  const locale = url.searchParams.get("lang") ? normalizeLocale(url.searchParams.get("lang") ?? undefined) : share.language;
  const svg = await buildShareImage(share.content, locale, readSharePhoto);

  return new Response(svg, {
    headers: {
      "Cache-Control": "private, no-store",
      "Content-Type": "image/svg+xml; charset=utf-8"
    }
  });
}
