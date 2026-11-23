import { clerkClient } from "@clerk/nextjs/server";
import { annotate, recordError } from "@hcm/telemetry";

// The request headers in which a sign-in arrives: the app's token, and a browser's session.
const signInHeaders = ["authorization", "cookie"];

/** What reads a request's sign-in: Clerk's client, or a stand-in in tests. */
type SignInChecker = Pick<Awaited<ReturnType<typeof clerkClient>>, "authenticateRequest">;

/**
 * The Clerk user who sent a request to the tRPC route, or null when it carries no valid sign-in.
 *
 * The route reads the sign-in itself, because proxy.ts does not run for `/api/trpc`: on Vercel
 * the proxy is a function of its own, and an app request that went through it waited for two
 * functions (two cold starts on a new instance) before it was answered (docs/adr/0010). The check
 * is the one the proxy's `clerkMiddleware` made, with the same keys: the session token in the
 * `Authorization` header, which the app sends, else a browser's session cookie.
 */
export async function signedInUserId(request: Request, clerk: () => Promise<SignInChecker> = clerkClient): Promise<string | null> {
  // Whether a sign-in came with the request, never what it says.
  annotate({ "auth.has_authorization_header": request.headers.has("authorization") });

  try {
    const state = await (await clerk()).authenticateRequest(request);
    const userId = state.toAuth()?.userId ?? null;

    if (userId === null) {
      // Clerk's code for why the request is not signed in (a missing, expired or refused token).
      annotate({ "auth.signed_out_reason": state.reason?.slice(0, 80) });
    }

    return userId;
  } catch (error) {
    // Clerk refuses a sign-in token that is wrong, also one it cannot decode at all (`Bearer
    // a.b.c`), though in the proxy its middleware failed on such a token instead. Should this
    // check ever fail so, the request is answered as one without a sign-in: the API refuses it
    // with 401 like any other token that is not valid. A failure of a request that carries no
    // sign-in is not hidden.
    if (!signInHeaders.some((name) => request.headers.has(name))) {
      throw error;
    }

    // Recorded, so that how often a token Clerk cannot decode arrives can be counted.
    annotate({ "auth.sign_in_fallback": true });
    recordError(error, { handled: true, reason: "clerk_token_undecodable" });

    return null;
  }
}
