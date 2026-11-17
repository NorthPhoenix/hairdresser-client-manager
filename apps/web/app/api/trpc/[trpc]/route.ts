// First: sets up telemetry before the first request (server/telemetry.ts says why).
import "../../../../server/telemetry";
// Second: starts the database as soon as its client has loaded (server/warmDatabase.ts says why).
import { untilDatabaseWarmedUp } from "../../../../server/warmDatabase";
import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { auth } from "@clerk/nextjs/server";
import { appRouter, appVersionFromHeaders, createPublishedReleaseReader, createTRPCContext, manifestUrlFor, recordProcedureError } from "@hcm/api";
import { annotate, requestFields } from "@hcm/telemetry";
import type { UTApi } from "uploadthing/server";
import { accountName, photoOrigins, readSharePhoto } from "../../../../server/profileShare";

// The release whose APK is published: latest.json beside APP_DOWNLOAD_URL, read at most once a
// minute by each server instance (docs/adr/0018).
const releaseManifestUrl = manifestUrlFor(process.env.APP_DOWNLOAD_URL);
const publishedRelease = releaseManifestUrl ? createPublishedReleaseReader({ manifestUrl: releaseManifestUrl }) : null;

// UploadThing's SDK, loaded the first time a file is deleted and not with the route: it is about a
// sixth of what a new server instance loads before its first request, which nearly never deletes
// a photo.
let uploadThing: Promise<UTApi> | undefined;

// The files of deleted Appointment Photos are deleted there too. The router logs a failure.
async function deleteStoredFiles(fileKeys: string[]) {
  uploadThing ??= import("uploadthing/server").then(
    ({ UTApi }) => new UTApi(),
    (error: unknown) => {
      // Tried again with the next deletion.
      uploadThing = undefined;
      throw error;
    }
  );

  const { success } = await (await uploadThing).deleteFiles(fileKeys);

  if (!success) {
    throw new Error("UploadThing did not delete the files.");
  }
}

function handler(request: Request) {
  // The request's wide event is the root span that Next.js opened for it (docs/observability.md).
  annotate(requestFields(request.headers));

  return fetchRequestHandler({
    endpoint: "/api/trpc",
    req: request,
    router: appRouter,
    createContext: () =>
      createTRPCContext({
        auth: async () => {
          const clerkAuth = await auth();

          // Signed in, so its procedure uses the database next. On a new instance it waits for the
          // warm-up while that is under way: Prisma has started by then, and one such request takes
          // the warm-up's connection.
          if (clerkAuth.userId) {
            await untilDatabaseWarmedUp();
          }

          return {
            userId: clerkAuth.userId
          };
        },
        photoOrigins,
        deleteStoredFiles,
        accountName: (clerkId) => accountName(clerkId, { fresh: true }),
        readSharePhoto,
        // Which Android app sent the request, so the server can tell which version a Stylist uses.
        appVersion: appVersionFromHeaders(request.headers),
        // The same address /download sends the browser to (next.config.mjs).
        appDownloadUrl: process.env.APP_DOWNLOAD_URL,
        publishedRelease
      }),
    // The reply to an unexpected failure says nothing about it, so the request's event has to.
    onError: recordProcedureError
  });
}

export { handler as GET, handler as POST };
