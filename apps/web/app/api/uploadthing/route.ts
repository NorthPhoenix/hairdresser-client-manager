// First: sets up telemetry before the first request (server/telemetry.ts says why).
import "../../../server/telemetry";
import { annotate, recordingStatus } from "@hcm/telemetry";
import { createRouteHandler } from "uploadthing/next";
import { uploadRouter } from "./core";

const handlers = createRouteHandler({
  router: uploadRouter
});

// Each event says the status answered, also on a new instance (recordingStatus says why).
export const GET = recordingStatus(handlers.GET);

// UploadThing makes several requests for one upload: the app asks to upload (`upload`), the
// storage service reports back when the file has arrived (the callback hook), and the app reports a
// failure (`failure`). The event says which of them this is; the slug names the file route.
export const POST: typeof handlers.POST = recordingStatus((request, ...rest) => {
  const url = new URL(request.url);

  annotate({
    "upload.action_type": url.searchParams.get("actionType") ?? "none",
    "upload.slug": url.searchParams.get("slug") ?? "none",
    "upload.is_callback": request.headers.get("uploadthing-hook") === "callback"
  });

  return handlers.POST(request, ...rest);
});
