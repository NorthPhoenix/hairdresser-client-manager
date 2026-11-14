import { buildShareImage } from "@hcm/api";
import { requestedLocale } from "@hcm/shared";
import { annotate } from "@hcm/telemetry";
import { loadProfileShare, readSharePhoto, unavailableShareResponse } from "../../../../server/profileShare";

export async function GET(request: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const lang = new URL(request.url).searchParams.get("lang");
  const share = await loadProfileShare(token);

  annotate({ "profile_share.view": "image" });

  if (!share) {
    return unavailableShareResponse(token, lang);
  }

  const asked = requestedLocale(lang);
  const locale = asked ?? share.language;

  annotate({ "profile_share.language": locale, "profile_share.language_source": asked ? "query" : "client" });

  const svg = await buildShareImage(share.content, locale, readSharePhoto);

  annotate({ "profile_share.image_served": true });

  return new Response(svg, {
    headers: {
      "Cache-Control": "private, no-store",
      "Content-Type": "image/svg+xml; charset=utf-8"
    }
  });
}
