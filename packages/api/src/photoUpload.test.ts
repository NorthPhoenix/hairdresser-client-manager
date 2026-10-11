import { describe, expect, it, vi } from "vitest";
import type { Db } from "./context";
import { appointmentPhotoFiles, authorizePhotoUpload } from "./photoUpload";

const input = {
  appointmentId: "appointment_1",
  clientId: "client_1",
  category: "after"
} as const;

// A database with one Appointment of the Stylist `clerk_1`, with the Client `client_1`.
function createDb() {
  const findFirst = vi.fn(async ({ where }: { where: { id: string; stylist: { clerkId: string } } }) =>
    where.id === "appointment_1" && where.stylist.clerkId === "clerk_1"
      ? {
          id: "appointment_1",
          participants: [
            {
              clientId: "client_1"
            }
          ]
        }
      : null
  );

  return {
    findFirst,
    db: {
      appointment: {
        findFirst
      }
    } as unknown as Db
  };
}

describe("who may upload an Appointment Photo", () => {
  it("lets the Stylist upload for a Client of their own Appointment", async () => {
    await expect(authorizePhotoUpload(createDb().db, "clerk_1", input)).resolves.toEqual({
      metadata: {
        userId: "clerk_1",
        appointmentId: "appointment_1",
        clientId: "client_1",
        category: "after"
      }
    });
  });

  // Each refusal has a code of its own: the route answers 403, 404 and 400, and never 500.
  it("refuses a caller who is not signed in before it reads anything", async () => {
    const { db, findFirst } = createDb();

    for (const clerkUserId of [null, undefined, ""]) {
      await expect(authorizePhotoUpload(db, clerkUserId, input)).resolves.toEqual({
        refusal: {
          code: "FORBIDDEN",
          message: "Authentication required."
        }
      });
    }

    expect(findFirst).not.toHaveBeenCalled();
  });

  it("refuses an Appointment that no longer exists, and one of another Stylist, as not found", async () => {
    const notFound = {
      refusal: {
        code: "NOT_FOUND",
        message: "Appointment not found."
      }
    };

    await expect(authorizePhotoUpload(createDb().db, "clerk_1", { ...input, appointmentId: "deleted" })).resolves.toEqual(notFound);
    await expect(authorizePhotoUpload(createDb().db, "clerk_2", input)).resolves.toEqual(notFound);
  });

  it("refuses a Client who is not a Client of the Appointment", async () => {
    await expect(authorizePhotoUpload(createDb().db, "clerk_1", { ...input, clientId: "client_2" })).resolves.toEqual({
      refusal: {
        code: "BAD_REQUEST",
        message: "Photo Client must be attached to the Appointment."
      }
    });
  });
});

describe("what the upload route accepts", () => {
  it("is JPEG by its full type, never the whole image group that includes SVG", () => {
    expect(Object.keys(appointmentPhotoFiles)).toEqual(["image/jpeg"]);
  });
});
