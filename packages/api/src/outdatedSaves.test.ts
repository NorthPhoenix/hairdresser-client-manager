import { describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
import { withTransaction } from "./testSupport";

const loadedAt = new Date("2026-06-13T12:00:00.000Z");
const savedElsewhereAt = new Date("2026-06-13T12:05:00.000Z");
const outdated = {
  code: "CONFLICT",
  message: "This record was changed since it was loaded."
};

function createCaller(db: object) {
  return appRouter.createCaller({
    clerkAuth: {
      userId: "clerk_1"
    },
    db: withTransaction(db),
    photoOrigins: []
  } as unknown as TRPCContext);
}

describe("saving a Client Profile from a form", () => {
  // The Client after another device changed the phone number.
  const storedClient = {
    id: "client_1",
    stylistId: "stylist_1",
    name: "Two Tina",
    language: "en" as const,
    phone: "5550999",
    email: "",
    address: "",
    note: "old note",
    createdAt: loadedAt,
    updatedAt: savedElsewhereAt
  };
  // What the form that was loaded before that change sends: the old phone and a new note.
  const formInput = {
    id: "client_1",
    name: "Two Tina",
    language: "en" as const,
    phone: "5550141",
    email: "",
    address: "",
    note: "old note app-note"
  };

  function createClientDb() {
    const update = vi.fn(({ data }: { data: Record<string, unknown> }) =>
      Promise.resolve({
        ...storedClient,
        ...data,
        updatedAt: new Date("2026-06-13T12:10:00.000Z")
      })
    );

    return {
      update,
      db: {
        stylist: {
          upsert: vi.fn().mockResolvedValue({
            id: "stylist_1",
            language: "en"
          })
        },
        client: {
          findFirst: vi.fn().mockResolvedValue(storedClient),
          update
        }
      }
    };
  }

  it("refuses the save when the Client was saved elsewhere after the form loaded it", async () => {
    const { db, update } = createClientDb();

    await expect(
      createCaller(db).clientProfile.save({
        ...formInput,
        expectedUpdatedAt: loadedAt.toISOString()
      })
    ).rejects.toMatchObject(outdated);
    expect(update).not.toHaveBeenCalled();
  });

  it("saves when the form was loaded from the Client as it is now, and answers with the new version", async () => {
    const { db, update } = createClientDb();

    await expect(
      createCaller(db).clientProfile.save({
        ...formInput,
        // The same moment written without milliseconds is the same version.
        expectedUpdatedAt: "2026-06-13T12:05:00Z"
      })
    ).resolves.toMatchObject({
      phone: "5550141",
      note: "old note app-note",
      updatedAt: "2026-06-13T12:10:00.000Z"
    });
    expect(update).toHaveBeenCalledTimes(1);
  });

  it("accepts a save that changes nothing: the same save sent again after its reply was lost", async () => {
    const { db, update } = createClientDb();

    await expect(
      createCaller(db).clientProfile.save({
        ...formInput,
        phone: storedClient.phone,
        note: storedClient.note,
        expectedUpdatedAt: loadedAt.toISOString()
      })
    ).resolves.toMatchObject({
      id: "client_1",
      phone: "5550999"
    });
    expect(update).toHaveBeenCalledTimes(1);
  });

  it("keeps saving for a caller that sends no version", async () => {
    const { db, update } = createClientDb();

    await createCaller(db).clientProfile.save(formInput);

    expect(update).toHaveBeenCalledTimes(1);
  });
});

describe("saving the Stylist's settings from a form", () => {
  // The Stylist after another device saved a new timezone and salon address.
  const storedStylist = {
    id: "stylist_1",
    clerkId: "clerk_1",
    language: "en" as const,
    timezone: "Asia/Tokyo",
    salonAddress: "9 Other Road",
    onboardingCompletedAt: loadedAt,
    createdAt: loadedAt,
    updatedAt: savedElsewhereAt
  };

  function createStylistDb() {
    const update = vi.fn(({ data }: { data: Record<string, unknown> }) =>
      Promise.resolve({
        ...storedStylist,
        ...data,
        updatedAt: new Date("2026-06-13T12:10:00.000Z")
      })
    );
    const appointmentUpdateMany = vi.fn();

    return {
      update,
      appointmentUpdateMany,
      db: {
        stylist: {
          // What the request read before it waited for the lock: already out of date.
          upsert: vi.fn().mockResolvedValue({
            ...storedStylist,
            timezone: "UTC",
            salonAddress: "12 Salon Street",
            updatedAt: loadedAt
          }),
          findUniqueOrThrow: vi.fn().mockResolvedValue(storedStylist),
          update
        },
        appointment: {
          updateMany: appointmentUpdateMany
        }
      }
    };
  }

  it("refuses a language switch that would put back the old timezone and salon address", async () => {
    const { db, update, appointmentUpdateMany } = createStylistDb();

    await expect(
      createCaller(db).stylist.saveSettings({
        language: "ru",
        timezone: "UTC",
        salonAddress: "12 Salon Street",
        expectedUpdatedAt: loadedAt.toISOString()
      })
    ).rejects.toMatchObject(outdated);
    expect(update).not.toHaveBeenCalled();
    expect(appointmentUpdateMany).not.toHaveBeenCalled();
  });

  it("saves from up-to-date settings and answers with the new version", async () => {
    const { db, update } = createStylistDb();

    await expect(
      createCaller(db).stylist.saveSettings({
        language: "ru",
        timezone: "Asia/Tokyo",
        salonAddress: "9 Other Road",
        expectedUpdatedAt: savedElsewhereAt.toISOString()
      })
    ).resolves.toMatchObject({
      language: "ru",
      timezone: "Asia/Tokyo",
      salonAddress: "9 Other Road",
      updatedAt: "2026-06-13T12:10:00.000Z"
    });
    expect(update).toHaveBeenCalledTimes(1);
  });

  it("accepts settings that are already saved, whatever version the form had", async () => {
    const { db, update } = createStylistDb();

    await createCaller(db).stylist.saveSettings({
      language: "en",
      timezone: "Asia/Tokyo",
      salonAddress: "9 Other Road",
      expectedUpdatedAt: loadedAt.toISOString()
    });

    expect(update).toHaveBeenCalledTimes(1);
  });

  it("keeps saving for a caller that sends no version", async () => {
    const { db, update } = createStylistDb();

    await createCaller(db).stylist.saveSettings({
      language: "ru",
      timezone: "UTC",
      salonAddress: "12 Salon Street"
    });

    expect(update).toHaveBeenCalledTimes(1);
  });

  it("tells the app which version of the settings it loaded", async () => {
    const { db } = createStylistDb();

    await expect(createCaller(db).stylist.bootstrap()).resolves.toMatchObject({
      updatedAt: loadedAt.toISOString()
    });
  });
});
