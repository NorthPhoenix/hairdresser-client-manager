/// <reference types="node" />

import { createSign, generateKeyPairSync, type KeyObject } from "node:crypto";
import { traceRequest } from "@hcm/telemetry";
import { setUpTelemetry, tearDownTelemetry } from "@hcm/telemetry/testing";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// How the tRPC route of the web app reads the sign-in of a request (apps/web/server/signIn.ts),
// which proxy.ts no longer does for it (docs/adr/0010). The web app has no test runner of its own.

type SignedInUserId = (request: Request, client?: () => unknown) => Promise<string | null>;

async function loadSignIn(): Promise<SignedInUserId> {
  return (await import(/* @vite-ignore */ new URL("../../../apps/web/server/signIn.ts", import.meta.url).href)).signedInUserId;
}

type State = { reason: string | null; toAuth: () => { userId: string | null } | null };

/**
 * A stand-in for Clerk's client whose check of a request ends in [state]. With [failure] it fails
 * instead on a request that carries a sign-in, or on every request when [alsoWithout].
 */
function clerkAnswering(state: State, failure?: { error: Error; alsoWithout?: boolean }) {
  const authenticateRequest = vi.fn(async (request: Request) => {
    const signIn = request.headers.has("authorization") || request.headers.has("cookie");

    if (failure && (signIn || failure.alsoWithout)) {
      throw failure.error;
    }

    return state;
  });

  return { authenticateRequest, client: () => ({ authenticateRequest }) };
}

const signedOut: State = { reason: "session-token-and-uat-missing", toAuth: () => ({ userId: null }) };

function apiRequest(headers: Record<string, string> = {}) {
  return new Request("https://hcm.example/api/trpc/stylist.bootstrap", { headers });
}

let telemetry: ReturnType<typeof setUpTelemetry>;

beforeEach(() => {
  telemetry = setUpTelemetry();
});

afterEach(() => {
  tearDownTelemetry();
  vi.unstubAllEnvs();
  vi.resetModules();
});

/** What [call] ends in, inside a request whose event is then the first of `telemetry.events()`. */
async function inRequest(call: () => Promise<unknown>) {
  let outcome: unknown;

  await traceRequest({ method: "GET", route: "/api/trpc/[trpc]" }, async () => {
    try {
      outcome = await call();
      return { status: 200 };
    } catch (error) {
      outcome = error;
      return { status: 500 };
    }
  });

  return outcome;
}

describe("the sign-in of a tRPC request", () => {
  it("is the Clerk user of a valid token, read from the request itself", async () => {
    const signedInUserId = await loadSignIn();
    const { authenticateRequest, client } = clerkAnswering({ reason: null, toAuth: () => ({ userId: "user_1" }) });
    const request = apiRequest({ authorization: "Bearer header.payload.signature" });

    await expect(inRequest(() => signedInUserId(request, client))).resolves.toBe("user_1");

    expect(authenticateRequest).toHaveBeenCalledWith(request);
    expect(telemetry.events()[0]).toMatchObject({ "auth.has_authorization_header": true, "auth.has_cookie_header": false });
    expect(telemetry.events()[0]).not.toHaveProperty("auth.signed_out_reason");
    // Never the token.
    expect(JSON.stringify(telemetry.events())).not.toContain("header.payload.signature");
  });

  it("is no one when Clerk counts the request as signed out, and the event says why", async () => {
    const signedInUserId = await loadSignIn();
    const { client } = clerkAnswering(signedOut);

    await expect(inRequest(() => signedInUserId(apiRequest(), client))).resolves.toBeNull();

    expect(telemetry.events()[0]).toMatchObject({
      "auth.has_authorization_header": false,
      "auth.signed_out_reason": "session-token-and-uat-missing"
    });
  });

  it("is no one when Clerk would send a browser to sign in first", async () => {
    const signedInUserId = await loadSignIn();
    // A handshake: Clerk has no auth object for it.
    const { client } = clerkAnswering({ reason: "dev-browser-missing", toAuth: () => null });

    await expect(inRequest(() => signedInUserId(apiRequest({ cookie: "__client_uat=0" }), client))).resolves.toBeNull();
    expect(telemetry.events()[0]).toMatchObject({ "auth.has_cookie_header": true, "auth.signed_out_reason": "dev-browser-missing" });
  });

  it("is no one when Clerk fails on the sign-in the request carries, which is recorded", async () => {
    const signedInUserId = await loadSignIn();

    for (const headers of [{ authorization: "Bearer a.b.c" }, { cookie: "__session=a.b.c" }] as Record<string, string>[]) {
      const { authenticateRequest, client } = clerkAnswering(signedOut, { error: new Error("Unable to decode the token.") });

      await expect(inRequest(() => signedInUserId(apiRequest(headers), client))).resolves.toBeNull();
      // Asked again without the sign-in, which it could read.
      expect(authenticateRequest).toHaveBeenCalledTimes(2);
      expect([...authenticateRequest.mock.calls[1]![0].headers.keys()]).toEqual([]);
    }

    for (const event of telemetry.events()) {
      expect(event).toMatchObject({ "auth.sign_in_fallback": true, "error.handled": true, "error.reason": "clerk_token_undecodable" });
      expect(event.outcome).not.toBe("error");
    }
  });

  it("does not take a failure of Clerk's own for a refused sign-in", async () => {
    const signedInUserId = await loadSignIn();

    for (const headers of [{}, { authorization: "Bearer header.payload.signature" }] as Record<string, string>[]) {
      const failure = new Error("Publishable key is missing.");
      const { client } = clerkAnswering(signedOut, { error: failure, alsoWithout: true });

      await expect(inRequest(() => signedInUserId(apiRequest(headers), client)), JSON.stringify(headers)).resolves.toBe(failure);
    }

    for (const event of telemetry.events()) {
      expect(event).not.toHaveProperty("auth.sign_in_fallback");
    }
  });
});

