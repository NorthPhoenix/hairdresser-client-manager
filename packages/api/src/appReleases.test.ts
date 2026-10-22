import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import {
  appReleases,
  appVersionFromHeaders,
  appVersionSeenFor,
  readReleases,
  recordAppVersion,
  releasesSince,
  shouldRecordAppVersion
} from "./appReleases";
import type { TRPCContext } from "./context";
import { appRouter } from "./root";
import releasesFile from "./releases.json" with { type: "json" };

const updatedAt = new Date("2026-10-01T12:00:00.000Z");

function storedStylist(seen: { appVersionCode?: number | null; appVersionName?: string | null; appVersionSeenAt?: Date | null } = {}) {
  return {
    id: "stylist_1",
    clerkId: "clerk_1",
    language: "ru",
    timezone: "America/Chicago",
    salonAddress: null,
    onboardingCompletedAt: updatedAt,
    appVersionCode: null,
    appVersionName: null,
    appVersionSeenAt: null,
    createdAt: updatedAt,
    updatedAt,
    ...seen
  };
}

function caller(options: {
  stylist?: ReturnType<typeof storedStylist>;
  userId?: string | null;
  appVersion?: { code: number; name: string | null } | null;
  appDownloadUrl?: string | null;
  executeRaw?: ReturnType<typeof vi.fn>;
}) {
  const executeRaw = options.executeRaw ?? vi.fn().mockResolvedValue(1);
  const upsert = vi.fn().mockResolvedValue(options.stylist ?? storedStylist());
  const ctx = {
    clerkAuth: { userId: options.userId === undefined ? "clerk_1" : options.userId },
    db: { stylist: { upsert, findUnique: upsert }, $executeRaw: executeRaw },
    photoOrigins: [],
    appVersion: options.appVersion ?? null,
    appDownloadUrl: options.appDownloadUrl ?? null
  } as unknown as TRPCContext;

  return { caller: appRouter.createCaller(ctx), executeRaw, upsert };
}

function headers(values: Record<string, string>) {
  return new Headers(values);
}

describe("releases.json", () => {
  it("is valid, newest first, and has as many English lines as Russian ones", () => {
    const releases = readReleases(releasesFile);

    expect(releases.length).toBeGreaterThan(0);
    expect(releases.map((release) => release.versionCode)).toEqual(
      [...releases.map((release) => release.versionCode)].sort((a, b) => b - a)
    );

    for (const release of releases) {
      expect(release.notes.en.length, `notes of ${release.versionName}`).toBe(release.notes.ru.length);
      expect(Number.isNaN(Date.parse(`${release.date}T00:00:00Z`))).toBe(false);
    }
  });

  it("names the version the Android app is built as as its latest release", () => {
    // A release raises versionCode and versionName and adds its entry, together (docs/releasing.md).
    const gradle = readFileSync(new URL("../../../apps/android/app/build.gradle.kts", import.meta.url), "utf8");
    const versionCode = Number(/versionCode\s*=\s*(\d+)/.exec(gradle)?.[1]);
    const versionName = /versionName\s*=\s*"([^"]+)"/.exec(gradle)?.[1];

    expect(appReleases[0]).toMatchObject({ versionCode, versionName });
  });

  it("refuses two entries with the same version", () => {
    const [newest] = readReleases(releasesFile);

    expect(() => readReleases([newest, { ...newest, versionName: "9.9.9" }])).toThrow();
    expect(() => readReleases([newest, { ...newest, versionCode: 999 }])).toThrow();
    expect(() => readReleases([])).toThrow();
    expect(() => readReleases([{ ...newest, notes: { ru: [], en: [] } }])).toThrow();
  });

  it("gives the latest release and the ones newer than a version", () => {
    const releases = readReleases([
      { versionCode: 3, versionName: "1.1.0", date: "2026-10-01", notes: { ru: ["а"], en: ["a"] } },
      { versionCode: 7, versionName: "1.5.0", date: "2026-11-01", notes: { ru: ["в"], en: ["c"] } },
      { versionCode: 5, versionName: "1.3.0", date: "2026-10-15", notes: { ru: ["б"], en: ["b"] } }
    ]);

    expect(releasesSince(releases).latest).toEqual({ versionCode: 7, versionName: "1.5.0", date: "2026-11-01" });
    expect(releasesSince(releases).releases.map((release) => release.versionCode)).toEqual([7, 5, 3]);
    expect(releasesSince(releases, 3).releases.map((release) => release.versionCode)).toEqual([7, 5]);
    expect(releasesSince(releases, 5).releases.map((release) => release.versionCode)).toEqual([7]);
    expect(releasesSince(releases, 7).releases).toEqual([]);
    expect(releasesSince(releases, 0).releases[2]).toEqual({
      versionCode: 3,
      versionName: "1.1.0",
      date: "2026-10-01",
      notes: { ru: ["а"], en: ["a"] }
    });
  });
});

