import { traceRequest } from "@hcm/telemetry";
import { setUpTelemetry, tearDownTelemetry } from "@hcm/telemetry/testing";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TRPCContext } from "./context";
import { appRouter } from "./root";

// How every signed-in request finds its Stylist (docs/adr/0011): one query for a Stylist who
// exists, a create only for a new account.

type Row = {
  id: string;
  clerkId: string;
  language: string;
  timezone: string;
  salonAddress: string | null;
  onboardingCompletedAt: Date | null;
  createdAt: Date;
  updatedAt: Date;
};

function row(clerkId: string, overrides: Partial<Row> = {}): Row {
  return {
    id: `stylist_${clerkId}`,
    clerkId,
    language: "ru",
    timezone: "UTC",
    salonAddress: null,
    onboardingCompletedAt: null,
    createdAt: new Date(),
    updatedAt: new Date(),
    ...overrides
  };
}

function uniqueViolation() {
  return Object.assign(new Error("Unique constraint failed on the fields: (`clerkId`)"), {
    name: "PrismaClientKnownRequestError",
    code: "P2002"
  });
}

/**
 * Stylists kept as the database keeps them: a unique Clerk id, so the second create of the same
 * account fails as Postgres would fail it. `beforeCreate` runs between a find that found nothing
 * and the create, where two first requests of an account can overlap.
 */
function stylistTable(beforeCreate: () => Promise<void> = async () => {}) {
  const rows = new Map<string, Row>();
  const findUnique = vi.fn(async ({ where }: { where: { clerkId: string } }) => rows.get(where.clerkId) ?? null);
  const create = vi.fn(async ({ data }: { data: { clerkId: string; language: string; timezone: string } }) => {
    await beforeCreate();

    if (rows.has(data.clerkId)) {
      throw uniqueViolation();
    }

    const created = row(data.clerkId, { language: data.language, timezone: data.timezone });

    rows.set(data.clerkId, created);
    return created;
  });

  return { rows, findUnique, create };
}

function callerFor(stylist: Record<string, unknown>, userId = "clerk_1") {
  return appRouter.createCaller({
    clerkAuth: { userId },
    db: { stylist },
    photoOrigins: []
  } as unknown as TRPCContext);
}

let telemetry: ReturnType<typeof setUpTelemetry>;

beforeEach(() => {
  telemetry = setUpTelemetry();
});

afterEach(tearDownTelemetry);

async function inRequest(call: () => Promise<unknown>) {
  let outcome: unknown;

  await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
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

describe("the Stylist of a request", () => {
  it("is found with one query, with nothing written, when it exists", async () => {
    const table = stylistTable();
    table.rows.set("clerk_1", row("clerk_1", { timezone: "America/Chicago" }));

    await expect(
      inRequest(() => callerFor(table).stylist.bootstrap({ deviceLanguage: "en", deviceTimezone: "Europe/Moscow" }))
    ).resolves.toMatchObject({ id: "stylist_clerk_1", language: "ru", timezone: "America/Chicago" });

    expect(table.findUnique).toHaveBeenCalledTimes(1);
    expect(table.findUnique).toHaveBeenCalledWith({ where: { clerkId: "clerk_1" } });
    expect(table.create).not.toHaveBeenCalled();
    expect(telemetry.events()[0]).toMatchObject({
      "stylist.id": "stylist_clerk_1",
      "stylist.just_created": false,
      "stylist.device_defaults_valid": true
    });
  });

  it("is created from the device's defaults on the first request of an account, which says so", async () => {
    const table = stylistTable();

    await expect(
      inRequest(() => callerFor(table).stylist.bootstrap({ deviceLanguage: "en", deviceTimezone: "America/Chicago" }))
    ).resolves.toMatchObject({ id: "stylist_clerk_1", language: "en", timezone: "America/Chicago", onboardingComplete: false });

    expect(table.create).toHaveBeenCalledTimes(1);
    expect(table.rows.size).toBe(1);
    expect(telemetry.events()[0]).toMatchObject({ "stylist.just_created": true });
  });

  it("is the same one for two first requests of an account at once, and neither fails", async () => {
    // Both have found nothing before either creates: the second create meets the first one's row.
    let found = 0;
    let bothFound!: () => void;
    const bothHaveLooked = new Promise<void>((resolve) => {
      bothFound = resolve;
    });
    const table = stylistTable(async () => {
      found += 1;

      if (found === 2) {
        bothFound();
      }

      await bothHaveLooked;
    });
    const caller = callerFor(table);

    const [first, second] = await Promise.all([
      inRequest(() => caller.stylist.bootstrap({ deviceLanguage: "en", deviceTimezone: "America/Chicago" })),
      inRequest(() => caller.stylist.bootstrap({ deviceLanguage: "en", deviceTimezone: "America/Chicago" }))
    ]);

    expect(first).toMatchObject({ id: "stylist_clerk_1", language: "en" });
    expect(second).toMatchObject({ id: "stylist_clerk_1", language: "en" });
    expect(table.create).toHaveBeenCalledTimes(2);
    expect(table.rows.size).toBe(1);
    // The request whose create lost found the Stylist again, after the create.
    expect(table.findUnique).toHaveBeenCalledTimes(3);
    // Only the request that made the Stylist says it did.
    expect(telemetry.events().map((event) => event["stylist.just_created"]).sort()).toEqual([false, true]);
  });

  it("fails as before when the create fails for another reason, and is not looked for again", async () => {
    const findUnique = vi.fn().mockResolvedValue(null);
    const create = vi.fn().mockRejectedValue(new Error("Can't reach database server"));

    await expect(inRequest(() => callerFor({ findUnique, create }).stylist.bootstrap())).resolves.toMatchObject({
      code: "INTERNAL_SERVER_ERROR"
    });
    expect(findUnique).toHaveBeenCalledTimes(1);
  });

  it("answers a create refused for an existing Clerk id as a conflict when it is not found again either", async () => {
    // Made and deleted again between the two looks, say: the refusal stands, in the domain's words.
    const findUnique = vi.fn().mockResolvedValue(null);
    const create = vi.fn().mockRejectedValue(uniqueViolation());

    await expect(inRequest(() => callerFor({ findUnique, create }).stylist.bootstrap())).resolves.toMatchObject({
      code: "CONFLICT",
      message: "This record already exists."
    });
    expect(findUnique).toHaveBeenCalledTimes(2);
  });
});
