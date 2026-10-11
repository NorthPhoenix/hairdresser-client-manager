import { describe, expect, it, vi } from "vitest";
import { maxAmountCents, textLimits, toOneLine } from "./input";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
import { firstIssueOf, withTransaction } from "./testSupport";

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

// A database that stores nothing: these tests are about what is refused before anything is
// written, and about the cleaned values that reach a create.
function createCaller() {
  const row = {
    id: "row_1",
    stylistId: "stylist_1",
    name: "",
    language: "en",
    defaultPriceCents: 0,
    createdAt: new Date(),
    updatedAt: new Date()
  };
  const db = {
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist),
      findUnique: vi.fn().mockResolvedValue(stylist),
      update: vi.fn().mockResolvedValue(stylist)
    },
    client: {
      create: vi.fn().mockResolvedValue(row),
      findMany: vi.fn().mockResolvedValue([])
    },
    serviceMenuItem: {
      create: vi.fn().mockResolvedValue(row)
    },
    appointment: {
      updateMany: vi.fn()
    }
  };
  const caller = appRouter.createCaller({
    clerkAuth: {
      userId: "clerk_1"
    },
    db: withTransaction(db),
    photoOrigins: []
  } as unknown as TRPCContext);

  return {
    caller,
    db
  };
}

const tooLong = (limit: number) => "x".repeat(limit + 1);

describe("text limits", () => {
  it.each([
    ["Client name", "name", { name: tooLong(textLimits.name) }],
    ["Client phone", "phone", { name: "Anna", phone: tooLong(textLimits.phone) }],
    ["Client email", "email", { name: "Anna", email: `${tooLong(textLimits.email)}@example.com` }],
    ["Client address", "address", { name: "Anna", address: tooLong(textLimits.address) }],
    ["Client Note", "note", { name: "Anna", note: tooLong(textLimits.note) }]
  ])("refuses a %s that is too long", async (_label, field, input) => {
    const { caller, db } = createCaller();

    await expect(firstIssueOf(caller.clientProfile.save(input))).resolves.toEqual({
      code: "BAD_REQUEST",
      message: "Text is too long.",
      field
    });
    expect(db.client.create).not.toHaveBeenCalled();
  });

  it("refuses every other free text above its limit", async () => {
    const { caller } = createCaller();
    const appointmentId = "appointment_1";
    const clientId = "client_1";
    const startsAt = "2026-06-13T15:00:00.000Z";
    const refused = [
      [() => caller.clientProfile.list({ search: tooLong(textLimits.name) }), "search"],
      [() => caller.stylist.saveSettings({ language: "en", timezone: "UTC", salonAddress: tooLong(textLimits.address) }), "salonAddress"],
      [() => caller.stylist.bootstrap({ deviceTimezone: tooLong(textLimits.timezone) }), "deviceTimezone"],
      [() => caller.serviceMenu.save({ name: tooLong(textLimits.name) }), "name"],
      [() => caller.appointment.create({ inlineClientName: tooLong(textLimits.name), startsAt }), "inlineClientName"],
      [
        () => caller.appointment.create({ primaryClientId: clientId, startsAt, customLocationAddress: tooLong(textLimits.address) }),
        "customLocationAddress"
      ],
      [() => caller.appointment.update({ id: appointmentId, note: tooLong(textLimits.note) }), "note"],
      [() => caller.appointment.update({ id: appointmentId, customLocationAddress: tooLong(textLimits.address) }), "customLocationAddress"],
      [() => caller.appointment.addService({ appointmentId, clientId, name: tooLong(textLimits.name) }), "name"],
      [() => caller.appointment.addService({ appointmentId, clientId, name: "Cut", note: tooLong(textLimits.note) }), "note"],
      [() => caller.appointment.updateService({ id: "service_1", name: tooLong(textLimits.name), priceCents: 0 }), "name"],
      [() => caller.appointment.updateService({ id: "service_1", name: "Cut", priceCents: 0, note: tooLong(textLimits.note) }), "note"],
      [() => caller.appointment.addColorFormula({ appointmentServiceId: "service_1", formula: tooLong(textLimits.formula) }), "formula"],
      [
        () => caller.appointment.addColorFormula({ appointmentServiceId: "service_1", formula: "7N", placement: tooLong(textLimits.placement) }),
        "placement"
      ],
      [() => caller.appointment.updateColorFormula({ id: "formula_1", formula: tooLong(textLimits.formula) }), "formula"],
      [
        () => caller.appointment.updateColorFormula({ id: "formula_1", formula: "7N", placement: tooLong(textLimits.placement) }),
        "placement"
      ],
      [() => caller.appointment.addPhoto({ appointmentId, clientId, category: "after", status: "failed", uploadError: tooLong(500) }), "uploadError"],
      [() => caller.appointment.addPhoto({ appointmentId, clientId, category: "after", fileKey: tooLong(200), url: "https://utfs.io/f/a" }), "fileKey"],
      [() => caller.appointment.addPhoto({ appointmentId, clientId, category: "after", fileKey: "a", url: `https://utfs.io/f/${tooLong(2000)}` }), "url"],
      [() => caller.appointment.updatePhoto({ id: "photo_1", uploadError: tooLong(500) }), "uploadError"]
    ] as const;

    for (const [call, field] of refused) {
      await expect(firstIssueOf(call()), field).resolves.toEqual({
        code: "BAD_REQUEST",
        message: "Text is too long.",
        field
      });
    }
  });

  it("accepts text of exactly the limit, counted after trimming", async () => {
    const { caller, db } = createCaller();
    const name = "x".repeat(textLimits.name);
    const note = "y".repeat(textLimits.note);

    await caller.clientProfile.save({
      name: `  ${name}  `,
      note: `\n${note}\n`
    });

    expect(db.client.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        name,
        note
      })
    });
  });
});

