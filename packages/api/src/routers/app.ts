import { z } from "zod";
import { appReleases, releasesSince } from "../appReleases";
import { annotate } from "@hcm/telemetry";
import { createTRPCRouter, stylistProcedure } from "../trpc";

const releasesInput = z
  .object({
    // Only the releases newer than this version code. Left out: every release.
    sinceVersionCode: z.number().int().min(0).max(2_100_000_000).optional()
  })
  .optional();

export const appReleaseRouter = createTRPCRouter({
  /**
   * The Android app's releases and what each one changed (releases.json), and where the app is
   * downloaded from. The app compares the latest release with its own version to offer an update,
   * and shows the notes of the releases it was updated past ("Что нового").
   *
   * Only the releases up to the published one count, and the download is that release's own file
   * (docs/adr/0018). While the server cannot tell what is published, nothing newer than the app
   * that asks is offered (every app that asks, from 1.4.0, says its version). A server without a
   * download server (development) counts every release.
   */
  releases: stylistProcedure.input(releasesInput).query(async ({ ctx, input }) => {
    const published = ctx.publishedRelease ? await ctx.publishedRelease() : undefined;
    const upTo = published === undefined ? undefined : (published?.versionCode ?? ctx.appVersion?.code);

    const result = releasesSince(appReleases, input?.sinceVersionCode, upTo);
    const downloadUrl = published?.downloadUrl ?? ctx.appDownloadUrl ?? null;

    // releasesSince records what it listed (release.*); this is what is decided here.
    annotate(() => ({
      // "unknown": the server could not tell what is published, so nothing newer than the app is offered.
      "release_list.published_source": !ctx.publishedRelease ? "not_configured" : published === undefined ? "unknown" : published === null ? "none" : "published",
      "release_list.up_to_version_code": upTo,
      "release_list.has_download_url": downloadUrl !== null
    }));

    return { ...result, downloadUrl };
  })
});
