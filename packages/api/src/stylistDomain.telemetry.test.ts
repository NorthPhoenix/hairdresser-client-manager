import { traceRequest } from "@hcm/telemetry";
import { setUpTelemetry, tearDownTelemetry } from "@hcm/telemetry/testing";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TRPCContext } from "./context";
import { refuseChangedRepeat } from "./input";
import { appRouter } from "./root";
import { addTravelFee } from "./travelFee";
import { withTransaction } from "./testSupport";

const stylist = {
  id: "stylist_1",
  clerkId: "clerk_1",
  language: "en",
  timezone: "America/Chicago",
  salonAddress: "500 Salon Ave",
  onboardingCompletedAt: new Date(),
  createdAt: new Date(),
  updatedAt: new Date()
};

let telemetry: ReturnType<typeof setUpTelemetry>;

beforeEach(() => {
  telemetry = setUpTelemetry();
});

afterEach(tearDownTelemetry);

function callerFor(models: Record<string, unknown>) {
  const db = withTransaction({
    stylist: { upsert: vi.fn().mockResolvedValue(stylist), findUniqueOrThrow: vi.fn().mockResolvedValue(stylist) },
    ...models
  });

  return appRouter.createCaller({ clerkAuth: { userId: "clerk_1" }, db, photoOrigins: [], unusedFiles: [] } as unknown as TRPCContext);
}

async function eventOf(call: () => Promise<unknown>) {
  await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
    try {
      await call();
      return { status: 200 };
    } catch {
      return { status: 400 };
    }
  });

  const [event] = telemetry.events();

  expect(event).not.toHaveProperty("telemetry.blocked_keys");
  return event ?? {};
}

describe("wide events of the domain helpers and small routers", () => {
  it("counts a Service Menu and what a save changed", async () => {
    const item = { id: "menu_1", stylistId: "stylist_1", name: "Cut", defaultPriceCents: 5000, group: "Hair", travelFee: false, createdAt: new Date(), updatedAt: new Date() };
    const list = callerFor({
      serviceMenuItem: { findMany: vi.fn().mockResolvedValue([item]) },
      appointmentService: { groupBy: vi.fn().mockResolvedValue([{ menuItemId: "menu_1", _count: { _all: 3 } }]) }
    });

    expect(await eventOf(() => list.serviceMenu.list())).toMatchObject({
      "service_menu.item_count": 1,
      "service_menu.group_count": 1,
      "service_menu.used_item_count": 1
    });

    telemetry.lines.length = 0;

    const save = callerFor({
      serviceMenuItem: {
        findFirst: vi.fn().mockResolvedValue(item),
        update: vi.fn().mockResolvedValue({ ...item, defaultPriceCents: 6000 })
      }
    });

    expect(await eventOf(() => save.serviceMenu.save({ id: "menu_1", name: "Cut", defaultPriceCents: 6000 }))).toMatchObject({
      "service_menu.id": "menu_1",
      "service_menu.save_result": "updated",
      "service_menu.changed_fields": ["price"],
      "service_menu.is_noop_save": false,
      "service_menu.group_input": "unchanged"
    });
  });

  it("records the settings an edit changed, and an outdated save", async () => {
    const caller = callerFor({
      stylist: { update: vi.fn().mockResolvedValue({ ...stylist, language: "ru" }), upsert: vi.fn().mockResolvedValue(stylist), findUniqueOrThrow: vi.fn().mockResolvedValue(stylist) },
      appointment: { updateMany: vi.fn().mockResolvedValue({ count: 2 }) }
    });

    expect(await eventOf(() => caller.stylist.saveSettings({ language: "ru", timezone: "America/Chicago", salonAddress: "500 Salon Ave" }))).toMatchObject({
      "stylist.changed_fields": ["language"],
      "stylist.is_noop_save": false,
      "stylist.salon_appointment_updated_count": 2
    });

    telemetry.lines.length = 0;

    expect(
      await eventOf(() =>
        caller.stylist.saveSettings({ language: "ru", timezone: "America/Chicago", salonAddress: "500 Salon Ave", expectedUpdatedAt: "2020-01-01T00:00:00.000Z" })
      )
    ).toMatchObject({ outcome: "refused", "error.reason": "outdated_save", "save.freshness": "outdated" });
  });

  it("names a repeat sent with other values", async () => {
    await traceRequest({ method: "POST", route: "/x" }, async () => {
      try {
        refuseChangedRepeat({ name: "A" }, { name: "B" }, "service_menu");
      } catch {
        // Refused.
      }

      return { status: 409 };
    });

    expect(telemetry.events()[0]).toMatchObject({
      "error.reason": "changed_repeat",
      "error.expected": true,
      "request.repeat": "changed",
      "request.repeat_entity": "service_menu",
      "request.repeat_changed_fields": ["name"]
    });
  });

  it("says why the Travel Fee changed nothing", async () => {
    const db = { serviceMenuItem: { findFirst: vi.fn().mockResolvedValue(null) } };

    await traceRequest({ method: "POST", route: "/x" }, async () => {
      await addTravelFee(db as never, "stylist_1", "appointment_1", "client_1");
      return { status: 200 };
    });

    expect(telemetry.events()[0]).toMatchObject({ "travel_fee.decision": "no_item" });
  });

  it("summarises a period and wraps the aggregation in a span", async () => {
    const completed = [
      { locationType: "atHome", finalTotalCentsOverride: null, tipCents: 500, services: [{ menuItemId: null, name: "Cut", priceCents: 5000 }] }
    ];
    const caller = callerFor({
      appointment: {
        findMany: vi.fn().mockResolvedValueOnce(completed).mockResolvedValueOnce([]),
        groupBy: vi.fn().mockResolvedValue([{ status: "canceled", _count: { _all: 2 } }])
      }
    });

    expect(await eventOf(() => caller.stats.summary({ period: "week" }))).toMatchObject({
      "stats.period": "week",
      "stats.range_days": 7,
      "stats.completed_count": 1,
      "stats.canceled_count": 2,
      "stats.at_home_count": 1,
      "stats.earned_minor": 5500,
      "stats.tips_minor": 500,
      "stats.is_empty": false
    });
  });
});
