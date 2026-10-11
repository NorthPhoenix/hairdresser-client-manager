/// <reference types="node" />

import { describe, expect, it, vi } from "vitest";
import type { Db, TRPCContext } from "./context";
import {
  defaultProfileShareOptions,
  findSharedPhoto,
  formatAppointmentTime,
  loadProfileShareContent,
  resolveProfileShareOptions,
  sharedPhotoName
} from "./profileShare";
import { appRouter } from "./root";
import { withTransaction } from "./testSupport";

const storage = "https://app.ufs.sh";

const client = {
  id: "client_1",
  name: "Anna",
  stylist: {
    id: "stylist_1",
    clerkId: "clerk_1",
    timezone: "Europe/Moscow",
    salonAddress: "12 Salon Street"
  }
};

type PhotoRow = {
  id: string;
  clientId: string;
  status: "stored" | "pendingUpload" | "failed";
  category: "before" | "after" | "other";
  url: string | null;
  createdAt: Date;
};

const photoRow = (id: string, row: Partial<PhotoRow> = {}): PhotoRow => ({
  id,
  clientId: "client_1",
  status: "stored",
  category: "after",
  url: `${storage}/f/${id}`,
  createdAt: new Date("2026-10-05T12:00:00.000Z"),
  ...row
});

// A group Appointment of Anna (client_1) and Olga (client_2), as the database holds it.
const completedAppointment = {
  startsAt: new Date("2026-10-05T11:00:00.000Z"),
  endsAt: new Date("2026-10-05T12:30:00.000Z"),
  locationType: "inSalon" as const,
  locationAddress: "12 Salon Street",
  note: "appointment note",
  services: [
    {
      clientId: "client_1",
      name: "Color",
      priceCents: 8500,
      note: "service note",
      colorFormulas: [
        { id: "formula_1", formula: "7N + 20 vol", placement: "Roots" },
        { id: "formula_2", formula: "9V", placement: null }
      ]
    },
    {
      clientId: "client_2",
      name: "Olga's cut",
      priceCents: 4000,
      note: null,
      colorFormulas: []
    }
  ],
  photos: [
    photoRow("before_1", { category: "before" }),
    photoRow("after_1"),
    photoRow("other_1", { category: "other" }),
    photoRow("after_2"),
    photoRow("olga_after", { clientId: "client_2" }),
    photoRow("after_uploading", { status: "pendingUpload", url: null }),
    photoRow("after_failed", { status: "failed", url: null }),
    photoRow("after_elsewhere", { url: "https://elsewhere.example/f/after_elsewhere" })
  ]
};

type CompletedQuery = {
  include: {
    services: { where: { clientId: string } };
    photos: { where: { clientId: string; status: string; category: { in: string[] } } };
  };
};

// Answers the two queries of a share the way the database would, from the rows above.
function createSources(overrides: { completed?: typeof completedAppointment | null; accountName?: () => Promise<string | null> } = {}) {
  const completed = overrides.completed === undefined ? completedAppointment : overrides.completed;
  const findMany = vi.fn().mockResolvedValue([
    {
      id: "appointment_2",
      startsAt: new Date("2026-10-22T21:30:00.000Z"),
      endsAt: new Date("2026-10-22T22:30:00.000Z"),
      locationType: "atHome",
      locationAddress: "Client home",
      note: "appointment note"
    }
  ]);
  const findFirst = vi.fn(async ({ include }: CompletedQuery) => {
    const { where } = include.photos;

    return (
      completed && {
        ...completed,
        services: completed.services.filter((service) => service.clientId === include.services.where.clientId),
        photos: completed.photos.filter(
          (photo) => photo.clientId === where.clientId && photo.status === where.status && where.category.in.includes(photo.category)
        )
      }
    );
  });

  return {
    findMany,
    findFirst,
    sources: {
      db: {
        appointment: {
          findMany,
          findFirst
        }
      } as unknown as Db,
      photoOrigins: [storage],
      accountName: overrides.accountName ?? (async () => "Maria Ivanova")
    }
  };
}

describe("Profile Share options", () => {
  it("includes Color Formulas and after photos by default, and no before or other photos", () => {
    expect(resolveProfileShareOptions()).toEqual({
      includeColorFormulas: true,
      includeAfterPhotos: true,
      includeOtherPhotos: false
    });
    expect(resolveProfileShareOptions(null, undefined, {})).toEqual(defaultProfileShareOptions);
  });

  it("takes each choice from the first source that states it", () => {
    expect(
      resolveProfileShareOptions(
        { includeColorFormulas: false },
        { includeColorFormulas: true, includeAfterPhotos: false, includeOtherPhotos: true }
      )
    ).toEqual({
      includeColorFormulas: false,
      includeAfterPhotos: false,
      includeOtherPhotos: true
    });
  });
});

