import { traceRequest } from "@hcm/telemetry";
import { setUpTelemetry, tearDownTelemetry } from "@hcm/telemetry/testing";
import { describe, expect, it, vi } from "vitest";
import { createPublishedReleaseReader, manifestUrlFor, parseManifest } from "./publishedRelease";

// Without a running request an error that was recorded is printed on a line of its own, which is
// where these tests read it.
function recordedErrors(printed: { mock: { calls: unknown[][] } }) {
  return printed.mock.calls
    .map(([line]) => (typeof line === "string" && line.startsWith('{"event":"hcm.error"') ? (JSON.parse(line) as Record<string, unknown>) : null))
    .filter((event) => event !== null);
}

const manifestUrl = "https://downloads.example.com/hcm/latest.json";
const manifest = { versionCode: 9, versionName: "1.7.0", file: "client-manager-1.7.0.apk", sha256: "ab12" };

function answer(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

describe("the manifest's address", () => {
  it("is latest.json in the folder of the download address", () => {
    expect(manifestUrlFor("https://downloads.example.com/hcm/client-manager.apk")).toBe(manifestUrl);
    expect(manifestUrlFor(" https://downloads.example.com/hcm/ ")).toBe(manifestUrl);
  });

  it("is none without a download address, or for one that is not http(s)", () => {
    expect(manifestUrlFor(undefined)).toBeNull();
    expect(manifestUrlFor("")).toBeNull();
    expect(manifestUrlFor("not a url")).toBeNull();
    expect(manifestUrlFor("ftp://downloads.example.com/hcm/client-manager.apk")).toBeNull();
  });
});

describe("a manifest", () => {
  it("names the release and its own file beside the manifest, and ignores what it does not know", () => {
    expect(parseManifest(manifest, manifestUrl)).toEqual({
      versionCode: 9,
      versionName: "1.7.0",
      downloadUrl: "https://downloads.example.com/hcm/client-manager-1.7.0.apk"
    });
  });

  it("is refused when it breaks a rule", () => {
    expect(parseManifest({ ...manifest, versionCode: "9" }, manifestUrl)).toBeNull();
    expect(parseManifest({ ...manifest, versionCode: 0 }, manifestUrl)).toBeNull();
    expect(parseManifest({ ...manifest, versionName: " " }, manifestUrl)).toBeNull();
    // Only a file in the same folder: no other folder, server or kind of file.
    expect(parseManifest({ ...manifest, file: "../client-manager.apk" }, manifestUrl)).toBeNull();
    expect(parseManifest({ ...manifest, file: "https://elsewhere.example.com/app.apk" }, manifestUrl)).toBeNull();
    expect(parseManifest({ ...manifest, file: "client-manager-1.7.0.zip" }, manifestUrl)).toBeNull();
    expect(parseManifest(null, manifestUrl)).toBeNull();
  });
});

describe("reading the published release", () => {
  // This server's newest release is 1.8.0 (10), one newer than the manifest's 1.7.0 (9).
  function reader(fetch: ReturnType<typeof vi.fn>, newestVersionCode = 10) {
    let clock = 1_000_000;
    const read = createPublishedReleaseReader({
      manifestUrl,
      fetch: fetch as unknown as typeof globalThis.fetch,
      freshForMs: 60_000,
      settledForMs: 300_000,
      newestVersionCode,
      staleForMs: 600_000,
      now: () => clock
    });

    return { read, later: (ms: number) => (clock += ms) };
  }

  it("reads the manifest once a minute for everyone who asks meanwhile", async () => {
    const fetch = vi.fn().mockImplementation(async () => answer(manifest));
    const { read, later } = reader(fetch);

    const [first, second] = await Promise.all([read(), read()]);
    expect(first?.versionCode).toBe(9);
    expect(second).toEqual(first);
    later(59_000);
    await read();
    expect(fetch).toHaveBeenCalledTimes(1);

    later(2_000);
    await read();
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch).toHaveBeenCalledWith(manifestUrl, expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it("follows a newer manifest once it is read again", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(answer(manifest))
      .mockResolvedValueOnce(answer({ versionCode: 10, versionName: "1.8.0", file: "client-manager-1.8.0.apk" }));
    const { read, later } = reader(fetch);

    expect((await read())?.versionName).toBe("1.7.0");
    later(61_000);
    expect((await read())?.versionName).toBe("1.8.0");
  });

  it("keeps the release read last while the manifest cannot be read, for a while", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fetch = vi.fn().mockResolvedValueOnce(answer(manifest)).mockRejectedValue(new Error("offline"));
    const { read, later } = reader(fetch);

    await read();
    later(61_000);
    expect((await read())?.versionCode).toBe(9);
    // Past the stale time the server no longer claims to know what is published.
    later(600_000);
    expect(await read()).toBeNull();
    expect(recordedErrors(printed)).toMatchObject([{ "error.reason": "manifest_unreadable", "error.handled": true }, { "error.reason": "manifest_unreadable" }]);
    printed.mockRestore();
  });

  it("knows nothing when the manifest is missing or breaks a rule, and says which", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);

    expect(await reader(vi.fn().mockResolvedValue(answer({ error: "not found" }, 404))).read()).toBeNull();
    expect(await reader(vi.fn().mockResolvedValue(answer({ versionCode: 9 }))).read()).toBeNull();
    expect(await reader(vi.fn().mockResolvedValue(new Response("<html>", { status: 200 }))).read()).toBeNull();
    expect(recordedErrors(printed).map((event) => event["error.reason"])).toEqual(["manifest_http_error", "manifest_invalid", "manifest_unreadable"]);
    printed.mockRestore();
  });

  it("waits a minute at the most for a release this server knows but is not published yet", async () => {
    const fetch = vi.fn().mockImplementation(async () => answer(manifest));
    const { read, later } = reader(fetch);

    await read();
    later(61_000);
    await read();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("reads less often once the manifest names the newest release this server knows, or a later one", async () => {
    for (const newest of [9, 8]) {
      const fetch = vi.fn().mockImplementation(async () => answer(manifest));
      const { read, later } = reader(fetch, newest);

      await read();
      later(299_000);
      expect((await read())?.versionCode).toBe(9);
      expect(fetch).toHaveBeenCalledTimes(1);

      later(2_000);
      await read();
      expect(fetch).toHaveBeenCalledTimes(2);
    }
  });

  it("does not put off reading again when the newest release only stood in for a manifest that could not be read", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(answer(manifest))
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue(answer(manifest));
    const { read, later } = reader(fetch, 9);

    await read();
    later(301_000);
    // Could not be read: 1.7.0 stands in, and is asked for again after a minute, not five.
    expect((await read())?.versionCode).toBe(9);
    later(61_000);
    expect((await read())?.versionCode).toBe(9);
    expect(fetch).toHaveBeenCalledTimes(3);
    printed.mockRestore();
  });

  it("reads ahead, before anyone asks, and answers the first to ask with what it read", async () => {
    const fetch = vi.fn().mockImplementation(async () => answer(manifest));
    const { read } = reader(fetch);

    await read.readAhead();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect((await read())?.versionCode).toBe(9);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("lets whoever asks while it reads ahead share that reading", async () => {
    let respond: (response: Response) => void = () => undefined;
    const fetch = vi.fn().mockImplementation(() => new Promise<Response>((resolve) => (respond = resolve)));
    const { read } = reader(fetch);

    const ahead = read.readAhead();
    const asked = read();

    respond(answer(manifest));
    await ahead;
    expect((await asked)?.versionCode).toBe(9);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("reads ahead only when the answer it has is no longer fresh", async () => {
    const fetch = vi.fn().mockImplementation(async () => answer(manifest));
    const { read, later } = reader(fetch);

    await read();
    await read.readAhead();
    expect(fetch).toHaveBeenCalledTimes(1);

    later(61_000);
    await read.readAhead();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("never fails to read ahead, and does not keep a read ahead that failed: the next to ask reads again", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fetch = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(answer(manifest));
    const { read } = reader(fetch);

    await expect(read.readAhead()).resolves.toBeUndefined();
    expect((await read())?.versionCode).toBe(9);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(recordedErrors(printed)).toMatchObject([{ "error.reason": "manifest_unreadable", "error.handled": true }]);
    printed.mockRestore();
  });

  it("reads for itself when the read ahead it waited for was frozen with the instance", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    let fail: (error: Error) => void = () => undefined;
    const fetch = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Response>((_, reject) => (fail = reject)))
      .mockResolvedValue(answer(manifest));
    const { read, later } = reader(fetch);

    const ahead = read.readAhead();
    // The instance was frozen after its first answer, and wakes for these two requests 90 s later.
    later(90_000);
    const asked = [read(), read()];

    fail(Object.assign(new Error("The operation timed out."), { name: "TimeoutError" }));
    await ahead;
    expect((await Promise.all(asked)).map((release) => release?.versionCode)).toEqual([9, 9]);
    // One new reading for both.
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(recordedErrors(printed)).toMatchObject([{ "error.reason": "manifest_timeout" }]);
    printed.mockRestore();
  });

  it("tells on each request whether it read again after a frozen read ahead or shared that reading, and how long it waited", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const telemetry = setUpTelemetry();

    try {
      let fail: (error: Error) => void = () => undefined;
      const fetch = vi
        .fn()
        .mockImplementationOnce(() => new Promise<Response>((_, reject) => (fail = reject)))
        .mockResolvedValue(answer(manifest));
      const { read, later } = reader(fetch);
      const request = () =>
        traceRequest({ method: "GET", route: "/api/trpc/[trpc]" }, async () => {
          await read();
          return { status: 200 };
        });

      const ahead = read.readAhead();
      later(90_000);
      const asked = [request(), request()];

      fail(Object.assign(new Error("The operation timed out."), { name: "TimeoutError" }));
      await Promise.all([ahead, ...asked]);
      // The first waited for the read ahead (a `release.read_manifest_wait`) and read again (a
      // `release.read_manifest`); the second waited for the read ahead and then for that reading.
      expect(telemetry.events().filter((event) => event.event === "hcm.request")).toMatchObject([
        { "release.manifest_source": "fetched", "release.manifest_result": "read", "release.published_version_code": 9, span_count: 2 },
        { "release.manifest_source": "joined_reading", "release.published_version_code": 9, span_count: 2 }
      ]);
    } finally {
      tearDownTelemetry();
      printed.mockRestore();
    }
  });

  it("uses a frozen read ahead that reads the manifest after all, without reading again", async () => {
    let respond: (response: Response) => void = () => undefined;
    const fetch = vi.fn().mockImplementation(() => new Promise<Response>((resolve) => (respond = resolve)));
    const { read, later } = reader(fetch);

    const ahead = read.readAhead();
    later(90_000);
    const asked = read();

    respond(answer(manifest));
    await ahead;
    expect((await asked)?.versionCode).toBe(9);
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("takes and keeps what a read ahead that failed while it waited found, as it would a reading of its own", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    let fail: (error: Error) => void = () => undefined;
    const fetch = vi
      .fn()
      .mockImplementationOnce(() => new Promise<Response>((_, reject) => (fail = reject)))
      .mockResolvedValue(answer(manifest));
    const { read, later } = reader(fetch);

    const ahead = read.readAhead();
    // The download server does not answer: the request waits with the read ahead until its timeout,
    // exactly as long as a reading of its own may take, and is not kept waiting for a second one.
    later(2_000);
    const asked = read();

    fail(Object.assign(new Error("The operation timed out."), { name: "TimeoutError" }));
    await ahead;
    expect(await asked).toBeNull();
    // Kept for a minute, as before: the requests after it do not wait for another timeout.
    later(59_000);
    expect(await read()).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(1);
    later(2_000);
    expect((await read())?.versionCode).toBe(9);
    expect(fetch).toHaveBeenCalledTimes(2);
    printed.mockRestore();
  });

  it("reads again after a minute when the read ahead names a release older than this server's newest", async () => {
    const fetch = vi.fn().mockImplementation(async () => answer(manifest));
    const { read, later } = reader(fetch);

    await read.readAhead();
    later(59_000);
    await read();
    expect(fetch).toHaveBeenCalledTimes(1);
    later(2_000);
    await read();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("keeps a read ahead that names the newest release for the half hour, and says so on its own event", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const telemetry = setUpTelemetry();

    try {
      const fetch = vi.fn().mockImplementation(async () => answer(manifest));
      const { read, later } = reader(fetch, 9);

      await read.readAhead();
      later(299_000);
      expect((await read())?.versionCode).toBe(9);
      expect(fetch).toHaveBeenCalledTimes(1);
      expect(telemetry.events().filter((event) => event.event === "hcm.task")).toMatchObject([
        { name: "release.read_manifest", "release.manifest_result": "read", "release.published_version_code": 9 }
      ]);
    } finally {
      tearDownTelemetry();
      printed.mockRestore();
    }
  });

  it("still knows nothing when the manifest cannot be read when asked either", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const fetch = vi.fn().mockRejectedValue(new Error("offline"));
    const { read, later } = reader(fetch);

    await read.readAhead();
    expect(await read()).toBeNull();
    // That answer, asked for, is kept for a minute as before.
    later(59_000);
    expect(await read()).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(2);
    printed.mockRestore();
  });

  it("stops offering the release an hour after it was last read when the download server goes off, also after a half-hour answer", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    let clock = 1_000_000;
    const fetch = vi.fn().mockResolvedValueOnce(answer(manifest)).mockRejectedValue(new Error("offline"));
    const read = createPublishedReleaseReader({
      manifestUrl,
      fetch: fetch as unknown as typeof globalThis.fetch,
      newestVersionCode: 9,
      now: () => clock
    });

    await read();
    clock += 30 * 60_000 + 1_000;
    // Read again after half an hour, which fails: the release read then stands in.
    expect((await read())?.versionCode).toBe(9);
    clock += 29 * 60_000;
    expect((await read())?.versionCode).toBe(9);
    clock += 60_000;
    // An hour and a second after it was read.
    expect(await read()).toBeNull();
    printed.mockRestore();
  });

  it("says how long ago the release in a cached answer was read, also while it stands in for a manifest that could not be read", async () => {
    const printed = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const telemetry = setUpTelemetry();

    try {
      const fetch = vi.fn().mockResolvedValueOnce(answer(manifest)).mockRejectedValue(new Error("offline"));
      const { read, later } = reader(fetch);

      await read();
      later(61_000);
      // Fails: the release read 61 s ago stands in, for another minute.
      await read();
      later(30_000);
      await traceRequest({ method: "GET", route: "/api/trpc/[trpc]" }, async () => {
        await read();
        return { status: 200 };
      });

      expect(telemetry.events().filter((event) => event.event === "hcm.request")).toMatchObject([
        { "release.manifest_source": "cache", "release.manifest_age_ms": 91_000, "release.published_version_code": 9 }
      ]);
    } finally {
      tearDownTelemetry();
      printed.mockRestore();
    }
  });
});
