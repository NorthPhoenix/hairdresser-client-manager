import { auth } from "@clerk/nextjs/server";
import { appointmentPhotoFiles, authorizePhotoUpload } from "@hcm/api";
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
  appointmentPhoto: f(appointmentPhotoFiles)
    .input(appointmentPhotoInput)
    .middleware(async ({ input }) => {
      const upload = await authorizePhotoUpload(db, (await auth()).userId, input);

      // With its code a refusal is answered 403, 404 or 400. A bare message would be a 500.
      if ("refusal" in upload) {
        throw new UploadThingError(upload.refusal);
      }

      return upload.metadata;
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