describe("Profile Share content", () => {
  it("shows the after photos of the last completed Appointment by default, only the Client's and only stored ones", async () => {
    const { sources, findFirst } = createSources();
    const content = await loadProfileShareContent(sources, client, defaultProfileShareOptions);

    expect(content.lastCompletedAppointment?.photos).toEqual([
      { url: `${storage}/f/after_1`, category: "after" },
      { url: `${storage}/f/after_2`, category: "after" }
    ]);
    expect(findFirst.mock.calls[0]?.[0].include.photos.where).toEqual({
      clientId: "client_1",
      status: "stored",
      category: {
        in: ["after"]
      }
    });
  });

  it("adds the before and other photos when the Stylist selected them, after photos first", async () => {
    const { sources } = createSources();
    const content = await loadProfileShareContent(sources, client, {
      ...defaultProfileShareOptions,
      includeOtherPhotos: true
    });

    expect(content.lastCompletedAppointment?.photos.map((photo) => photo.url.split("/").pop())).toEqual([
      "after_1",
      "after_2",
      "before_1",
      "other_1"
    ]);
    expect(content.lastCompletedAppointment?.photos.map((photo) => photo.category)).toEqual([
      "after",
      "after",
      "before",
      "other"
    ]);
  });

  it("shows no photo when the Stylist left the photos out", async () => {
    const { sources } = createSources();
    const withoutAfter = await loadProfileShareContent(sources, client, {
      ...defaultProfileShareOptions,
      includeAfterPhotos: false
    });
    const onlyOther = await loadProfileShareContent(sources, client, {
      ...defaultProfileShareOptions,
      includeAfterPhotos: false,
      includeOtherPhotos: true
    });

    expect(withoutAfter.lastCompletedAppointment?.photos).toEqual([]);
    expect(onlyOther.lastCompletedAppointment?.photos.map((photo) => photo.category)).toEqual(["before", "other"]);
  });

  it("leaves the Color Formulas out when the Stylist excluded them and keeps the Services", async () => {
    const { sources } = createSources();
    const shared = await loadProfileShareContent(sources, client, defaultProfileShareOptions);
    const excluded = await loadProfileShareContent(sources, client, {
      ...defaultProfileShareOptions,
      includeColorFormulas: false
    });

    expect(shared.includesColorFormulas).toBe(true);
    expect(shared.lastCompletedAppointment?.services).toEqual([
      {
        name: "Color",
        colorFormulas: [
          { formula: "7N + 20 vol", placement: "Roots" },
          { formula: "9V", placement: null }
        ]
      }
    ]);
    expect(excluded.includesColorFormulas).toBe(false);
    expect(excluded.lastCompletedAppointment?.services).toEqual([{ name: "Color", colorFormulas: [] }]);
    expect(JSON.stringify(excluded)).not.toContain("7N");
  });

  it("carries the Stylist's timezone, account name and salon address, and nothing private", async () => {
    const { sources, findMany } = createSources({ accountName: async () => "  Maria \n Ivanova " });
    const content = await loadProfileShareContent(sources, client, defaultProfileShareOptions);

    expect(content.clientName).toBe("Anna");
    expect(content.stylistName).toBe("Maria Ivanova");
    expect(content.salonAddress).toBe("12 Salon Street");
    expect(content.timezone).toBe("Europe/Moscow");
    expect(content.upcomingAppointments).toEqual([
      {
        startsAt: new Date("2026-10-22T21:30:00.000Z"),
        endsAt: new Date("2026-10-22T22:30:00.000Z"),
        locationType: "atHome",
        locationAddress: "Client home"
      }
    ]);
    expect(findMany.mock.calls[0]?.[0].where).toMatchObject({
      stylistId: "stylist_1",
      status: "scheduled",
      participants: {
        some: {
          clientId: "client_1"
        }
      }
    });

    // No note, no price, no other Client, no record id and no Clerk id.
    for (const hidden of ["note", "8500", "Olga", "appointment_2", "formula_1", "after_uploading", "clerk_1", "elsewhere"]) {
      expect(JSON.stringify(content)).not.toContain(hidden);
    }
  });

  it("is shown without a Stylist name when the account has none or the sign-in provider fails", async () => {
    const unnamed = createSources({ accountName: async () => null });
    const failing = createSources({
      accountName: async () => {
        throw new Error("provider is down");
      }
    });

    expect((await loadProfileShareContent(unnamed.sources, client, defaultProfileShareOptions)).stylistName).toBeNull();
    expect((await loadProfileShareContent(failing.sources, client, defaultProfileShareOptions)).stylistName).toBeNull();
  });

  it("has no last completed Appointment when the Client has none", async () => {
    const { sources } = createSources({ completed: null });
    const content = await loadProfileShareContent(sources, { ...client, stylist: { ...client.stylist, salonAddress: " " } }, defaultProfileShareOptions);

    expect(content.lastCompletedAppointment).toBeNull();
    expect(content.salonAddress).toBeNull();
  });
});

