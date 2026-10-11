import { describe, expect, it, vi } from "vitest";
import { inAddedOrder } from "./addedOrder";
import { appRouter } from "./root";
import type { TRPCContext } from "./context";
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

type AppointmentRow = {
  id: string;
  stylistId: string;
  status: string;
  startsAt: Date;
  clientIds: string[];
};
type ServiceRow = {
  id: string;
  appointmentId: string;
  clientId: string;
  menuItemId: string | null;
  name: string;
  priceCents: number;
  note: string;
  requestKey?: string | null;
  createdAt: Date;
};
type FormulaRow = {
  id: string;
  appointmentServiceId: string;
  formula: string;
  placement: string;
  createdAt: Date;
};
// The condition that getCopySourceFilter builds, as far as these tests need it.
type AppointmentWhere = {
  id?: string | { not: string };
  stylistId?: string;
  status?: string;
  OR?: { participants: { some: { clientId: string } }; services: { some: { clientId: string } } }[];
};

const at = (time: string) => new Date(`2026-10-10T${time}:00.000Z`);

/**
 * A working day kept in memory: Anna's and Bob's completed Appointments, and one empty Scheduled
 * Appointment each for Anna, Bob and Dan. It answers the Appointment conditions the way the
 * database does, and returns Services and Color Formulas newest row first: their order in a reply
 * must not depend on the order the database happens to return them in.
 */
function createDay() {
  const appointment = (id: string, time: string, status: string, clientIds: string[]): AppointmentRow => ({
    id,
    stylistId: "stylist_1",
    status,
    startsAt: at(time),
    clientIds
  });
  const service = (id: string, appointmentId: string, clientId: string, name: string, priceCents: number, time: string): ServiceRow => ({
    id,
    appointmentId,
    clientId,
    menuItemId: null,
    name,
    priceCents,
    note: "",
    createdAt: at(time)
  });
  const rows = {
    appointments: [
      appointment("anna_0609", "06:09", "completed", ["anna"]),
      appointment("anna_0717", "07:17", "completed", ["anna"]),
      appointment("anna_bob_1019", "10:19", "completed", ["anna", "bob"]),
      appointment("bob_1127", "11:27", "completed", ["bob"]),
      appointment("target_anna", "13:31", "scheduled", ["anna"]),
      appointment("target_bob", "13:39", "scheduled", ["bob"]),
      appointment("target_dan", "13:47", "scheduled", ["dan"]),
      { ...appointment("other_stylist", "12:00", "completed", ["anna"]), stylistId: "stylist_2" }
    ],
    services: [
      service("gloss", "anna_0717", "anna", "Gloss", 4999, "07:20"),
      service("cut", "anna_0717", "anna", "Cut", 5500, "07:40"),
      service("anna_color", "anna_bob_1019", "anna", "Anna Color", 12050, "10:20"),
      service("bob_beard", "anna_bob_1019", "bob", "Bob Beard", 2500, "10:30"),
      service("bob_fade", "bob_1127", "bob", "Bob Fade", 4000, "11:30"),
      service("other_cut", "other_stylist", "anna", "Other Cut", 100, "12:00")
    ],
    // The two formulas of Gloss share their timestamp, as an earlier copy left them.
    formulas: [
      { id: "formula_roots", appointmentServiceId: "gloss", formula: "6N + 7N 1:1", placement: "Roots", createdAt: at("07:20") },
      { id: "formula_toner", appointmentServiceId: "gloss", formula: "Toner 9V", placement: "", createdAt: at("07:20") },
      { id: "formula_mids", appointmentServiceId: "anna_color", formula: "7N + 20 vol", placement: "Mids", createdAt: at("10:20") }
    ] as FormulaRow[]
  };
  const client = (id: string) => ({
    id,
    name: id,
    address: ""
  });
  const servicesOf = (appointmentId: string) => rows.services.filter((row) => row.appointmentId === appointmentId);
  const read = (row: AppointmentRow) => ({
    ...row,
    primaryClientId: row.clientIds[0] ?? "",
    endsAt: null,
    locationType: "inSalon",
    locationAddress: "500 Salon Ave",
    locationAddressIsCustom: false,
    note: "",
    finalTotalCentsOverride: null,
    primaryClient: client(row.clientIds[0] ?? ""),
    participants: row.clientIds.map((clientId) => ({
      clientId,
      client: client(clientId)
    })),
    services: servicesOf(row.id)
      .map((serviceRow) => ({
        ...serviceRow,
        colorFormulas: rows.formulas.filter((formula) => formula.appointmentServiceId === serviceRow.id).reverse()
      }))
      .reverse(),
    photos: []
  });
  const matches = (row: AppointmentRow, where: AppointmentWhere) =>
    (where.id === undefined || (typeof where.id === "string" ? row.id === where.id : row.id !== where.id.not)) &&
    (where.stylistId === undefined || row.stylistId === where.stylistId) &&
    (where.status === undefined || row.status === where.status) &&
    (where.OR === undefined ||
      where.OR.some(
        (condition) =>
          row.clientIds.includes(condition.participants.some.clientId) &&
          servicesOf(row.id).some((serviceRow) => serviceRow.clientId === condition.services.some.clientId)
      ));
  const find = ({ where }: { where: AppointmentWhere }) =>
    rows.appointments
      .filter((row) => matches(row, where))
      .sort((a, b) => b.startsAt.getTime() - a.startsAt.getTime())
      .map(read);
  const db = withTransaction({
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist)
    },
    appointment: {
      findFirst: vi.fn(async (args: { where: AppointmentWhere }) => find(args)[0] ?? null),
      findMany: vi.fn(async (args: { where: AppointmentWhere }) => find(args)),
      findUniqueOrThrow: vi.fn(async (args: { where: AppointmentWhere }) => find(args)[0])
    },
    appointmentService: {
      create: vi.fn(async ({ data }: { data: Omit<ServiceRow, "id"> & { colorFormulas: { create: Omit<FormulaRow, "id" | "appointmentServiceId">[] } } }) => {
        const { colorFormulas, ...serviceRow } = data;
        const id = `copied_service_${rows.services.length + 1}`;

        rows.services.push({ ...serviceRow, id });
        rows.formulas.push(
          ...colorFormulas.create.map((formula, index) => ({
            ...formula,
            id: `copied_formula_${rows.formulas.length + index + 1}`,
            appointmentServiceId: id
          }))
        );
      })
    },
    colorFormula: {
      findFirst: vi.fn(async ({ where }: { where: { id: string } }) => {
        const formula = rows.formulas.find((row) => row.id === where.id);

        return formula && { ...formula, appointmentService: rows.services.find((row) => row.id === formula.appointmentServiceId) };
      }),
      // Like the database, an update leaves the changed row in another place among the rows.
      update: vi.fn(async ({ where, data }: { where: { id: string }; data: Partial<FormulaRow> }) => {
        const index = rows.formulas.findIndex((row) => row.id === where.id);
        const [formula] = rows.formulas.splice(index, 1);

        rows.formulas.push({ ...formula!, ...data });
      })
    }
  });
  const caller = appRouter.createCaller({
    clerkAuth: {
      userId: "clerk_1"
    },
    db,
    photoOrigins: []
  } as unknown as TRPCContext);

  return {
    caller,
    rows
  };
}

