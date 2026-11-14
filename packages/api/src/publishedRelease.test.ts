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
  function reader(fetch: ReturnType<typeof vi.fn>) {
    let clock = 1_000_000;
    const read = createPublishedReleaseReader({
      manifestUrl,
      fetch: fetch as unknown as typeof globalThis.fetch,
      freshForMs: 60_000,
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
});