describe("one-line text", () => {
  it.each([
    ["a pasted line break", "Paste Test\nsecond line", "Paste Test second line"],
    ["a Windows line break with spaces around it", "Anna \r\n  Petrova", "Anna Petrova"],
    ["a vertical tab from a device contact", "Kim\u000bVert Importa", "Kim Vert Importa"],
    ["a tab", "Anna\tPetrova", "Anna Petrova"],
    ["a line separator", "Anna\u2028Petrova", "Anna Petrova"],
    ["an invisible control character", "Zoe\u0001Ctrl", "ZoeCtrl"],
    ["control characters at the ends", "\u0001 Anna \u007f", "Anna"]
  ])("stores a Client name with %s as one clean line", async (_label, typed, stored) => {
    const { caller, db } = createCaller();

    await caller.clientProfile.save({
      name: typed
    });

    expect(db.client.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        name: stored
      })
    });
  });

  it("makes one space of a run of line breaks with the spaces around it", async () => {
    const { caller, db } = createCaller();

    await caller.clientProfile.save({
      name: "  Anna \n \r\n\t  Maria\u0085\u0085Petrova  \n",
      phone: `${" ".repeat(4000)}+1 512\n${" ".repeat(4000)}555  0100\n`
    });

    expect(db.client.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        name: "Anna Maria Petrova",
        // Spaces that are not next to a line break stay as they were typed.
        phone: "+1 512 555  0100"
      })
    });
  });

  it("refuses a one-line text far over its limit, also when it is all spaces", async () => {
    const { caller, db } = createCaller();
    const refusedAs = (field: string) => ({
      code: "BAD_REQUEST",
      message: "Text is too long.",
      field
    });

    // Cleaning these took the server minutes before it was told how long they are.
    for (const name of [" ".repeat(1_000_000), `x${" ".repeat(200_000)}y`, `Anna${" ".repeat(10_000)}`]) {
      await expect(firstIssueOf(caller.clientProfile.save({ name }))).resolves.toEqual(refusedAs("name"));
    }

    await expect(firstIssueOf(caller.clientProfile.save({ name: "Anna", phone: " \n".repeat(100_000) }))).resolves.toEqual(
      refusedAs("phone")
    );
    await expect(firstIssueOf(caller.serviceMenu.save({ name: `x${" ".repeat(200_000)}y` }))).resolves.toEqual(
      refusedAs("name")
    );
    expect(db.client.create).not.toHaveBeenCalled();
    expect(db.serviceMenuItem.create).not.toHaveBeenCalled();
  });

  it("cleans a megabyte in the time it takes to read it", () => {
    const startedAt = performance.now();

    expect(toOneLine(" ".repeat(1_000_000))).toBe("");
    expect(toOneLine(`x${" ".repeat(1_000_000)}y`)).toHaveLength(1_000_002);
    expect(toOneLine(" \n".repeat(500_000))).toBe("");
    expect(toOneLine("a\n".repeat(500_000))).toBe(`${"a ".repeat(499_999)}a`);
    expect(toOneLine("\u0001".repeat(1_000_000))).toBe("");
    // Milliseconds. The pattern this replaced needed five seconds for 80,000 spaces.
    expect(performance.now() - startedAt).toBeLessThan(2000);
  });

  it("keeps the characters that join letters and emoji", async () => {
    const { caller, db } = createCaller();
    const family = "\u{1F469}\u200d\u{1F467}";

    await caller.clientProfile.save({
      name: `${family} Нина`
    });

    expect(db.client.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        name: `${family} Нина`
      })
    });
  });

  it.each([
    ["only a control character", "\u0001"],
    ["only line breaks", "\n\r\n"],
    ["only a zero-width space", "\u200b"],
    ["only invisible formatting characters", "\u200e\u2060\ufeff"]
  ])("refuses a name of %s like an empty one", async (_label, name) => {
    const { caller, db } = createCaller();

    await expect(caller.clientProfile.save({ name })).rejects.toMatchObject({
      code: "BAD_REQUEST"
    });
    await expect(caller.serviceMenu.save({ name })).rejects.toMatchObject({
      code: "BAD_REQUEST"
    });
    expect(db.client.create).not.toHaveBeenCalled();
    expect(db.serviceMenuItem.create).not.toHaveBeenCalled();
  });

  it("names the rule when a name has no visible character", async () => {
    const { caller } = createCaller();

    await expect(firstIssueOf(caller.clientProfile.save({ name: "\u200b" }))).resolves.toEqual({
      code: "BAD_REQUEST",
      message: "Name is required.",
      field: "name"
    });
  });

  it("cleans the phone and the Service Menu Item name the same way and keeps line breaks in a note", async () => {
    const { caller, db } = createCaller();

    await caller.clientProfile.save({
      name: "Anna",
      phone: "+1 512\n555 0100",
      address: "742 Evergreen Terrace\nAustin, TX",
      note: "Line one\nLine two"
    });
    await caller.serviceMenu.save({
      name: "Root\ncolor\u0001"
    });

    expect(db.client.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        phone: "+1 512 555 0100",
        address: "742 Evergreen Terrace\nAustin, TX",
        note: "Line one\nLine two"
      })
    });
    expect(db.serviceMenuItem.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        name: "Root color"
      })
    });
  });
});

