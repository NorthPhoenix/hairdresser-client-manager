// First: sets up telemetry before the first request (server/telemetry.ts says why).
import "./server/telemetry";
import { NextResponse, type NextRequest } from "next/server";
import { defaultLocale, requestedLocale } from "@hcm/shared";
import { annotate, recordError } from "@hcm/telemetry";
import { lookUpSharePage, unavailableLanguageHeader, unknownAddress } from "./server/shareLink";

// The status of what the proxy answers, for the request's event: Next.js reports 200 for the
// proxy whatever it answers (docs/observability.md, `http.status_code`).
function answering<T extends Response>(response: T, status: number = response.status): T {
  annotate({ "http.status_code": status });

  return response;
}

// What is done with a page's request that is not refused.
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

    // The address it is passed on as has no page, which is answered with 404.
    return answering(
      NextResponse.rewrite(new URL(unknownAddress, request.url), {
        request: {
          headers
        }
      }),
      404
    );
  }

  annotate({ "proxy.decision": "passed_on" });

  return answering(
    NextResponse.next({
      request: {
        headers
      }
    })
  );
}

/**
 * Runs before a page: the home page, a Profile Share page and any address this server does not
 * know. Never before the API, nor before a Profile Share's picture and photos, which answer for
 * themselves (see `config`), and it reads no sign-in: no page needs one, and each API route reads
 * its own (server/signIn.ts).
 */
export default async function proxy(request: NextRequest) {
  // A page answers only reading. Without this the framework draws a page for any method. (TRACE
  // never gets here: see instrumentation.ts.)
  if (request.method !== "GET" && request.method !== "HEAD") {
    annotate({ "proxy.decision": "method_refused", "proxy.refused_method": request.method });
    recordError(new Error("Only the API takes anything but reading."), { expected: true, code: "METHOD_NOT_ALLOWED", reason: "method_not_allowed" });

    return answering(
      new NextResponse(null, {
        status: 405,
        headers: {
          Allow: "GET, HEAD"
        }
      })
    );
  }

  return passOn(request);
}

export const config = {
  // On Vercel this proxy is a function of its own, which Vercel deploys apart from the routes and
  // runs in a region of its choosing, before the route's function (docs/adr/0010). So it runs only
  // where nothing else can do its work: before a page, for the method it answers and for a
  // Profile Share link that shows nothing. Every address but the framework's own files, those
  // with a dot in them, which are files, the API, and what is under a Profile Share's address
  // (its picture and photos). The address of a Profile Share page is named too: a link that was
  // copied with the full stop of its sentence has a dot in it and is a link that shows nothing,
  // not a file.
  matcher: ["/((?!_next|api(?:/|$)|profile-shares/|.*\\..*).*)", "/profile-shares/([^/]+)"]
};