describe("Profile Share photo links", () => {
  const token = "a".repeat(64);
  const withEveryPhoto = { ...defaultProfileShareOptions, includeOtherPhotos: true };

  it("name each shared photo without telling where its file is stored", async () => {
    const content = await loadProfileShareContent(createSources().sources, client, withEveryPhoto);
    const photos = content.lastCompletedAppointment?.photos ?? [];
    const names = photos.map((photo) => sharedPhotoName(token, photo));

    expect(photos).toHaveLength(4);
    expect(new Set(names).size).toBe(4);

    for (const [index, name] of names.entries()) {
      // Safe as one part of a link, and nothing of the stored file or of the database is in it.
      expect(name).toMatch(/^[A-Za-z0-9_-]{16}$/);
      expect(name).not.toContain(photos[index]?.url.split("/").pop());
      expect(findSharedPhoto(content, token, name)).toEqual(photos[index]);
    }

    // The same photo keeps its name from one view of the page to the next.
    expect(sharedPhotoName(token, { ...photos[0]! })).toBe(names[0]);
  });

  it("find a photo only under the link it was named for", async () => {
    const content = await loadProfileShareContent(createSources().sources, client, withEveryPhoto);
    const photo = content.lastCompletedAppointment!.photos[0]!;
    const otherToken = "b".repeat(64);

    expect(sharedPhotoName(otherToken, photo)).not.toBe(sharedPhotoName(token, photo));
    expect(findSharedPhoto(content, otherToken, sharedPhotoName(token, photo))).toBeNull();
  });

  it("find nothing by the address or the file key of a stored file, or by a made-up name", async () => {
    const content = await loadProfileShareContent(createSources().sources, client, withEveryPhoto);

    for (const name of ["after_1", `${storage}/f/after_1`, "0", "", "x".repeat(16)]) {
      expect(findSharedPhoto(content, token, name)).toBeNull();
    }
  });

  it("stop finding a photo when the Stylist leaves it out, and never find one the share does not show", async () => {
    const { sources } = createSources();
    const shown = await loadProfileShareContent(sources, client, withEveryPhoto);
    const nameOf = (file: string) => sharedPhotoName(token, { url: `${storage}/f/${file}`, category: "after" });

    expect(findSharedPhoto(shown, token, nameOf("before_1"))?.category).toBe("before");
    expect(findSharedPhoto(shown, token, nameOf("after_1"))?.category).toBe("after");

    const afterOnly = await loadProfileShareContent(sources, client, defaultProfileShareOptions);

    expect(findSharedPhoto(afterOnly, token, nameOf("before_1"))).toBeNull();
    expect(findSharedPhoto(afterOnly, token, nameOf("after_1"))?.category).toBe("after");

    const noPhotos = await loadProfileShareContent(sources, client, { ...withEveryPhoto, includeAfterPhotos: false, includeOtherPhotos: false });

    expect(findSharedPhoto(noPhotos, token, nameOf("after_1"))).toBeNull();

    // The other Client's photo, one that is not stored, and one outside this server's storage.
    for (const file of ["olga_after", "after_uploading", "after_failed"]) {
      expect(findSharedPhoto(shown, token, nameOf(file))).toBeNull();
    }

    expect(
      findSharedPhoto(shown, token, sharedPhotoName(token, { url: "https://elsewhere.example/f/after_elsewhere", category: "after" }))
    ).toBeNull();
  });

  it("find nothing when the Client has no completed Appointment", async () => {
    const content = await loadProfileShareContent(createSources({ completed: null }).sources, client, withEveryPhoto);

    expect(findSharedPhoto(content, token, sharedPhotoName(token, { url: `${storage}/f/after_1`, category: "after" }))).toBeNull();
  });
});

