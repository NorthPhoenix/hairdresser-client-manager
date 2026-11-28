import { createClerkClient } from "@clerk/nextjs/server";
import { annotate, recordError } from "@hcm/telemetry";

// The request headers in which a sign-in arrives: the app's token, and a browser's session.
const signInHeaders = ["authorization", "cookie"];

// Clerk's codes for a token that the public key does not check: a signature that does not fit
// it, or a key that cannot be used. Clerk's keys may have changed since the key was set.
const jwtKeyRefusals = ["token-invalid-signature", "token-verification-failed"];

// How many key ids a server instance checks with the public key before one of them has checked a
// token. Clerk keeps the key it makes from the public key under each token's key id, which the
// sender chooses, and keeps all of them for as long as it does not fetch its own keys again:
// without a limit, tokens of made-up key ids would grow what it keeps.
const maxTriedKeyIds = 3;

type ClerkClient = ReturnType<typeof createClerkClient>;

/** What reads a request's sign-in: Clerk's client, or a stand-in in tests. */
type SignInChecker = Pick<ClerkClient, "authenticateRequest">;

type SignInState = Awaited<ReturnType<SignInChecker["authenticateRequest"]>>;

let clerk: ClerkClient | undefined;

// The key ids this server instance has checked tokens of with the public key, and the one whose
// token it checked: from then on only tokens of that key id are, and one of another is Clerk's
// keys changing (see `checked`).
const triedKeyIds = new Set<string>();
let checkedKeyId: string | undefined;
// Whether this server instance found the public key out of date. It is recorded once, and from
// then on the instance checks every token with Clerk's API keys: tokens signed with a key Clerk
// no longer signs with (say, after a leak) are no longer let in here by the public key.
let keyOutOfDate = false;

/**
 * The public key of the Clerk instance that signs the tokens (`CLERK_JWT_KEY`: the PEM of the
 * Clerk dashboard's API keys, "JWT public key"), or undefined when it is not set. A key stored
 * with `\n` for its line breaks is read with line breaks.
 */
function jwtKey(): string | undefined {
  return process.env.CLERK_JWT_KEY?.trim().replace(/\\n/g, "\n") || undefined;
}

/**
 * Who issues the tokens of the instance whose keys are set: `https://` and the instance's Frontend
 * API, which its publishable key holds (`pk_live_` and the host, base64, ending in `$`).
 */
function tokenIssuer(): string | undefined {
  const encoded = process.env.NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY?.trim().replace(/^pk_(test|live)_/, "");
  const host = encoded ? Buffer.from(encoded, "base64").toString("utf8").replace(/\$$/, "") : "";

  return /^[a-z0-9.-]+$/i.test(host) ? `https://${host}` : undefined;
}

/**
 * The token in the request's `Authorization` header, as Clerk reads it there when it comes after
 * `Bearer` (Clerk's `parseAuthorizationHeader`), or undefined.
 */
function bearerToken(request: Request): string | undefined {
  const [scheme, token] = (request.headers.get("authorization") ?? "").split(" ", 2);

  return scheme === "Bearer" && token ? token : undefined;
}

/** The key id of [token], from its header, or undefined when it cannot be read. */
function keyIdOf(token: string): string | undefined {
  const header = token.split(".")[0];

  try {
    const kid: unknown = header ? JSON.parse(Buffer.from(header, "base64url").toString("utf8"))?.kid : undefined;

    return typeof kid === "string" && kid.length > 0 && kid.length <= 100 ? kid : undefined;
  } catch {
    return undefined;
  }
}

/** The issuer a signed-in [state] names, from its token. */
function issuerOf(state: SignInState): unknown {
  return (state.toAuth() as { sessionClaims?: { iss?: unknown } | null } | null)?.sessionClaims?.iss;
}

/**
 * Why a token of key id [keyId] is not checked with the public key [key] for the instance of
 * [issuer], or null when it is.
 */
function skippedBecause(key: string | undefined, issuer: string | undefined, keyId: string | undefined): string | null {
  if (key === undefined) {
    return "no_jwt_key";
  }
  if (issuer === undefined) {
    return "no_instance";
  }
  if (keyOutOfDate) {
    return "out_of_date";
  }
  if (keyId === undefined) {
    return "no_kid";
  }
  if (checkedKeyId !== undefined) {
    return keyId === checkedKeyId ? null : "other_kid";
  }

  return triedKeyIds.has(keyId) || triedKeyIds.size < maxTriedKeyIds ? null : "kid_limit";
}

/**
 * Clerk's check of [request]. Without the public key Clerk checks a token with the signing keys
 * it fetches from its API (`GET /v1/jwks`), which it keeps for 5 minutes in each server instance:
 * the first request on a new instance waits for that call before it is checked (140-300 ms in
 * production on 2026-11-26/27, on every app open after a pause), and so does the first one
 * after 5 minutes. With the key (Clerk's `jwtKey`) Clerk checks the token without a call. It
 * does so for a token in the `Authorization` header, which the app sends; a browser's session
 * cookie is checked as before.
 *
 * A token the key does not check is checked again with the keys from Clerk's API, so a key that
 * is out of date costs that call, never a sign-in. A token the key checks has to be one of the
 * instance whose keys are set beside it (its issuer): a key of another instance (say, the
 * development one) lets none of that instance's tokens in. Once the key is found out of date, the
 * server instance checks every token with Clerk's API keys (see `keyOutOfDate`).
 */
