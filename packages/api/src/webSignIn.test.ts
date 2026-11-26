/// <reference types="node" />

import { createSign, generateKeyPairSync, randomUUID, type KeyObject } from "node:crypto";
import { traceRequest } from "@hcm/telemetry";
import { setUpTelemetry, tearDownTelemetry } from "@hcm/telemetry/testing";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// How the API routes of the web app (tRPC, uploads) read the sign-in of a request
// (apps/web/server/signIn.ts), which proxy.ts no longer does for them (docs/adr/0010). The web app
// has no test runner of its own.

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

describe("the sign-in of an API request", () => {
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

describe("the sign-in of an API request, read by Clerk's own client", () => {
  // A development instance's keys, made up, and a signing key of its own, which a stand-in for
  // Clerk's API hands out as it does in production (`GET /v1/jwks` with the secret key). Clerk
  // keeps the keys it fetched for the process, so the key's id is new for each run.
  const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const otherKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey;
  const kid = `ins_madeup_${randomUUID()}`;

  function token(claims: Record<string, unknown>, key: KeyObject = privateKey) {
    const part = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
    const signed = `${part({ alg: "RS256", typ: "JWT", kid })}.${part(claims)}`;

    return `${signed}.${createSign("RSA-SHA256").update(signed).sign(key).toString("base64url")}`;
  }

  function session(overrides: Record<string, unknown> = {}) {
    const now = Math.floor(Date.now() / 1000);

    return { sub: "user_madeup", sid: "sess_madeup", iss: "https://made-up.clerk.accounts.dev", iat: now - 5, nbf: now - 5, exp: now + 60, v: 2, ...overrides };
  }

  /** Clerk's API as far as checking a token needs it: its signing keys, for the instance's secret key. */
  function clerkApi() {
    return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const address = String(url instanceof Request ? url.url : url);
      const authorization = new Headers(init?.headers).get("authorization");

      if (address !== "https://api.clerk.com/v1/jwks" || authorization !== "Bearer sk_test_madeup") {
        return Response.json({ errors: [{ code: "not_found" }] }, { status: 404 });
      }

      return Response.json({ keys: [{ ...publicKey.export({ format: "jwk" }), kid, alg: "RS256", use: "sig" }] });
    });
  }

  async function loadWithKeys() {
    vi.stubEnv("NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY", `pk_test_${Buffer.from("made-up.clerk.accounts.dev$").toString("base64")}`);
    vi.stubEnv("CLERK_SECRET_KEY", "sk_test_madeup");
    vi.resetModules();

    const api = clerkApi();

    vi.stubGlobal("fetch", api);

    return { signedInUserId: await loadSignIn(), api };
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("is the user of a valid session token, with the keys from Clerk's API", async () => {
    const { signedInUserId, api } = await loadWithKeys();
    const bearer = { authorization: `Bearer ${token(session())}` };

    await expect(inRequest(() => signedInUserId(apiRequest(bearer)))).resolves.toBe("user_madeup");
    expect(api).toHaveBeenCalled();
    expect(api.mock.calls.every(([url]) => String(url) === "https://api.clerk.com/v1/jwks")).toBe(true);
  });

  it("is no one for a token that ran out, of a session not let in yet, signed by another key, of a machine, unreadable, or none", async () => {
    const { signedInUserId } = await loadWithKeys();
    const now = Math.floor(Date.now() / 1000);
    const cases = {
      expired: token(session({ iat: now - 120, nbf: now - 120, exp: now - 60 })),
      pending: token(session({ sts: "pending" })),
      otherKey: token(session(), otherKey),
      apiKey: "ak_madeup",
      machineToken: "mt_madeup",
      unreadable: "a.b.c",
      notAToken: "hello"
    };

    for (const [name, value] of Object.entries(cases)) {
      await expect(inRequest(() => signedInUserId(apiRequest({ authorization: `Bearer ${value}` }))), name).resolves.toBeNull();
    }
    await expect(inRequest(() => signedInUserId(apiRequest()))).resolves.toBeNull();

    const reasons = telemetry.events().map((event) => event["auth.signed_out_reason"]);

    expect(reasons[0]).toMatch(/^session-token-expired/);
    expect(reasons[1]).toBe("session-pending");
    expect(reasons.every((reason) => typeof reason === "string")).toBe(true);
    for (const event of telemetry.events()) {
      expect(event).not.toHaveProperty("error.type");
    }
  });
});
