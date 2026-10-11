import { clerkMiddleware } from "@clerk/nextjs/server";
import { NextRequest, NextResponse, type NextFetchEvent } from "next/server";
import { defaultLocale, requestedLocale } from "@hcm/shared";
import { findShareLink, unavailableLanguageHeader, unknownAddress } from "./server/shareLink";

// What is done with a request once Clerk has read who sent it.
async function passOn(request: NextRequest) {
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
}

// The request headers in which a sign-in arrives: the app's token, and a browser's session.
const signInHeaders = ["authorization", "cookie"];

export default async function proxy(request: NextRequest, event: NextFetchEvent) {
  // Only the API takes anything but reading. Without this the framework draws a page for any
  // method. (TRACE never gets here: see instrumentation.ts.)
  if (!request.nextUrl.pathname.startsWith("/api/") && request.method !== "GET" && request.method !== "HEAD") {
    return new NextResponse(null, {
      status: 405,
      headers: {
        Allow: "GET, HEAD"
      }
    });
  }

  // Made for this request, so that `read` is its own.
  let read = false;
  const answer = clerkMiddleware((_auth, request) => {
    read = true;

    return passOn(request);
  });

  try {
    return await answer(request, event);
  } catch (error) {
    // Clerk refuses a sign-in token that is wrong, but fails on one it cannot decode at all (three
    // parts that are not what a token is made of, as in `Bearer a.b.c`), and the request would end
    // in a server error on every address. Such a request is answered as if it carried no sign-in:
    // the API then refuses it with 401 like any other token that is not valid, and a public page
    // is shown. A failure of ours, or of a request that carries no sign-in, is not hidden.
    if (read || !signInHeaders.some((name) => request.headers.has(name))) {
      throw error;
    }

    const headers = new Headers(request.headers);

    signInHeaders.forEach((name) => headers.delete(name));

    return answer(new NextRequest(request.url, { method: request.method, headers }), event);
  }
}

export const config = {
  matcher: ["/((?!_next|.*\\..*).*)", "/api/trpc/(.*)"]
};
