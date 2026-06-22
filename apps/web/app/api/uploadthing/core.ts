import { auth } from "@clerk/nextjs/server";
import { db } from "@hcm/db";
import { z } from "zod";
import { createUploadthing, type FileRouter } from "uploadthing/next";
import { UploadThingError } from "uploadthing/server";

const f = createUploadthing();

const appointmentPhotoInput = z.object({
  appointmentId: z.string(),
  clientId: z.string(),
  category: z.enum(["before", "after", "other"])
});

export const uploadRouter = {
  appointmentPhoto: f({
    image: {
      maxFileSize: "8MB",
      maxFileCount: 10
    }
  })
    .input(appointmentPhotoInput)
    .middleware(async ({ input }) => {
      const clerkAuth = await auth();

      if (!clerkAuth.userId) {
        throw new UploadThingError("Authentication required.");
      }

      const appointment = await db.appointment.findFirst({
        where: {
          id: input.appointmentId,
          stylist: {
            clerkId: clerkAuth.userId
          }
        },
        include: {
          participants: true
        }
      });

      if (!appointment) {
        throw new UploadThingError("Appointment not found.");
      }

      if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
        throw new UploadThingError("Photo Client must be attached to the Appointment.");
      }

      return {
        userId: clerkAuth.userId,
        appointmentId: input.appointmentId,
        clientId: input.clientId,
        category: input.category
      };
    })
    .onUploadComplete(({ metadata, file }) => ({
      appointmentId: metadata.appointmentId,
      clientId: metadata.clientId,
      category: metadata.category,
      fileKey: file.key,
      url: file.ufsUrl,
      thumbnailUrl: file.ufsUrl
    }))
} satisfies FileRouter;

export type UploadRouter = typeof uploadRouter;
