import { z } from "zod";
import { appReleases, releasesForApp } from "../appReleases";
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
   *
   * An app before 1.13.0 that is behind is not told of its own release or older ones, so that its
   * Home offers the update instead of keeping its own "Что нового" (`releasesForApp`).
   */
  releases: stylistProcedure.input(releasesInput).query(async ({ ctx, input }) => {
    const published = ctx.publishedRelease ? await ctx.publishedRelease() : undefined;
    const upTo = published === undefined ? undefined : (published?.versionCode ?? ctx.appVersion?.code);

    return {
      ...releasesForApp(appReleases, ctx.appVersion ?? null, input?.sinceVersionCode, upTo),
      downloadUrl: published?.downloadUrl ?? ctx.appDownloadUrl ?? null
    };
  })
});
