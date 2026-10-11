import { buildShareImage } from "@hcm/api";
import { requestedLocale } from "@hcm/shared";
import { loadProfileShare, readSharePhoto, unavailableShareResponse } from "../../../../server/profileShare";

export async function GET(request: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const lang = new URL(request.url).searchParams.get("lang");
  const share = await loadProfileShare(token);

  if (!share) {
    return unavailableShareResponse(token, lang);
  }

  const locale = requestedLocale(lang) ?? share.language;
  const svg = await buildShareImage(share.content, locale, readSharePhoto);

  return new Response(svg, {
    headers: {
      "Cache-Control": "private, no-store",
      "Content-Type": "image/svg+xml; charset=utf-8"
    }
  });
}
