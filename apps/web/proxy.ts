import { clerkMiddleware } from "@clerk/nextjs/server";
import { NextResponse } from "next/server";
import { defaultLocale, requestedLocale } from "@hcm/shared";
import { findShareLink, unavailableLanguageHeader, unknownAddress } from "./server/shareLink";

export default clerkMiddleware(async (_auth, request) => {
  const { pathname, searchParams } = request.nextUrl;
  // A Profile Share page, not its picture or its photos: those answer for themselves.
  const token = /^\/profile-shares\/([^/]+)$/.exec(pathname)?.[1];
  const link = token === undefined ? null : await findShareLink(token);
  const headers = new Headers(request.headers);

  // The language in which a Client is told that a link shows nothing: the one the address asks
  // for, else the language of the Client the link was made for (a revoked link is still known),
  // else the primary language.
  headers.set(unavailableLanguageHeader, requestedLocale(searchParams.getAll("lang")) ?? link?.client.language ?? defaultLocale);

  // A link that is unknown or revoked is passed on as an address without a page. Only so is its
  // page (app/not-found.tsx) whole in the answer, with status 404: when the share page finds out
  // by itself (it still does, see loadProfileShare) the framework answers 404 with an empty
  // document and leaves it to the browser to draw the page.
  if (token !== undefined && (!link || link.revokedAt !== null)) {
    return NextResponse.rewrite(new URL(unknownAddress, request.url), {
      request: {
        headers
      }
    });
  }

  return NextResponse.next({
    request: {
      headers
    }
  });
});

export const config = {
  matcher: ["/((?!_next|.*\\..*).*)", "/api/trpc/(.*)"]
};
