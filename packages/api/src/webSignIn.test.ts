import { traceRequest } from "@hcm/telemetry";
import { setUpTelemetry, tearDownTelemetry } from "@hcm/telemetry/testing";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// How the tRPC route of the web app reads the sign-in of a request (apps/web/server/signIn.ts),
// which proxy.ts no longer does for it (docs/adr/0010). The web app has no test runner of its own.

type SignedInUserId = (request: Request, clerk?: () => Promise<unknown>) => Promise<string | null>;

async function loadSignIn(): Promise<SignedInUserId> {
  return (await import(/* @vite-ignore */ new URL("../../../apps/web/server/signIn.ts", import.meta.url).href)).signedInUserId;
}

/** A stand-in for Clerk's client whose check of a request ends in [state], or fails with [failure]. */
function clerkAnswering(state: { reason: string | null; toAuth: () => { userId: string | null } | null } | { failure: Error }) {
  const authenticateRequest = vi.fn(async (_request: Request) => {
    if ("failure" in state) {
      throw state.failure;
    }

    return state;
  });

  return { authenticateRequest, clerk: async () => ({ authenticateRequest }) };
}

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
    const { authenticateRequest, clerk } = clerkAnswering({ reason: null, toAuth: () => ({ userId: "user_1" }) });
    const request = apiRequest({ authorization: "Bearer header.payload.signature" });

    await expect(inRequest(() => signedInUserId(request, clerk))).resolves.toBe("user_1");

    expect(authenticateRequest).toHaveBeenCalledWith(request);
    expect(telemetry.events()[0]).toMatchObject({ "auth.has_authorization_header": true });
    expect(telemetry.events()[0]).not.toHaveProperty("auth.signed_out_reason");
    // Never the token.
    expect(JSON.stringify(telemetry.events())).not.toContain("header.payload.signature");
  });

  it("is no one when Clerk counts the request as signed out, and the event says why", async () => {
    const signedInUserId = await loadSignIn();
    const { clerk } = clerkAnswering({ reason: "session-token-and-uat-missing", toAuth: () => ({ userId: null }) });

    await expect(inRequest(() => signedInUserId(apiRequest(), clerk))).resolves.toBeNull();

    expect(telemetry.events()[0]).toMatchObject({
      "auth.has_authorization_header": false,
      "auth.signed_out_reason": "session-token-and-uat-missing"
    });
  });

  it("is no one when Clerk would send a browser to sign in first", async () => {
    const signedInUserId = await loadSignIn();
    // A handshake: Clerk has no auth object for it.
    const { clerk } = clerkAnswering({ reason: "dev-browser-missing", toAuth: () => null });

    await expect(inRequest(() => signedInUserId(apiRequest({ cookie: "__client_uat=0" }), clerk))).resolves.toBeNull();
    expect(telemetry.events()[0]).toMatchObject({ "auth.signed_out_reason": "dev-browser-missing" });
  });

  it("is no one when Clerk fails on the sign-in the request carries, which is recorded", async () => {
    const signedInUserId = await loadSignIn();

    for (const headers of [{ authorization: "Bearer a.b.c" }, { cookie: "__session=a.b.c" }] as Record<string, string>[]) {
      const { clerk } = clerkAnswering({ failure: new Error("Unable to decode the token.") });

      await expect(inRequest(() => signedInUserId(apiRequest(headers), clerk))).resolves.toBeNull();
    }

    for (const event of telemetry.events()) {
      expect(event).toMatchObject({ "auth.sign_in_fallback": true, "error.handled": true, "error.reason": "clerk_token_undecodable" });
      expect(event.outcome).not.toBe("error");
    }
  });

  it("does not hide a failure of a request that carries no sign-in", async () => {
    const signedInUserId = await loadSignIn();
    const failure = new Error("Publishable key is missing.");
    const { clerk } = clerkAnswering({ failure });

    await expect(inRequest(() => signedInUserId(apiRequest(), clerk))).resolves.toBe(failure);
    expect(telemetry.events()[0]).not.toHaveProperty("auth.sign_in_fallback");
  });

  it("is no one, with Clerk's own client, for a token it cannot read or none", async () => {
    // A development instance's keys, made up: nothing here reaches Clerk, since no token is read far
    // enough to need its signing keys.
    vi.stubEnv("NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY", `pk_test_${Buffer.from("made-up.clerk.accounts.dev$").toString("base64")}`);
    vi.stubEnv("CLERK_SECRET_KEY", "sk_test_madeup");
    vi.resetModules();

    const signedInUserId = await loadSignIn();

    for (const headers of [{}, { authorization: "Bearer a.b.c" }, { authorization: "Bearer hello" }] as Record<string, string>[]) {
      await expect(inRequest(() => signedInUserId(apiRequest(headers))), JSON.stringify(headers)).resolves.toBeNull();
    }

    for (const event of telemetry.events()) {
      expect(typeof event["auth.signed_out_reason"]).toBe("string");
      expect(event).not.toHaveProperty("error.type");
    }
  });
});
