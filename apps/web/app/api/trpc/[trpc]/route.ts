import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { auth } from "@clerk/nextjs/server";
import { appRouter, appVersionFromHeaders, createPublishedReleaseReader, createTRPCContext, manifestUrlFor, recordProcedureError } from "@hcm/api";
import { annotate, requestFields } from "@hcm/telemetry";
import { UTApi } from "uploadthing/server";
import { accountName, photoOrigins, readSharePhoto } from "../../../../server/profileShare";

const uploadThing = new UTApi();

// The release whose APK is published: latest.json beside APP_DOWNLOAD_URL, read at most once a
// minute by each server instance (docs/adr/0018).
const releaseManifestUrl = manifestUrlFor(process.env.APP_DOWNLOAD_URL);
const publishedRelease = releaseManifestUrl ? createPublishedReleaseReader({ manifestUrl: releaseManifestUrl }) : null;

// The files of deleted Appointment Photos are deleted there too. The router logs a failure.
async function deleteStoredFiles(fileKeys: string[]) {
  const { success } = await uploadThing.deleteFiles(fileKeys);

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