async function checked(request: Request, client: () => SignInChecker): Promise<SignInState> {
  const token = bearerToken(request);

  if (token === undefined) {
    return client().authenticateRequest(request);
  }

  const key = jwtKey();
  const issuer = tokenIssuer();
  const keyId = keyIdOf(token);
  const skipped = skippedBecause(key, issuer, keyId);
  let refusal: string | undefined;

  if (skipped === null && key !== undefined && keyId !== undefined) {
    let state: SignInState | undefined;

    triedKeyIds.add(keyId);

    try {
      state = await client().authenticateRequest(request, { jwtKey: key });
    } catch {
      // Checked again below: whether the key or the token is at fault, Clerk's API keys tell.
    }

    const signedIn = state?.toAuth()?.userId != null;

    if (!state) {
      refusal = "failed";
    } else if (jwtKeyRefusals.includes(state.reason ?? "")) {
      refusal = state.reason ?? undefined;
    } else if (signedIn && issuerOf(state) !== issuer) {
      refusal = "other_issuer";
    } else {
      annotate({ "auth.checked_with": "local" });
      if (signedIn) {
        checkedKeyId = keyId;
      }

      return state;
    }

    annotate({ "auth.local_check_refused": refusal });
  } else if (skipped !== null && skipped !== "no_jwt_key") {
    annotate({ "auth.local_check_skipped": skipped });
  }

  const state = await client().authenticateRequest(request);

  annotate({ "auth.checked_with": "clerk_api" });

  // Clerk's own keys check a token that the public key did not, or one of another key id than the
  // one the key checked: the key is not the one Clerk signs this instance's tokens with now. A
  // token that neither checks is only a token that is not valid.
  if ((refusal !== undefined || skipped === "other_kid") && state.toAuth()?.userId && !keyOutOfDate) {
    keyOutOfDate = true;
    recordError(new Error(`CLERK_JWT_KEY does not check a token that Clerk's signing keys check (${refusal ?? skipped}).`), {
      handled: true,
      reason: "clerk_jwt_key_out_of_date"
    });
  }

  return state;
}

/**
 * Clerk's client, made the first time this server needs it, from the settings that Clerk's
 * Next.js SDK reads: the keys, and the address and version of Clerk's API when they are set. The
 * one client of this server, for checking a sign-in and for asking Clerk's API (an account's
 * name). Not the client `clerkClient()` gives, which takes its settings from the request header
 * `x-clerk-request-data` whenever that decrypts: the caller can send it, and a development server
 * also decrypts it with a key that is public. Its settings are this server's only.
 */
export function clerkFromEnvironment(): ClerkClient {
  clerk ??= createClerkClient({
    secretKey: process.env.CLERK_SECRET_KEY,
    publishableKey: process.env.NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY,
    apiUrl: process.env.CLERK_API_URL || undefined,
    apiVersion: process.env.CLERK_API_VERSION || undefined
  });

  return clerk;
}

/**
 * The Clerk user who sent a request to the API (the tRPC route and the upload route), or null
 * when it carries no valid sign-in.
 *
 * Each route reads the sign-in itself: proxy.ts does not run for `/api/` and no longer loads
 * Clerk at all. On Vercel the proxy is a function of its own, deployed apart from the routes, and
 * an app request that went through it waited for two functions (two cold starts on a new
 * instance) in two regions before it was answered (docs/adr/0010). The check is the one the
 * proxy's `clerkMiddleware` made, with the same keys: the session token in the `Authorization`
 * header, which the app sends, else a browser's session cookie. With `CLERK_JWT_KEY` set, the
 * token is checked without a call to Clerk (see `checked`).
 */
export async function signedInUserId(request: Request, client: () => SignInChecker = clerkFromEnvironment): Promise<string | null> {
  // Whether a sign-in came with the request, never what it says.
  annotate({
    "auth.has_authorization_header": request.headers.has("authorization"),
    "auth.has_cookie_header": request.headers.has("cookie")
  });

  try {
    const state = await checked(request, client);
    const userId = state.toAuth()?.userId ?? null;

    if (userId === null) {
      // Clerk's code for why the request is not signed in (a missing, expired or refused token).
      // A session Clerk has not let in yet (one with a task to do first) is signed in to Clerk
      // and has no code: it is named here.
      annotate({ "auth.signed_out_reason": state.reason?.slice(0, 80) ?? (state.isSignedIn ? "session-pending" : undefined) });
    }

    return userId;
  } catch (error) {
    // Clerk refuses a sign-in token that is wrong, also one it cannot decode at all (`Bearer
    // a.b.c`), though its middleware failed on such a token instead. Should this check ever fail
    // on the sign-in, the request is answered as one without it: the API refuses it with 401 like
    // any other token that is not valid. But only when Clerk can read the request without its
    // sign-in: a failure of Clerk's own (keys missing) is not hidden as a refusal, which the app
    // would take for a sign-in that ran out.
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
