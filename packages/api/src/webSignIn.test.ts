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
  // As on a server without it, whatever the shell has.
  vi.stubEnv("CLERK_JWT_KEY", "");
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

  // A made-up instance: its publishable key names its Frontend API, which issues its tokens.
  const issuer = "https://made-up.clerk.accounts.dev";
  const madeUpKey = "-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----";

  function withJwtKey(stored = madeUpKey) {
    vi.stubEnv("NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY", `pk_live_${Buffer.from("made-up.clerk.accounts.dev$").toString("base64")}`);
    vi.stubEnv("CLERK_JWT_KEY", stored);
  }

  /** An `Authorization` header with a token of key id [kid]; only its header is read here. */
  function bearer(kid = "ins_madeup") {
    return { authorization: `Bearer ${Buffer.from(JSON.stringify({ alg: "RS256", kid })).toString("base64url")}.payload.signature` };
  }

  const signedInBy = (iss: string): State => ({ reason: null, toAuth: () => ({ userId: "user_1", sessionClaims: { iss } }) as { userId: string } });
  const refusedFor = (reason: string): State => ({ reason, toAuth: () => ({ userId: null }) });

  /** A stand-in whose check with the public key ends in [withKey], and with Clerk's API keys in [withApi]. */
  function clerkWithKey(withKey: State | Error, withApi: State) {
    const authenticateRequest = vi.fn(async (_request: Request, options?: { jwtKey?: string }) => {
      if (!options?.jwtKey) {
        return withApi;
      }
      if (withKey instanceof Error) {
        throw withKey;
      }

      return withKey;
    });

    return { authenticateRequest, client: () => ({ authenticateRequest }) };
  }

  it("is checked with the public key when it is set, for a token in the Authorization header", async () => {
    withJwtKey(`  ${madeUpKey.replace(/\n/g, "\\n")}\n`);

    const signedInUserId = await loadSignIn();
    const { authenticateRequest, client } = clerkWithKey(signedInBy(issuer), signedOut);
    const request = apiRequest(bearer());

    await expect(inRequest(() => signedInUserId(request, client))).resolves.toBe("user_1");
    // As one with line breaks, also when it was stored with `\n` for them.
    expect(authenticateRequest).toHaveBeenCalledExactlyOnceWith(request, { jwtKey: madeUpKey });
    expect(telemetry.events()[0]).toMatchObject({ "auth.checked_with": "local" });
    expect(telemetry.events()[0]).not.toHaveProperty("auth.local_check_refused");
  });

  it("is checked with Clerk's keys, with the public key set, for no sign-in, a session cookie, or a token it cannot read", async () => {
    withJwtKey();

    const signedInUserId = await loadSignIn();
    const cases: Record<string, string>[] = [{}, { cookie: "__session=header.payload.signature" }, { authorization: "Bearer notatoken" }, { authorization: "Basic dXNlcjpwYXNz" }];

    for (const headers of cases) {
      const { authenticateRequest, client } = clerkWithKey(signedInBy(issuer), signedOut);
      const request = apiRequest(headers);

      await expect(inRequest(() => signedInUserId(request, client)), JSON.stringify(headers)).resolves.toBeNull();
      expect(authenticateRequest).toHaveBeenCalledExactlyOnceWith(request);
    }

    const [none, cookie, unreadable, basic] = telemetry.events();

    // Only a token in the Authorization header names what checked it.
    for (const event of [none, cookie, basic]) {
      expect(event).not.toHaveProperty("auth.checked_with");
    }
    expect(unreadable).toMatchObject({ "auth.checked_with": "clerk_api" });
    expect(telemetry.events().some((event) => "auth.local_check_refused" in event)).toBe(false);
  });

  it("is checked with Clerk's keys when the public key is not set", async () => {
    const signedInUserId = await loadSignIn();
    const { authenticateRequest, client } = clerkAnswering({ reason: null, toAuth: () => ({ userId: "user_1" }) });
    const request = apiRequest(bearer());

    await expect(inRequest(() => signedInUserId(request, client))).resolves.toBe("user_1");
    expect(authenticateRequest).toHaveBeenCalledExactlyOnceWith(request);
    expect(telemetry.events()[0]).toMatchObject({ "auth.checked_with": "clerk_api" });
  });

  it("is checked with Clerk's keys when the publishable key names no instance", async () => {
    withJwtKey();
    vi.stubEnv("NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY", "");

    const signedInUserId = await loadSignIn();
    const { authenticateRequest, client } = clerkWithKey(signedInBy(issuer), signedInBy(issuer));

    await expect(inRequest(() => signedInUserId(apiRequest(bearer()), client))).resolves.toBe("user_1");
    expect(authenticateRequest).toHaveBeenCalledTimes(1);
    expect(authenticateRequest.mock.calls[0]).toHaveLength(1);
  });

  it("is checked again with Clerk's keys when the public key does not check the token, and an out-of-date key is recorded once", async () => {
    withJwtKey();

    const signedInUserId = await loadSignIn();

    for (const withKey of [refusedFor("token-invalid-signature"), refusedFor("token-verification-failed"), new Error("Unable to import the key.")]) {
      const { authenticateRequest, client } = clerkWithKey(withKey, signedInBy(issuer));
      const request = apiRequest(bearer());

      await expect(inRequest(() => signedInUserId(request, client))).resolves.toBe("user_1");
      expect(authenticateRequest).toHaveBeenCalledTimes(2);
      expect(authenticateRequest.mock.calls[1]).toEqual([request]);
    }

    for (const event of telemetry.events()) {
      expect(event).toMatchObject({ "auth.checked_with": "clerk_api", "auth.local_check_refused": true });
      expect(event).not.toHaveProperty("auth.sign_in_fallback");
      expect(event.outcome).not.toBe("error");
    }
    // Once for the server instance: on every request it would stand in for the reason of a refusal.
    expect(telemetry.events().map((event) => event["error.reason"])).toEqual(["clerk_jwt_key_out_of_date", undefined, undefined]);
  });

  it("is no one for a token that neither key checks, which is not an out-of-date key", async () => {
    withJwtKey();

    const signedInUserId = await loadSignIn();
    const { authenticateRequest, client } = clerkWithKey(refusedFor("token-invalid-signature"), refusedFor("token-invalid-signature"));

    await expect(inRequest(() => signedInUserId(apiRequest(bearer()), client))).resolves.toBeNull();
    expect(authenticateRequest).toHaveBeenCalledTimes(2);
    expect(telemetry.events()[0]).toMatchObject({ "auth.checked_with": "clerk_api", "auth.local_check_refused": true, "auth.signed_out_reason": "token-invalid-signature" });
    expect(telemetry.events()[0]).not.toHaveProperty("error.reason");
  });

  it("does not let in a token of another instance that the public key checks", async () => {
    withJwtKey();

    const signedInUserId = await loadSignIn();
    // Say the development instance's key was set in production: its tokens fit it, but were issued by it.
    const { authenticateRequest, client } = clerkWithKey(signedInBy("https://other.clerk.accounts.dev"), refusedFor("token-invalid-signature"));

    await expect(inRequest(() => signedInUserId(apiRequest(bearer()), client))).resolves.toBeNull();
    expect(authenticateRequest).toHaveBeenCalledTimes(2);
    expect(telemetry.events()[0]).toMatchObject({ "auth.checked_with": "clerk_api", "auth.local_check_refused": true });
  });

  it("is not checked again with Clerk's keys for a token the public key refuses for itself", async () => {
    withJwtKey();

    const signedInUserId = await loadSignIn();
    const { authenticateRequest, client } = clerkWithKey(refusedFor("session-token-expired-refresh-non-eligible-no-cookie"), signedInBy(issuer));

    await expect(inRequest(() => signedInUserId(apiRequest(bearer()), client))).resolves.toBeNull();
    expect(authenticateRequest).toHaveBeenCalledTimes(1);
    expect(telemetry.events()[0]).toMatchObject({ "auth.checked_with": "local", "auth.signed_out_reason": "session-token-expired-refresh-non-eligible-no-cookie" });
  });

  it("checks only the key id the public key checked, and takes a token of another one for Clerk's keys changing", async () => {
    withJwtKey();

    const signedInUserId = await loadSignIn();
    const { authenticateRequest, client } = clerkWithKey(signedInBy(issuer), signedInBy(issuer));

    await expect(inRequest(() => signedInUserId(apiRequest(bearer("ins_old")), client))).resolves.toBe("user_1");
    await expect(inRequest(() => signedInUserId(apiRequest(bearer("ins_new")), client))).resolves.toBe("user_1");
    await expect(inRequest(() => signedInUserId(apiRequest(bearer("ins_old")), client))).resolves.toBe("user_1");

    // With the key, without it, with it.
    expect(authenticateRequest.mock.calls.map((call) => call.length)).toEqual([2, 1, 2]);
    expect(telemetry.events().map((event) => event["auth.checked_with"])).toEqual(["local", "clerk_api", "local"]);
    expect(telemetry.events()[1]).toMatchObject({ "error.reason": "clerk_jwt_key_out_of_date" });
    expect(telemetry.events()[1]).not.toHaveProperty("auth.local_check_refused");
  });

  it("checks tokens of at most three key ids with the public key before one of them is let in", async () => {
    withJwtKey();

    const signedInUserId = await loadSignIn();
    const { authenticateRequest, client } = clerkWithKey(refusedFor("token-invalid-signature"), refusedFor("token-invalid-signature"));

    for (const kid of ["ins_1", "ins_2", "ins_3", "ins_4", "ins_1"]) {
      await expect(inRequest(() => signedInUserId(apiRequest(bearer(kid)), client))).resolves.toBeNull();
    }

    // The fourth key id goes to Clerk's keys alone; one tried before is tried again.
    expect(authenticateRequest.mock.calls.map((call) => call.length)).toEqual([2, 1, 2, 1, 2, 1, 1, 2, 1]);
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
  const other = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const otherKey = other.privateKey;
  const otherPublicPem = other.publicKey.export({ type: "spki", format: "pem" }).toString();
  const kid = `ins_madeup_${randomUUID()}`;

  // A key id that Clerk's API hands out too, for the tests of the public key: Clerk keeps the key
  // it made from the public key under the token's key id, for the process, so each of those
  // tests signs with a key id of its own.
  const servedKids = [kid];

  function newKid() {
    const id = `ins_madeup_${randomUUID()}`;

    servedKids.push(id);
    return id;
  }

  const pem = publicKey.export({ type: "spki", format: "pem" }).toString();

  function token(claims: Record<string, unknown>, key: KeyObject = privateKey, keyId = kid) {
    const part = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
    const signed = `${part({ alg: "RS256", typ: "JWT", kid: keyId })}.${part(claims)}`;

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

      return Response.json({ keys: servedKids.map((id) => ({ ...publicKey.export({ format: "jwk" }), kid: id, alg: "RS256", use: "sig" })) });
    });
  }

  async function loadWithKeys(jwtKey?: string) {
    vi.stubEnv("NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY", `pk_test_${Buffer.from("made-up.clerk.accounts.dev$").toString("base64")}`);
    vi.stubEnv("CLERK_SECRET_KEY", "sk_test_madeup");
    if (jwtKey !== undefined) {
      vi.stubEnv("CLERK_JWT_KEY", jwtKey);
    }
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

  it("is the user of a valid session token, checked with the public key and no call to Clerk's API", async () => {
    // Once as the dashboard gives it, once stored with `\n` for its line breaks.
    for (const stored of [pem, pem.trim().replace(/\n/g, "\\n")]) {
      const { signedInUserId, api } = await loadWithKeys(stored);
      const bearer = { authorization: `Bearer ${token(session(), privateKey, newKid())}` };

      await expect(inRequest(() => signedInUserId(apiRequest(bearer)))).resolves.toBe("user_madeup");
      expect(api).not.toHaveBeenCalled();
    }

    for (const event of telemetry.events()) {
      expect(event).toMatchObject({ "auth.checked_with": "local" });
      expect(event).not.toHaveProperty("auth.local_check_refused");
    }
  });

  it("is no one for a token that ran out, of another key or unreadable, with the public key", async () => {
    const { signedInUserId } = await loadWithKeys(pem);
    const now = Math.floor(Date.now() / 1000);
    const cases = {
      expired: token(session({ iat: now - 120, nbf: now - 120, exp: now - 60 }), privateKey, newKid()),
      otherKey: token(session(), otherKey, newKid()),
      unreadable: "a.b.c"
    };

    for (const [name, value] of Object.entries(cases)) {
      await expect(inRequest(() => signedInUserId(apiRequest({ authorization: `Bearer ${value}` }))), name).resolves.toBeNull();
    }

    const [expired, otherKeyEvent] = telemetry.events();

    // Refused by the key itself: not asked again.
    expect(expired).toMatchObject({ "auth.checked_with": "local" });
    expect(expired!["auth.signed_out_reason"]).toMatch(/^session-token-expired/);
    // Asked again, and refused by Clerk's keys too: not recorded as a key out of date.
    expect(otherKeyEvent).toMatchObject({ "auth.checked_with": "clerk_api", "auth.local_check_refused": true });
    expect(telemetry.events().some((event) => event["error.reason"] === "clerk_jwt_key_out_of_date")).toBe(false);
  });

  it("is the user of a token signed with a key Clerk changed to, with a public key that is out of date or cannot be used", async () => {
    const outOfDate = generateKeyPairSync("rsa", { modulusLength: 2048 }).publicKey.export({ type: "spki", format: "pem" }).toString();

    for (const stored of [outOfDate, pem.slice(0, 120)]) {
      const { signedInUserId, api } = await loadWithKeys(stored);
      const bearer = { authorization: `Bearer ${token(session(), privateKey, newKid())}` };

      await expect(inRequest(() => signedInUserId(apiRequest(bearer)))).resolves.toBe("user_madeup");
      // The keys from Clerk's API checked it.
      expect(api).toHaveBeenCalled();
    }

    for (const event of telemetry.events()) {
      expect(event).toMatchObject({
        "auth.checked_with": "clerk_api",
        "auth.local_check_refused": true,
        "error.handled": true,
        "error.reason": "clerk_jwt_key_out_of_date"
      });
    }
  });

  it("lets in none of another instance's tokens when that instance's public key is set", async () => {
    // The development instance's key, set where the keys beside it are another instance's: its
    // tokens fit the key, but that instance issued them.
    const { signedInUserId } = await loadWithKeys(otherPublicPem);
    const otherInstance = token(session({ iss: "https://other-instance.clerk.accounts.dev" }), otherKey, newKid());
    const ours = token(session(), privateKey, newKid());

    await expect(inRequest(() => signedInUserId(apiRequest({ authorization: `Bearer ${otherInstance}` })))).resolves.toBeNull();
    // This instance's own token is still let in, by Clerk's API keys.
    await expect(inRequest(() => signedInUserId(apiRequest({ authorization: `Bearer ${ours}` })))).resolves.toBe("user_madeup");

    const [refused, letIn] = telemetry.events();

    expect(refused).toMatchObject({ "auth.checked_with": "clerk_api", "auth.local_check_refused": true });
    expect(refused).not.toHaveProperty("error.reason");
    expect(letIn).toMatchObject({ "auth.checked_with": "clerk_api", "error.reason": "clerk_jwt_key_out_of_date" });
  });
});
