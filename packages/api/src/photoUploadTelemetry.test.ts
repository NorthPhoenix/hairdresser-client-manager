import { beforeEach, describe, expect, it, vi } from "vitest";

// What the upload decision writes into the request's wide event, read through a stand-in for the
// telemetry API: the fields of every `annotate` merged, and every error that was recorded.
const annotated: Record<string, unknown> = {};
const errors: { error: unknown; details: Record<string, unknown> }[] = [];

vi.mock("@hcm/telemetry", () => ({
  annotate: (fields: Record<string, unknown>) => Object.assign(annotated, fields),
  recordError: (error: unknown, details: Record<string, unknown>) => errors.push({ error, details }),
  increment: () => undefined,
  traced: (_name: string, fn: (span: { annotate: () => void }) => unknown) => fn({ annotate: () => undefined })
}));

const { authorizePhotoUpload } = await import("./photoUpload");

const input = { appointmentId: "appointment_1", clientId: "client_1", category: "before" } as const;

function dbWith(appointment: unknown) {
  return { appointment: { findFirst: vi.fn(async () => appointment) } } as never;
}

beforeEach(() => {
  Object.keys(annotated).forEach((key) => delete annotated[key]);
  errors.length = 0;
});

describe("the event of an upload decision", () => {
  it("names the records and says it was authorized", async () => {
    await authorizePhotoUpload(dbWith({ stylistId: "stylist_1", status: "completed", participants: [{ clientId: "client_1" }] }), "clerk_1", input);

    expect(annotated).toMatchObject({
      "upload.decision": "authorized",
      "appointment.id": "appointment_1",
      "client.id": "client_1",
      "photo.category": "before",
      "stylist.id": "stylist_1",
      "appointment.status": "completed",
      "appointment.client_count": 1,
      "auth.signed_in": true
    });
    expect(errors).toEqual([]);
  });

  it.each([
    ["not signed in", null, null, "not_signed_in", "FORBIDDEN"],
    ["an Appointment that is not theirs", "clerk_1", null, "appointment_not_found", "NOT_FOUND"],
    ["a Client that is not on the Appointment", "clerk_1", { stylistId: "stylist_1", status: "scheduled", participants: [] }, "client_not_attached", "BAD_REQUEST"]
  ])("records the reason of a refusal: %s", async (_label, clerkId, appointment, reason, code) => {
    await authorizePhotoUpload(dbWith(appointment), clerkId, input);

    expect(annotated).toMatchObject({ "upload.decision": "refused", "upload.refusal_code": code });
    expect(errors).toMatchObject([{ details: { expected: true, reason, code } }]);
  });
});