describe("the sign-in of a tRPC request, read by Clerk's own client", () => {
  // A development instance's keys, made up, and a signing key of its own: nothing here reaches
  // Clerk, since the tokens are checked with CLERK_JWT_KEY.
  const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const otherKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey;

  function token(claims: Record<string, unknown>, key: KeyObject = privateKey) {
    const part = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
    const signed = `${part({ alg: "RS256", typ: "JWT", kid: "ins_madeup" })}.${part(claims)}`;

    return `${signed}.${createSign("RSA-SHA256").update(signed).sign(key).toString("base64url")}`;
  }

  function session(overrides: Record<string, unknown> = {}) {
    const now = Math.floor(Date.now() / 1000);

    return { sub: "user_madeup", sid: "sess_madeup", iss: "https://made-up.clerk.accounts.dev", iat: now - 5, nbf: now - 5, exp: now + 60, v: 2, ...overrides };
  }

  async function loadWithKeys() {
    vi.stubEnv("NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY", `pk_test_${Buffer.from("made-up.clerk.accounts.dev$").toString("base64")}`);
    vi.stubEnv("CLERK_SECRET_KEY", "sk_test_madeup");
    vi.stubEnv("CLERK_JWT_KEY", publicKey.export({ type: "spki", format: "pem" }).toString());
    vi.resetModules();

    return loadSignIn();
  }

  it("is the user of a valid session token", async () => {
    const signedInUserId = await loadWithKeys();
    const bearer = { authorization: `Bearer ${token(session())}` };

    await expect(inRequest(() => signedInUserId(apiRequest(bearer)))).resolves.toBe("user_madeup");
    // Whatever the caller puts in the header Clerk's middleware writes, the keys are the environment's.
    await expect(inRequest(() => signedInUserId(apiRequest({ ...bearer, "x-clerk-request-data": "made-up" })))).resolves.toBe("user_madeup");
  });

  it("is no one for a token that ran out, of a session not yet active, signed by another key, unreadable, or none", async () => {
    const signedInUserId = await loadWithKeys();
    const now = Math.floor(Date.now() / 1000);
    const cases = {
      expired: token(session({ iat: now - 120, nbf: now - 120, exp: now - 60 })),
      pending: token(session({ sts: "pending" })),
      otherKey: token(session(), otherKey),
      unreadable: "a.b.c",
      notAToken: "hello"
    };

    for (const [name, value] of Object.entries(cases)) {
      await expect(inRequest(() => signedInUserId(apiRequest({ authorization: `Bearer ${value}` }))), name).resolves.toBeNull();
    }
    await expect(inRequest(() => signedInUserId(apiRequest()))).resolves.toBeNull();

    const reasons = telemetry.events().map((event) => event["auth.signed_out_reason"]);

    expect(reasons[0]).toMatch(/^session-token-expired/);
    for (const event of telemetry.events()) {
      expect(event).not.toHaveProperty("error.type");
    }
  });
});
