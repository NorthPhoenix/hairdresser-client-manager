import { z } from "zod";
import type { Db } from "./context";
import releasesFile from "./releases.json" with { type: "json" };

/**
 * Releases of the Android app and what each one changed, in the Stylist's two languages. They are
 * kept in releases.json, next to this file, and a release adds its entry there (docs/releasing.md).
 * The entry with the highest version code is the latest release.
 */
const releaseNotes = z.array(z.string().trim().min(1).max(300)).min(1).max(20);

const releaseEntry = z
  .object({
    // Android's versionCode and versionName of the release, as in apps/android/app/build.gradle.kts.
    versionCode: z.number().int().min(1).max(2_100_000_000),
    versionName: z.string().trim().min(1).max(50),
    // The day it was published, "2026-10-21".
    date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
    notes: z.object({ ru: releaseNotes, en: releaseNotes })
  })
  .strict();

export type AppRelease = z.infer<typeof releaseEntry>;

/**
 * The entries of [file], newest first. A file that breaks a rule fails here, when the server
 * starts and in the tests, and never answers a request with half of it.
 */
export function readReleases(file: unknown): AppRelease[] {
  const releases = z.array(releaseEntry).min(1).parse(file);
  const codes = new Set(releases.map((release) => release.versionCode));
  const names = new Set(releases.map((release) => release.versionName));

  if (codes.size !== releases.length || names.size !== releases.length) {
    throw new Error("Every app release needs a version code and a version name of its own.");
  }

  return [...releases].sort((a, b) => b.versionCode - a.versionCode);
}

export const appReleases: AppRelease[] = readReleases(releasesFile);

/** The latest release, and the releases newer than [sinceVersionCode] (all of them without one), newest first. */
export function releasesSince(releases: AppRelease[], sinceVersionCode?: number) {
  const [latest] = releases;

  if (!latest) {
    throw new Error("There is no app release.");
  }

  return {
    latest: {
      versionCode: latest.versionCode,
      versionName: latest.versionName,
      date: latest.date
    },
    releases: releases
      .filter((release) => sinceVersionCode === undefined || release.versionCode > sinceVersionCode)
      .map((release) => ({
        versionCode: release.versionCode,
        versionName: release.versionName,
        date: release.date,
        notes: { ru: release.notes.ru, en: release.notes.en }
      }))
  };
}

/** The app that sent a request, as it names itself. */
export type AppVersion = {
  code: number;
  name: string | null;
};

/**
 * The version the Android app names in the headers of a request (`X-App-Version-Code`,
 * `X-App-Version-Name`), or null when it names none or one that is not valid. Apps before 1.4.0
 * send no such header.
 */
export function appVersionFromHeaders(headers: Pick<Headers, "get">): AppVersion | null {
  const codeText = headers.get("x-app-version-code")?.trim() ?? "";

  if (!/^\d{1,10}$/.test(codeText)) {
    return null;
  }

  const code = Number(codeText);

  if (code < 1 || code > 2_100_000_000) {
    return null;
  }

  const nameText = headers.get("x-app-version-name")?.trim() ?? "";
  // A name is what Android shows ("1.4.0"): short, printable and on one line.
  const name = nameText.length > 0 && nameText.length <= 50 && /^[\x21-\x7e]+$/.test(nameText) ? nameText : null;

  return { code, name };
}

/** How long a seen app version stays fresh: within it the same version is not written again. */
export const appVersionSeenFor = 60 * 60 * 1000;

type SeenAppVersion = {
  appVersionCode?: number | null;
  appVersionName?: string | null;
  appVersionSeenAt?: Date | null;
};

/** True when the Stylist's record should take [version]: it names another one, or it saw this one more than an hour ago. */
export function shouldRecordAppVersion(stylist: SeenAppVersion, version: AppVersion, now: Date): boolean {
  return (
    stylist.appVersionCode !== version.code ||
    (stylist.appVersionName ?? null) !== version.name ||
    !stylist.appVersionSeenAt ||
    now.getTime() - stylist.appVersionSeenAt.getTime() >= appVersionSeenFor
  );
}

/**
 * Remembers which app the Stylist used last, so the maintainer can tell which version is in use.
 * A request without a version, or of a version that was seen within the hour, writes nothing. The
 * write leaves `updatedAt` alone: it is the version of the Stylist's settings, which this does not
 * change (docs/adr/0015). It is one statement and needs no lock. A failure is logged and does not
 * fail the request: the request is not about this.
 */
export async function recordAppVersion(db: Db, stylist: { id: string } & SeenAppVersion, version: AppVersion | null, now = new Date()) {
  if (!version || !shouldRecordAppVersion(stylist, version, now)) {
    return;
  }

  try {
    await db.$executeRaw`UPDATE "Stylist" SET "appVersionCode" = ${version.code}, "appVersionName" = ${version.name}, "appVersionSeenAt" = ${now} WHERE "id" = ${stylist.id}`;
  } catch (error) {
    console.error("Could not record the app version of a Stylist:", error);
  }
}