describe("email", () => {
  it.each(["not an email@@", "plainaddress", "anna@", "@example.com", "anna@example", "anna@example..com", "anna @example.com"])(
    "refuses %s",
    async (email) => {
      const { caller, db } = createCaller();

      await expect(firstIssueOf(caller.clientProfile.save({ name: "Eve Invalid", email }))).resolves.toEqual({
        code: "BAD_REQUEST",
        message: "Email is not valid.",
        field: "email"
      });
      expect(db.client.create).not.toHaveBeenCalled();
    }
  );

  it.each(["", "  ", "anna@example.com", " anna.petrova+salon@mail.example.co.uk ", "анна@почта.рф"])("accepts %j", async (email) => {
    const { caller, db } = createCaller();

    await caller.clientProfile.save({
      name: "Anna",
      email
    });

    expect(db.client.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        email: email.trim()
      })
    });
  });
});

describe("timezone", () => {
  it.each(["Mars/Phobos", "america/chicago", "+03:00", "GMT+03:00", "12345", "Not a zone"])(
    "refuses %s as the Stylist's timezone",
    async (timezone) => {
      const { caller, db } = createCaller();

      await expect(firstIssueOf(caller.stylist.saveSettings({ language: "ru", timezone, salonAddress: "x" }))).resolves.toEqual({
        code: "BAD_REQUEST",
        message: "Timezone is not valid.",
        field: "timezone"
      });
      expect(db.stylist.update).not.toHaveBeenCalled();
    }
  );

  it.each(["America/Chicago", "Europe/Moscow", "Asia/Kolkata", "US/Central", "UTC", "Etc/GMT+5"])(
    "accepts %s as the Stylist's timezone",
    async (timezone) => {
      const { caller, db } = createCaller();

      await caller.stylist.saveSettings({
        language: "ru",
        timezone
      });

      expect(db.stylist.update).toHaveBeenCalledWith({
        where: {
          id: "stylist_1"
        },
        data: expect.objectContaining({
          timezone
        })
      });
    }
  );
});

