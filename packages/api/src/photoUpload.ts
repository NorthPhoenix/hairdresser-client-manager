import { annotate, recordError } from "@hcm/telemetry";
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

// Records a refusal with its reason, which is what the upload route then answers with its code.
function refuse(reason: string, refusal: PhotoUploadRefusal) {
  recordError(new Error(refusal.message), { expected: true, code: refusal.code, reason });
  annotate({ "upload.decision": "refused", "upload.refusal_code": refusal.code });

  return { refusal };
}

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
  annotate({
    "appointment.id": input.appointmentId,
    "client.id": input.clientId,
    "photo.category": input.category,
    "auth.signed_in": Boolean(clerkUserId)
  });

  if (!clerkUserId) {
    return refuse("not_signed_in", {
      code: "FORBIDDEN",
      message: "Authentication required."
    });
  }

  annotate({ "auth.user_id": clerkUserId });

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
    // Not found covers an Appointment that is another Stylist's too: the answer does not say which.
    return refuse("appointment_not_found", {
      code: "NOT_FOUND",
      message: "Appointment not found."
    });
  }

  annotate({
    "stylist.id": appointment.stylistId,
    "appointment.status": appointment.status,
    "appointment.client_count": appointment.participants.length
  });

  if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
    return refuse("client_not_attached", {
      code: "BAD_REQUEST",
      message: "Photo Client must be attached to the Appointment."
    });
  }

  annotate({ "upload.decision": "authorized" });

  return {
    metadata: {
      userId: clerkUserId,
      appointmentId: input.appointmentId,
      clientId: input.clientId,
      category: input.category
    }
  };
}
