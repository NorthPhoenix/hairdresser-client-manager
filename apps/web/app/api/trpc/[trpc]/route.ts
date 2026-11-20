// First: sets up telemetry before the first request (server/telemetry.ts says why).
import "../../../../server/telemetry";
// Second: starts the database as soon as its client has loaded (server/warmDatabase.ts says why).
import { untilDatabaseWarmedUp } from "../../../../server/warmDatabase";
// Third: starts reading the published release, latest.json beside APP_DOWNLOAD_URL (docs/adr/0018),
// as soon as the API has loaded (server/publishedRelease.ts says why).
import { publishedRelease, releaseReadAheadUnderWay } from "../../../../server/publishedRelease";
import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { auth } from "@clerk/nextjs/server";
import { after } from "next/server";
import { appRouter, appVersionFromHeaders, createTRPCContext, recordProcedureError } from "@hcm/api";
import { annotate, recordError, requestFields } from "@hcm/telemetry";
import type { UTApi } from "uploadthing/server";
import { accountName, photoOrigins, readSharePhoto } from "../../../../server/profileShare";

// UploadThing's SDK, loaded the first time a file is deleted and not with the route: it is about a
// sixth of what a new server instance loads before its first request, which nearly never deletes
// a photo.
let uploadThing: Promise<UTApi> | undefined;

// The files of deleted Appointment Photos are deleted there too. The router logs a failure.
async function deleteStoredFiles(fileKeys: string[]) {
  uploadThing ??= import("uploadthing/server")
    .then(({ UTApi }) => new UTApi())
    .catch((error: unknown) => {
      // Tried again with the next deletion.
      uploadThing = undefined;
      throw error;
    });

  const { success } = await (await uploadThing).deleteFiles(fileKeys);

  if (!success) {
    throw new Error("UploadThing did not delete the files.");
  }
}

function handler(request: Request) {
  // The request's wide event is the root span that Next.js opened for it (docs/observability.md).
  annotate(requestFields(request.headers));

  // The first request of a new instance keeps it running until its reading of the published
  // release has ended, which would otherwise stop half-way once this request has been answered.
  const readingAhead = releaseReadAheadUnderWay();

  if (readingAhead) {
    try {
      after(readingAhead);
    } catch (error) {
      // A host that cannot keep work running after a response (Next.js throws then): the reading
      // goes on as far as the instance lets it, and this request is answered as before.
      recordError(error, { handled: true, reason: "release_read_ahead_not_kept" });
    }
  }

  return fetchRequestHandler({
    endpoint: "/api/trpc",
    req: request,
    router: appRouter,
    createContext: () =>
      createTRPCContext({
        auth: async () => {
          const clerkAuth = await auth();

          // Signed in, so its procedure uses the database next. The first such request on a new
          // instance waits for the warm-up while that is under way, and takes its connection.
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
