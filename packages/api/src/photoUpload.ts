import type { Db } from "./context";

/**
 * What the upload route accepts for an Appointment Photo: JPEG, which is what the app makes of
 * every picture before it uploads it. The type is named in full on purpose. The UploadThing group
 * "image" lets every `image/*` through, also `image/svg+xml`, which the storage would then serve
 * as a document that can carry a script.
 */
export const appointmentPhotoFiles = {
  "image/jpeg": {
    maxFileSize: "8MB",
    maxFileCount: 10
  }
} as const;

export type PhotoUploadInput = {
  appointmentId: string;
  clientId: string;
  category: "before" | "after" | "other";
};

/**
 * Why an upload may not start, as the upload route answers it: `code` is an UploadThing error
 * code and decides the HTTP status (403, 404, 400). Without a code the route would answer 500.
 */
export type PhotoUploadRefusal = {
  code: "FORBIDDEN" | "NOT_FOUND" | "BAD_REQUEST";
  message: string;
};

/**
 * Decides whether a caller may upload an Appointment Photo, before the upload route hands out an
 * address to upload to: the caller is signed in, the Appointment is theirs and still exists, and
 * the Client is one of its Clients. The messages are those of `appointment.addPhoto`, which checks
 * the same again when the photo is recorded.
 */
export async function authorizePhotoUpload(
  db: Db,
  clerkUserId: string | null | undefined,
  input: PhotoUploadInput
): Promise<{ refusal: PhotoUploadRefusal } | { metadata: PhotoUploadInput & { userId: string } }> {
  if (!clerkUserId) {
    return {
      refusal: {
        code: "FORBIDDEN",
        message: "Authentication required."
      }
    };
  }

  const appointment = await db.appointment.findFirst({
    where: {
      id: input.appointmentId,
      stylist: {
        clerkId: clerkUserId
      }
    },
    include: {
      participants: true
    }
  });

  if (!appointment) {
    return {
      refusal: {
        code: "NOT_FOUND",
        message: "Appointment not found."
      }
    };
  }

  if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
    return {
      refusal: {
        code: "BAD_REQUEST",
        message: "Photo Client must be attached to the Appointment."
      }
    };
  }

  return {
    metadata: {
      userId: clerkUserId,
      appointmentId: input.appointmentId,
      clientId: input.clientId,
      category: input.category
    }
  };
}
