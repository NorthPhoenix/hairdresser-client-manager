import { z } from "zod";
import { appReleases, releasesSince } from "../appReleases";
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
   */
  releases: stylistProcedure.input(releasesInput).query(({ ctx, input }) => ({
    ...releasesSince(appReleases, input?.sinceVersionCode),
    downloadUrl: ctx.appDownloadUrl ?? null
  }))
});