describe("amounts and other numbers", () => {
  it("refuses an amount above the maximum wherever an amount is sent", async () => {
    const { caller, db } = createCaller();
    const tooMuch = maxAmountCents + 1;
    const refused = [
      [() => caller.serviceMenu.save({ name: "Typo Price", defaultPriceCents: tooMuch }), "defaultPriceCents"],
      [() => caller.appointment.addService({ appointmentId: "appointment_1", clientId: "client_1", name: "Typo Price", priceCents: tooMuch }), "priceCents"],
      [() => caller.appointment.updateService({ id: "service_1", name: "Typo Price", priceCents: tooMuch }), "priceCents"],
      [() => caller.appointment.update({ id: "appointment_1", finalTotalCentsOverride: tooMuch }), "finalTotalCentsOverride"],
      // What the app sent for a price it could not hold, and one past the 32-bit limit.
      [() => caller.serviceMenu.save({ name: "Typo Price", defaultPriceCents: 2_147_483_647 }), "defaultPriceCents"],
      [() => caller.serviceMenu.save({ name: "Typo Price", defaultPriceCents: 2_147_483_648 }), "defaultPriceCents"]
    ] as const;

    for (const [call, field] of refused) {
      await expect(firstIssueOf(call()), field).resolves.toEqual({
        code: "BAD_REQUEST",
        message: "Amount is too large.",
        field
      });
    }

    expect(db.serviceMenuItem.create).not.toHaveBeenCalled();
  });

  it("accepts the maximum amount", async () => {
    const { caller, db } = createCaller();

    await caller.serviceMenu.save({
      name: "Extensions",
      defaultPriceCents: maxAmountCents
    });

    expect(db.serviceMenuItem.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        defaultPriceCents: maxAmountCents
      })
    });
  });

  it("refuses photo dimensions the database cannot hold", async () => {
    const { caller } = createCaller();
    const photo = {
      appointmentId: "appointment_1",
      clientId: "client_1",
      category: "after",
      fileKey: "ut_file_1",
      url: "https://utfs.io/f/ut_file_1"
    } as const;

    await expect(caller.appointment.addPhoto({ ...photo, width: 2_147_483_648 })).rejects.toMatchObject({
      code: "BAD_REQUEST"
    });
    await expect(caller.appointment.addPhoto({ ...photo, height: 2_147_483_648 })).rejects.toMatchObject({
      code: "BAD_REQUEST"
    });
  });

  it("refuses a request key that is not one", async () => {
    const { caller, db } = createCaller();

    for (const requestKey of ["short", "has spaces in the key 123", "x".repeat(65)]) {
      await expect(firstIssueOf(caller.clientProfile.save({ name: "Anna", requestKey }))).resolves.toEqual({
        code: "BAD_REQUEST",
        message: "Request key is not valid.",
        field: "requestKey"
      });
    }

    expect(db.client.create).not.toHaveBeenCalled();
  });
});
