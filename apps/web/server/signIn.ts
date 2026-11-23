import { createClerkClient } from "@clerk/nextjs/server";
import { annotate, recordError } from "@hcm/telemetry";

// The request headers in which a sign-in arrives: the app's token, and a browser's session.
const signInHeaders = ["authorization", "cookie"];

/** What reads a request's sign-in: Clerk's client, or a stand-in in tests. */
type SignInChecker = Pick<ReturnType<typeof createClerkClient>, "authenticateRequest">;

let clerk: SignInChecker | undefined;

/**
 * Clerk's client, made from the environment the first time a request needs it. Not the one
 * `clerkClient()` gives: that takes its keys from a header of the request (`x-clerk-request-data`)
 * that Clerk's middleware writes, and without the proxy the header is whatever the caller sent.
 */
function clerkFromEnvironment(): SignInChecker {
  clerk ??= createClerkClient({
    secretKey: process.env.CLERK_SECRET_KEY,
    publishableKey: process.env.NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY,
    // When set (the instance's public key), a token is checked without fetching Clerk's keys first.
    jwtKey: process.env.CLERK_JWT_KEY || undefined
  });

  return clerk;
}

/**
 * The Clerk user who sent a request to the tRPC route, or null when it carries no valid sign-in.
 *
 * The route reads the sign-in itself, because proxy.ts does not run for `/api/trpc`: on Vercel
 * the proxy is a function of its own, and an app request that went through it waited for two
 * functions (two cold starts on a new instance) before it was answered (docs/adr/0010). The check
 * is the one the proxy's `clerkMiddleware` made, with the same keys: the session token in the
 * `Authorization` header, which the app sends, else a browser's session cookie.
 */
export async function signedInUserId(request: Request, client: () => SignInChecker = clerkFromEnvironment): Promise<string | null> {
  // Whether a sign-in came with the request, never what it says.
  annotate({
    "auth.has_authorization_header": request.headers.has("authorization"),
    "auth.has_cookie_header": request.headers.has("cookie")
  });

  try {
    const state = await client().authenticateRequest(request);
    const userId = state.toAuth()?.userId ?? null;

    if (userId === null) {
      // Clerk's code for why the request is not signed in (a missing, expired or refused token).
      annotate({ "auth.signed_out_reason": state.reason?.slice(0, 80) });
    }

    return userId;
  } catch (error) {
    // Clerk refuses a sign-in token that is wrong, also one it cannot decode at all (`Bearer
    // a.b.c`), though in the proxy its middleware failed on such a token instead. Should this
    // check ever fail on the sign-in, the request is answered as one without it: the API refuses
    // it with 401 like any other token that is not valid. But only when Clerk can read the
    // request without its sign-in: a failure of Clerk's own (keys missing) is not hidden as a
    // refusal, which the app would take for a sign-in that ran out.
    if (!signInHeaders.some((name) => request.headers.has(name))) {
      throw error;
    }

    const headers = new Headers(request.headers);

    signInHeaders.forEach((name) => headers.delete(name));
    await client().authenticateRequest(new Request(request.url, { method: request.method, headers }));

    // Recorded, so that how often a sign-in Clerk fails on arrives can be counted.
    annotate({ "auth.sign_in_fallback": true });
    recordError(error, { handled: true, reason: "clerk_token_undecodable" });

    return null;
  }
}