describe("app.releases", () => {
  it("is for a signed-in Stylist only", async () => {
    await expect(caller({ userId: null }).caller.app.releases()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
  });

  it("answers with the latest release, every release newest first, and the download address", async () => {
    const { caller: app } = caller({ appDownloadUrl: "https://example.com/hcm.apk" });
    const answer = await app.app.releases();

    expect(answer.latest).toEqual({
      versionCode: appReleases[0]?.versionCode,
      versionName: appReleases[0]?.versionName,
      date: appReleases[0]?.date
    });
    expect(answer.releases.map((release) => release.versionCode)).toEqual(appReleases.map((release) => release.versionCode));
    expect(answer.releases[0]?.notes.ru.length).toBeGreaterThan(0);
    expect(answer.downloadUrl).toBe("https://example.com/hcm.apk");
  });

  it("leaves out the releases up to sinceVersionCode", async () => {
    const latest = appReleases[0]?.versionCode ?? 0;
    const { caller: app } = caller({});

    expect((await app.app.releases({ sinceVersionCode: latest - 1 })).releases.map((release) => release.versionCode)).toEqual([latest]);
    expect((await app.app.releases({ sinceVersionCode: latest })).releases).toEqual([]);
    // The latest release is named also when the app has it already.
    expect((await app.app.releases({ sinceVersionCode: latest })).latest.versionCode).toBe(latest);
  });

  it("has no download address when the server has none", async () => {
    expect((await caller({}).caller.app.releases()).downloadUrl).toBeNull();
  });

  it("refuses a version code that is not a whole number", async () => {
    await expect(caller({}).caller.app.releases({ sinceVersionCode: 1.5 })).rejects.toMatchObject({ code: "BAD_REQUEST" });
    await expect(caller({}).caller.app.releases({ sinceVersionCode: -1 })).rejects.toMatchObject({ code: "BAD_REQUEST" });
  });
});

describe("the app version in a request's headers", () => {
  it("reads the code and the name", () => {
    expect(appVersionFromHeaders(headers({ "X-App-Version-Code": "6", "X-App-Version-Name": "1.4.0" }))).toEqual({ code: 6, name: "1.4.0" });
    expect(appVersionFromHeaders(headers({ "x-app-version-code": " 12 " }))).toEqual({ code: 12, name: null });
  });

  it("is none for an app that sends no code, or one that is not valid", () => {
    // Every app before 1.4.0.
    expect(appVersionFromHeaders(headers({}))).toBeNull();
    expect(appVersionFromHeaders(headers({ "X-App-Version-Name": "1.4.0" }))).toBeNull();
    expect(appVersionFromHeaders(headers({ "X-App-Version-Code": "abc" }))).toBeNull();
    expect(appVersionFromHeaders(headers({ "X-App-Version-Code": "0" }))).toBeNull();
    expect(appVersionFromHeaders(headers({ "X-App-Version-Code": "-3" }))).toBeNull();
    expect(appVersionFromHeaders(headers({ "X-App-Version-Code": "2100000001" }))).toBeNull();
    expect(appVersionFromHeaders(headers({ "X-App-Version-Code": "99999999999" }))).toBeNull();
  });

  it("drops a name that is too long or not printable, and keeps the code", () => {
    expect(appVersionFromHeaders(headers({ "X-App-Version-Code": "6", "X-App-Version-Name": "x".repeat(51) }))).toEqual({ code: 6, name: null });
    expect(appVersionFromHeaders(headers({ "X-App-Version-Code": "6", "X-App-Version-Name": "1.4 beta" }))).toEqual({ code: 6, name: null });
  });
});

describe("recording the app version a Stylist uses", () => {
  const now = new Date("2026-10-21T18:00:00.000Z");
  const version = { code: 6, name: "1.4.0" };

  it("writes a version that is new, changed, or last seen an hour ago or more", () => {
    expect(shouldRecordAppVersion({}, version, now)).toBe(true);
    expect(shouldRecordAppVersion({ appVersionCode: 5, appVersionName: "1.3.0", appVersionSeenAt: now }, version, now)).toBe(true);
    expect(shouldRecordAppVersion({ appVersionCode: 6, appVersionName: "1.4.0-debug", appVersionSeenAt: now }, version, now)).toBe(true);
    expect(
      shouldRecordAppVersion({ appVersionCode: 6, appVersionName: "1.4.0", appVersionSeenAt: new Date(now.getTime() - appVersionSeenFor) }, version, now)
    ).toBe(true);
  });

  it("does not write the same version again within the hour", () => {
    expect(
      shouldRecordAppVersion({ appVersionCode: 6, appVersionName: "1.4.0", appVersionSeenAt: new Date(now.getTime() - appVersionSeenFor + 1000) }, version, now)
    ).toBe(false);
  });

  it("writes the version of a request with one, and leaves updatedAt alone", async () => {
    const { caller: app, executeRaw } = caller({ appVersion: version });

    await app.stylist.bootstrap();

    expect(executeRaw).toHaveBeenCalledTimes(1);
    const [strings, ...values] = executeRaw.mock.calls[0] as [TemplateStringsArray, ...unknown[]];
    const statement = strings.join("?");

    expect(statement).toContain('UPDATE "Stylist"');
    expect(statement).not.toContain("updatedAt");
    expect(values.slice(0, 2)).toEqual([6, "1.4.0"]);
    expect(values[2]).toBeInstanceOf(Date);
    expect(values[3]).toBe("stylist_1");
  });

  it("writes nothing for a request without a version, as from an app before 1.4.0", async () => {
    const { caller: app, executeRaw } = caller({ appVersion: null });

    await expect(app.stylist.bootstrap()).resolves.toMatchObject({ id: "stylist_1", updatedAt: updatedAt.toISOString() });
    await app.app.releases();

    expect(executeRaw).not.toHaveBeenCalled();
  });

  it("writes nothing when the same version was seen within the hour", async () => {
    const { caller: app, executeRaw } = caller({
      appVersion: version,
      stylist: storedStylist({ appVersionCode: 6, appVersionName: "1.4.0", appVersionSeenAt: new Date(Date.now() - 60_000) })
    });

    await app.stylist.bootstrap();

    expect(executeRaw).not.toHaveBeenCalled();
  });

  it("does not fail the request when the write fails", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { caller: app } = caller({ appVersion: version, executeRaw: vi.fn().mockRejectedValue(new Error("database down")) });

    await expect(app.stylist.bootstrap()).resolves.toMatchObject({ id: "stylist_1" });
    expect(error).toHaveBeenCalled();
    error.mockRestore();
  });

  it("writes without a Stylist lock or a transaction", async () => {
    const executeRaw = vi.fn().mockResolvedValue(1);

    await recordAppVersion({ $executeRaw: executeRaw } as unknown as TRPCContext["db"], { id: "stylist_9" }, version, now);

    expect(executeRaw).toHaveBeenCalledTimes(1);
    expect((executeRaw.mock.calls[0] as unknown[]).slice(1)).toEqual([6, "1.4.0", now, "stylist_9"]);
  });
});
