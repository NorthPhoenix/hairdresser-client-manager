import { clerkMiddleware } from "@clerk/nextjs/server";
import { NextRequest, NextResponse, type NextFetchEvent } from "next/server";
import { defaultLocale, requestedLocale } from "@hcm/shared";
import { annotate, recordError } from "@hcm/telemetry";
import { lookUpSharePage, unavailableLanguageHeader, unknownAddress } from "./server/shareLink";

// What is done with a request once Clerk has read who sent it.
async function passOn(request: NextRequest) {
  const { pathname, searchParams } = request.nextUrl;
  // A Profile Share page, not its picture or its photos: those answer for themselves.
  const token = /^\/profile-shares\/([^/]+)$/.exec(pathname)?.[1];
  const sharePage = token === undefined ? null : await lookUpSharePage(token);
  const headers = new Headers(request.headers);

  annotate({ "proxy.is_share_page": token !== undefined });

  // The language in which a Client is told that a link shows nothing: the one the address asks
  // for, else the language of the Client the link was made for (a revoked link is still known),
  // else the primary language.
  const asked = requestedLocale(searchParams.getAll("lang"));
  const language = asked ?? sharePage?.language ?? defaultLocale;

  headers.set(unavailableLanguageHeader, language);
  annotate({
    "proxy.language": language,
    "proxy.language_source": asked ? "query" : sharePage?.language ? "client" : "default",
    "proxy.share_shows_nothing": sharePage?.showsNothing
  });

  // A link that is unknown or revoked is passed on as an address without a page. Only so is its
  // page (app/not-found.tsx) whole in the answer, with status 404: when the share page finds out
  // by itself (it still does, see loadProfileShare) the framework answers 404 with an empty
  // document and leaves it to the browser to draw the page.
  if (sharePage?.showsNothing) {
    annotate({ "proxy.decision": "rewrite_to_unavailable" });

    return NextResponse.rewrite(new URL(unknownAddress, request.url), {
      request: {
        headers
      }
    });
  }

  annotate({ "proxy.decision": "passed_on" });

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
    annotate({ "proxy.decision": "method_refused", "proxy.refused_method": request.method });
    recordError(new Error("Only the API takes anything but reading."), { expected: true, code: "METHOD_NOT_ALLOWED", reason: "method_not_allowed" });

    return new NextResponse(null, {
      status: 405,
      headers: {
        Allow: "GET, HEAD"
      }
    });
  }

  // Made for this request, so that `read` is its own.
  let read = false;

  // Whether a sign-in came with the request, never what it says.
  annotate({
    "proxy.has_authorization_header": request.headers.has("authorization"),
    "proxy.has_cookie_header": request.headers.has("cookie")
  });

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

    // Recorded, so that how often a token Clerk cannot decode arrives (and from whom: its headers
    // are named in `proxy.has_*`) can be counted.
    annotate({ "proxy.sign_in_fallback": true });
    recordError(error, { handled: true, reason: "clerk_token_undecodable" });

    const headers = new Headers(request.headers);

    signInHeaders.forEach((name) => headers.delete(name));

    return answer(new NextRequest(request.url, { method: request.method, headers }), event);
  }
}

export const config = {
  // Every address but the framework's own files and those with a dot in them, which are files.
  // The addresses of Profile Shares are named too: a link that was copied with the full stop of
  // its sentence has a dot in it and is a link that shows nothing, not a file.
  matcher: ["/((?!_next|.*\\..*).*)", "/api/trpc/(.*)", "/profile-shares/(.*)"]
};
