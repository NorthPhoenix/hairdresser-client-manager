import { auth } from "@clerk/nextjs/server";
import { appointmentPhotoFiles, authorizePhotoUpload } from "@hcm/api";
import { db } from "@hcm/db";
import { annotate } from "@hcm/telemetry";
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
    .middleware(async ({ input, files }) => {
      // What the Stylist's phone asks to send. Never the names of the files.
      annotate({
        "upload.file_count": files.length,
        "upload.total_bytes": files.reduce((sum, file) => sum + file.size, 0),
        "upload.largest_bytes": files.reduce((largest, file) => Math.max(largest, file.size), 0),
        "upload.file_type": files[0]?.type
      });

      const upload = await authorizePhotoUpload(db, (await auth()).userId, input);

      // With its code a refusal is answered 403, 404 or 400. A bare message would be a 500.
      if ("refusal" in upload) {
        throw new UploadThingError(upload.refusal);
      }

      return upload.metadata;
    })
    .onUploadComplete(({ metadata, file }) => {
      // The storage service reports that the file arrived. Neither its key nor its address is recorded.
      annotate({
        "upload.completed": true,
        "appointment.id": metadata.appointmentId,
        "client.id": metadata.clientId,
        "photo.category": metadata.category,
        "upload.file_size_bytes": file.size,
        "upload.file_type": file.type
      });

      return {
        appointmentId: metadata.appointmentId,
        clientId: metadata.clientId,
        category: metadata.category,
        fileKey: file.key,
        url: file.ufsUrl,
        thumbnailUrl: file.ufsUrl
      };
    })
} satisfies FileRouter;

export type UploadRouter = typeof uploadRouter;
