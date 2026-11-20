// The published release (latest.json beside APP_DOWNLOAD_URL, docs/adr/0018), which `app.releases`
// offers updates up to. Imported third at the top of the tRPC route, which passes
// `publishedRelease` on to its procedures.
//
// Reading latest.json takes about 0.4 s, since the download server is on the maintainer's machine
// behind Tailscale Funnel. Most of the app's `app.releases` calls used to wait for it: they came to
// a server instance that had never read it, because the app's first requests are spread over
// several instances. So the reading starts here, as the route's module loads, and a new instance
// has the answer, or has it under way, by the time the app asks. On Vercel an instance is frozen
// once it has answered and has nothing left to do, which would also stop a reading half-way
// through; the first request keeps the instance running until the reading has ended
// (`releaseReadAheadUnderWay`). The time it takes is the `release.read_manifest` task in the
// server log (docs/observability.md).
import { createPublishedReleaseReader, manifestUrlFor } from "@hcm/api";

const manifestUrl = manifestUrlFor(process.env.APP_DOWNLOAD_URL);

export const publishedRelease = manifestUrl ? createPublishedReleaseReader({ manifestUrl }) : null;

// Not while `next build` loads the modules to collect page data: it serves no requests.
let readingAhead: Promise<void> | null =
  publishedRelease && process.env.NEXT_RUNTIME === "nodejs" && process.env.NEXT_PHASE !== "phase-production-build"
    ? publishedRelease.readAhead().finally(() => {
        readingAhead = null;
      })
    : null;

/**
 * The reading started as the module loaded, while it is under way, for the first request to ask:
 * that request hands it to `after`, so that the instance runs until the reading has ended.
 * Nothing for every other request, nor once the reading has ended.
 */
export function releaseReadAheadUnderWay(): Promise<void> | null {
  const underWay = readingAhead;

  readingAhead = null;

  return underWay;
}