describe("Profile Share times", () => {
  const appointment = {
    startsAt: new Date("2026-10-22T21:30:00.000Z"),
    endsAt: new Date("2026-10-22T22:30:00.000Z"),
    locationType: "inSalon" as const,
    locationAddress: null
  };
  const plain = (text: string) => text.replaceAll(" ", " ");

  it("are written in the Stylist's timezone and in the language of the page", () => {
    expect(plain(formatAppointmentTime("en", "Europe/Moscow", appointment))).toBe("Oct 23, 2026, 12:30 AM - 1:30 AM");
    expect(plain(formatAppointmentTime("ru", "Europe/Moscow", appointment))).toBe("23 окт. 2026 г., 00:30 - 01:30");
    expect(plain(formatAppointmentTime("en", "America/Chicago", appointment))).toBe("Oct 22, 2026, 4:30 PM - 5:30 PM");
    expect(plain(formatAppointmentTime("en", "Asia/Tokyo", { ...appointment, endsAt: null }))).toBe("Oct 23, 2026, 6:30 AM");
  });

  it("do not depend on the timezone of the server", () => {
    const serverTimezone = process.env.TZ;

    try {
      const written = ["UTC", "America/Los_Angeles", "Asia/Vladivostok"].map((timezone) => {
        process.env.TZ = timezone;

        return {
          serverTime: appointment.startsAt.toLocaleTimeString("en-US"),
          shown: plain(formatAppointmentTime("en", "Europe/Moscow", appointment))
        };
      });

      // The server really was in three timezones, and the share showed the same time in each.
      expect(new Set(written.map(({ serverTime }) => serverTime)).size).toBe(3);
      expect(written.map(({ shown }) => shown)).toEqual(Array(3).fill("Oct 23, 2026, 12:30 AM - 1:30 AM"));
    } finally {
      if (serverTimezone === undefined) {
        delete process.env.TZ;
      } else {
        process.env.TZ = serverTimezone;
      }
    }
  });

  it("fall back to UTC for a stored timezone this server does not know, instead of failing", () => {
    expect(plain(formatAppointmentTime("en", "Mars/Olympus", appointment))).toBe("Oct 22, 2026, 9:30 PM - 10:30 PM");
  });
});

