import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { auth } from "@clerk/nextjs/server";
import { appRouter, appVersionFromHeaders, createTRPCContext } from "@hcm/api";
import { UTApi } from "uploadthing/server";
import { accountName, photoOrigins, readSharePhoto } from "../../../../server/profileShare";

const uploadThing = new UTApi();

// The files of deleted Appointment Photos are deleted there too. The router logs a failure.
async function deleteStoredFiles(fileKeys: string[]) {
  const { success } = await uploadThing.deleteFiles(fileKeys);

  if (!success) {
    throw new Error("UploadThing did not delete the files.");
  }
}

function handler(request: Request) {
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
        appDownloadUrl: process.env.APP_DOWNLOAD_URL
      }),
    // The reply to an unexpected failure says nothing about it, so the log has to.
    onError: ({ error, path }) => {
      if (error.code === "INTERNAL_SERVER_ERROR") {
        console.error(`tRPC ${path ?? "request"} failed:`, error.cause ?? error);
      }
    }
  });
}

export { handler as GET, handler as POST };