// Two more completed Appointments, newer than every Appointment with Services of Anna: one of
// hers without Services, and one with Bob in which only Bob has a Service.
function addLaterAppointmentsWithoutServicesOfAnna(rows: ReturnType<typeof createDay>["rows"]) {
  rows.appointments.push(
    { id: "anna_1200", stylistId: "stylist_1", status: "completed", startsAt: at("12:00"), clientIds: ["anna"] },
    { id: "anna_bob_1230", stylistId: "stylist_1", status: "completed", startsAt: at("12:30"), clientIds: ["anna", "bob"] }
  );
  rows.services.push({
    id: "bob_shave",
    appointmentId: "anna_bob_1230",
    clientId: "bob",
    menuItemId: null,
    name: "Bob Shave",
    priceCents: 1500,
    note: "",
    createdAt: at("12:31")
  });
}

const nothingToCopy = {
  code: "BAD_REQUEST",
  message: "Source Appointment has no Services to copy."
};

describe("Appointment Copying", () => {
  it("copies from the last completed Appointment that has Services for a Client of the target", async () => {
    const { caller } = createDay();
    // The Stylist's newest completed Appointment is Bob's. Anna's last one was together with Bob.
    const anna = await caller.appointment.copyServices({
      targetAppointmentId: "target_anna"
    });

    expect(anna.services).toMatchObject([
      {
        clientId: "anna",
        name: "Anna Color",
        priceCents: 12050,
        colorFormulas: [
          {
            formula: "7N + 20 vol",
            placement: "Mids"
          }
        ]
      }
    ]);
    expect(anna.copiedServiceCount).toBe(1);
    expect(anna.serviceTotalCents).toBe(12050);

    const bob = await caller.appointment.copyServices({
      targetAppointmentId: "target_bob"
    });

    expect(bob.services.map((service) => service.name)).toEqual(["Bob Fade"]);
  });

  it("skips a later completed Appointment in which the shared Client has no Services", async () => {
    const { caller, rows } = createDay();

    addLaterAppointmentsWithoutServicesOfAnna(rows);

    const reply = await caller.appointment.copyServices({
      targetAppointmentId: "target_anna"
    });

    expect(reply.services.map((service) => service.name)).toEqual(["Anna Color"]);
    await expect(caller.appointment.completedSources({ targetAppointmentId: "target_anna" })).resolves.toMatchObject([
      {
        id: "anna_bob_1019"
      },
      {
        id: "anna_0717"
      }
    ]);
  });

  it("says that there is nothing to copy from when no completed Appointment has Services for the target's Clients", async () => {
    const { caller, rows } = createDay();
    const servicesBefore = rows.services.length;

    await expect(
      caller.appointment.copyServices({
        targetAppointmentId: "target_dan"
      })
    ).rejects.toMatchObject({
      code: "NOT_FOUND",
      message: "Completed source Appointment not found."
    });
    expect(rows.services).toHaveLength(servicesBefore);
  });

  it("offers only the completed Appointments that have something to copy into the target", async () => {
    const { caller } = createDay();
    const forAnna = await caller.appointment.completedSources({
      targetAppointmentId: "target_anna"
    });

    // Not Bob's Appointment, not Anna's Appointment without Services, not another Stylist's.
    expect(forAnna.map((source) => source.id)).toEqual(["anna_bob_1019", "anna_0717"]);
    expect(forAnna).toMatchObject([
      {
        copyableServiceCount: 1,
        copyableClientIds: ["anna"]
      },
      {
        copyableServiceCount: 2,
        copyableClientIds: ["anna"]
      }
    ]);

    const forBob = await caller.appointment.completedSources({
      targetAppointmentId: "target_bob"
    });

    expect(forBob.map((source) => source.id)).toEqual(["bob_1127", "anna_bob_1019"]);
    expect(forBob).toMatchObject([
      {
        copyableServiceCount: 1,
        copyableClientIds: ["bob"]
      },
      {
        copyableServiceCount: 1,
        copyableClientIds: ["bob"]
      }
    ]);
    await expect(caller.appointment.completedSources({ targetAppointmentId: "target_dan" })).resolves.toEqual([]);
  });

  it("does not offer an Appointment as a source for itself", async () => {
    const { caller } = createDay();
    const sources = await caller.appointment.completedSources({
      targetAppointmentId: "anna_0717"
    });

    expect(sources.map((source) => source.id)).toEqual(["anna_bob_1019"]);
  });

  it("lists every completed Appointment when no target is named, as before", async () => {
    const { caller } = createDay();
    const sources = await caller.appointment.completedSources();

    expect(sources.map((source) => source.id)).toEqual(["bob_1127", "anna_bob_1019", "anna_0717", "anna_0609"]);
    expect(sources[0]).not.toHaveProperty("copyableServiceCount");
  });

  it("refuses a target that is not the Stylist's when listing sources", async () => {
    const { caller } = createDay();

    await expect(caller.appointment.completedSources({ targetAppointmentId: "other_stylist" })).rejects.toMatchObject({
      code: "NOT_FOUND",
      message: "Target Appointment not found."
    });
  });

  it("refuses a named source that has nothing to copy into the target and copies nothing", async () => {
    const { caller, rows } = createDay();

    addLaterAppointmentsWithoutServicesOfAnna(rows);

    const servicesBefore = rows.services.length;
    const copies = [
      // Shares no Client with the target.
      { targetAppointmentId: "target_anna", sourceAppointmentId: "bob_1127" },
      // Shares the Client, and has no Services.
      { targetAppointmentId: "target_anna", sourceAppointmentId: "anna_0609" },
      // Shares the Client, and only the other Client of the group has a Service.
      { targetAppointmentId: "target_anna", sourceAppointmentId: "anna_bob_1230" },
      // The Appointment itself.
      { targetAppointmentId: "anna_0717", sourceAppointmentId: "anna_0717" }
    ];

    for (const copy of copies) {
      await expect(caller.appointment.copyServices(copy)).rejects.toMatchObject(nothingToCopy);
    }

    expect(rows.services).toHaveLength(servicesBefore);
  });

  it("tells a source that is no longer Completed or no longer exists from having no sources at all", async () => {
    const { caller, rows } = createDay();
    const servicesBefore = rows.services.length;
    const copy = {
      targetAppointmentId: "target_anna",
      sourceAppointmentId: "anna_0717"
    };

    // Set back to Scheduled on another device while the copy sheet was open.
    rows.appointments.find((row) => row.id === "anna_0717")!.status = "scheduled";

    await expect(caller.appointment.copyServices(copy)).rejects.toMatchObject({
      code: "NOT_FOUND",
      message: "Source Appointment is no longer Completed."
    });

    rows.appointments = rows.appointments.filter((row) => row.id !== "anna_0717");

    for (const sourceAppointmentId of ["anna_0717", "other_stylist"]) {
      await expect(caller.appointment.copyServices({ ...copy, sourceAppointmentId })).rejects.toMatchObject({
        code: "NOT_FOUND",
        message: "Source Appointment not found."
      });
    }

    expect(rows.services).toHaveLength(servicesBefore);
  });

  it("reports how many Services it copied, for the Clients attached to both Appointments only", async () => {
    const { caller, rows } = createDay();

    rows.appointments.push({ id: "target_group", stylistId: "stylist_1", status: "scheduled", startsAt: at("14:00"), clientIds: ["bob", "anna", "dan"] });

    const one = await caller.appointment.copyServices({
      targetAppointmentId: "target_bob",
      sourceAppointmentId: "anna_bob_1019"
    });
    const both = await caller.appointment.copyServices({
      targetAppointmentId: "target_group",
      sourceAppointmentId: "anna_bob_1019"
    });

    expect(one.copiedServiceCount).toBe(1);
    expect(one.services.map((service) => service.name)).toEqual(["Bob Beard"]);
    expect(both.copiedServiceCount).toBe(2);
    expect(both.services.map((service) => service.name)).toEqual(["Anna Color", "Bob Beard"]);
  });

  it("keeps copied Services and Color Formulas in the order of the source, also after one is edited", async () => {
    const { caller } = createDay();
    const formulasOf = (appointment: { services: { name: string; colorFormulas: { formula: string }[] }[] }) =>
      appointment.services.map((service) => [service.name, ...service.colorFormulas.map((formula) => formula.formula)]);
    const copied = await caller.appointment.copyServices({
      targetAppointmentId: "target_anna",
      sourceAppointmentId: "anna_0717"
    });

    expect(formulasOf(copied)).toEqual([["Gloss", "6N + 7N 1:1", "Toner 9V"], ["Cut"]]);

    // Every copy has a time of its own, so no later change can reorder them.
    const [gloss, cut] = copied.services;
    const [roots, toner] = gloss!.colorFormulas;

    expect(Date.parse(gloss!.createdAt)).toBeLessThan(Date.parse(cut!.createdAt));
    expect(Date.parse(roots!.createdAt)).toBeLessThan(Date.parse(toner!.createdAt));

    const edited = await caller.appointment.updateColorFormula({
      id: roots!.id,
      formula: "6N + 7N 1:1 EDIT",
      placement: "Roots"
    });

    expect(formulasOf(edited)).toEqual([["Gloss", "6N + 7N 1:1 EDIT", "Toner 9V"], ["Cut"]]);
  });

  it("keeps Color Formulas that already share a timestamp in one order after one is edited", async () => {
    const { caller } = createDay();
    const edited = await caller.appointment.updateColorFormula({
      id: "formula_roots",
      formula: "6N + 7N 1:1 EDIT",
      placement: "Roots"
    });
    const editedAgain = await caller.appointment.updateColorFormula({
      id: "formula_toner",
      formula: "Toner 9V EDIT"
    });

    for (const reply of [edited, editedAgain]) {
      expect(reply.services[0]!.colorFormulas.map((formula) => formula.id)).toEqual(["formula_roots", "formula_toner"]);
    }
  });
});

describe("the order Services and Color Formulas were added in", () => {
  it("is by time, then by id for rows written together, whatever order they arrive in", () => {
    const row = (id: string, time: string) => ({
      id,
      createdAt: at(time)
    });
    const rows = [row("b", "09:00"), row("c", "08:00"), row("a", "09:00")];

    expect(inAddedOrder(rows).map(({ id }) => id)).toEqual(["c", "a", "b"]);
    expect(inAddedOrder([...rows].reverse()).map(({ id }) => id)).toEqual(["c", "a", "b"]);
    expect(rows.map(({ id }) => id)).toEqual(["b", "c", "a"]);
  });
});