describe("Profile Share procedures", () => {
  const createdAt = new Date("2026-10-06T09:00:00.000Z");

  // A signed-in Stylist in Moscow with the Client and the Appointments above. `share` is the
  // Client's active Profile Share, if any.
  function createCaller(share: Record<string, unknown> | null = null) {
    const { sources, findMany, findFirst } = createSources();
    const shareRow = share && {
      id: "share_1",
      clientId: "client_1",
      token: "token_1",
      language: "ru",
      includeColorFormulas: true,
      includeAfterPhotos: true,
      includeOtherPhotos: false,
      revokedAt: null,
      createdAt,
      ...share
    };
    const clientRow = {
      id: "client_1",
      stylistId: "stylist_1",
      name: "Anna",
      language: "ru",
      phone: "+15551234567",
      email: "anna@example.com",
      address: null,
      note: "client note",
      createdAt,
      updatedAt: createdAt,
      profileShares: shareRow ? [shareRow] : []
    };
    const profileShareCreate = vi.fn(async ({ data }: { data: Record<string, unknown> }) => ({
      id: "share_new",
      revokedAt: null,
      createdAt,
      ...data
    }));
    const profileShareUpdate = vi.fn(async ({ data }: { data: Record<string, unknown> }) => ({
      ...shareRow,
      // Like the database: a field that is not stated keeps its value.
      ...Object.fromEntries(Object.entries(data).filter(([, value]) => value !== undefined))
    }));
    const readSharePhoto = vi.fn(async (url: string) => ({
      dataUri: `data:image/jpeg;base64,${url.split("/").pop()}`,
      width: 720,
      height: 960
    }));
    const caller = appRouter.createCaller({
      clerkAuth: {
        userId: "clerk_1"
      },
      db: withTransaction({
        stylist: {
          upsert: vi.fn().mockResolvedValue({
            ...client.stylist,
            language: "ru",
            onboardingCompletedAt: createdAt
          })
        },
        client: {
          findFirst: vi.fn(async ({ where }: { where: { id: string } }) => (where.id === "client_1" ? clientRow : null)),
          findMany: vi.fn().mockResolvedValue([clientRow])
        },
        profileShare: {
          create: profileShareCreate,
          update: profileShareUpdate
        },
        appointment: {
          findMany,
          findFirst
        }
      }),
      photoOrigins: sources.photoOrigins,
      deleteStoredFiles: async () => undefined,
      accountName: sources.accountName,
      readSharePhoto
    });

    return { caller, profileShareCreate, profileShareUpdate, readSharePhoto };
  }

  const shareImage = async (call: Promise<{ svg: string }>) => (await call).svg.replaceAll("\u202f", " ");

  it("creates a Profile Share with the Stylist's choices and answers with them", async () => {
    const { caller, profileShareCreate } = createCaller();
    const share = await caller.clientProfile.createProfileShare({
      clientId: "client_1",
      includeColorFormulas: false,
      includeOtherPhotos: true
    });

    expect(profileShareCreate.mock.calls[0]?.[0].data).toMatchObject({
      clientId: "client_1",
      includeColorFormulas: false,
      includeAfterPhotos: true,
      includeOtherPhotos: true
    });
    expect(share).toMatchObject({
      includeColorFormulas: false,
      includeAfterPhotos: true,
      includeOtherPhotos: true
    });
  });

  it("lists a Client with the choices of the active Profile Share", async () => {
    const { caller } = createCaller({ includeAfterPhotos: false });

    expect((await caller.clientProfile.list())[0]?.activeProfileShare).toEqual({
      id: "share_1",
      token: "token_1",
      language: "ru",
      includeColorFormulas: true,
      includeAfterPhotos: false,
      includeOtherPhotos: false,
      createdAt: createdAt.toISOString()
    });
  });

  it("changes one choice of an existing Profile Share and keeps its link", async () => {
    const { caller, profileShareUpdate, profileShareCreate } = createCaller({ includeOtherPhotos: true });
    const share = await caller.clientProfile.updateProfileShare({
      clientId: "client_1",
      includeColorFormulas: false
    });

    expect(profileShareUpdate).toHaveBeenCalledWith({
      where: {
        id: "share_1"
      },
      data: {
        includeColorFormulas: false,
        includeAfterPhotos: undefined,
        includeOtherPhotos: undefined
      }
    });
    expect(share).toEqual({
      id: "share_1",
      token: "token_1",
      language: "ru",
      includeColorFormulas: false,
      includeAfterPhotos: true,
      includeOtherPhotos: true,
      createdAt: createdAt.toISOString()
    });
    expect(profileShareCreate).not.toHaveBeenCalled();
  });

  it("refuses to change a Profile Share that does not exist or was revoked", async () => {
    const { caller, profileShareUpdate } = createCaller();

    await expect(
      caller.clientProfile.updateProfileShare({ clientId: "client_1", includeColorFormulas: false })
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      message: "Profile Share not found."
    });
    await expect(
      caller.clientProfile.updateProfileShare({ clientId: "client_of_another_stylist", includeColorFormulas: false })
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      message: "Client not found."
    });
    expect(profileShareUpdate).not.toHaveBeenCalled();
  });

  it("builds the Share Image in the Stylist's timezone with the Stylist's name and the after photos", async () => {
    const { caller, readSharePhoto } = createCaller();
    const svg = await shareImage(caller.clientProfile.buildProfileShareImage({ clientId: "client_1", language: "en" }));

    expect(svg).toContain("Oct 23, 2026, 12:30 AM - 1:30 AM");
    expect(svg).toContain("Oct 5, 2026, 2:00 PM - 3:30 PM");
    expect(svg).toContain("Stylist: Maria Ivanova");
    expect(svg).toContain("Salon: 12 Salon Street");
    expect(svg).toContain("Roots: 7N + 20 vol");
    expect(readSharePhoto.mock.calls).toEqual([[`${storage}/f/after_1`], [`${storage}/f/after_2`]]);
    expect(svg.match(/<image /g)).toHaveLength(2);

    for (const hidden of ["+15551234567", "anna@example.com", "client note", "service note", "Olga", "olga_after", "before_1"]) {
      expect(svg).not.toContain(hidden);
    }
  });

  it("builds the Share Image with the choices of the Profile Share unless others are stated", async () => {
    const { caller, readSharePhoto } = createCaller({ includeColorFormulas: false, includeOtherPhotos: true });
    const asShared = await shareImage(caller.clientProfile.buildProfileShareImage({ clientId: "client_1", language: "en" }));

    expect(asShared).not.toContain("7N");
    expect(asShared).toContain(">Services<");
    expect(readSharePhoto.mock.calls.map(([url]) => url.split("/").pop())).toEqual(["after_1", "after_2", "before_1", "other_1"]);

    readSharePhoto.mockClear();

    const asStated = await shareImage(
      caller.clientProfile.buildProfileShareImage({
        clientId: "client_1",
        language: "en",
        includeColorFormulas: true,
        includeAfterPhotos: false,
        includeOtherPhotos: false
      })
    );

    expect(asStated).toContain("Roots: 7N + 20 vol");
    expect(asStated).not.toContain("<image");
    expect(asStated).not.toContain(">Photos<");
    expect(readSharePhoto).not.toHaveBeenCalled();
  });
});
