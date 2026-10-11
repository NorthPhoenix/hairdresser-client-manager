import { afterEach, describe, expect, it, vi } from "vitest";
import { deleteUnusedFiles, getUploadThingOrigins, isStoredFileUrl } from "./photoStorage";

// A token as UploadThing issues it, with a made-up key.
const tokenOf = (content: unknown) => btoa(JSON.stringify(content));

describe("the photo storage of this server", () => {
  it("is the UploadThing app the token names", () => {
    const token = tokenOf({
      apiKey: "sk_live_made_up",
      appId: "App1d2x9",
      regions: ["sea1"]
    });

    expect(getUploadThingOrigins(token)).toEqual(["https://app1d2x9.ufs.sh"]);
    // What the upload route answers with for a file of that app.
    expect(isStoredFileUrl("https://app1d2x9.ufs.sh/f/AbC123", "AbC123", getUploadThingOrigins(token))).toBe(true);
  });

  it.each([
    ["no token", undefined],
    ["an empty token", ""],
    ["a token that is not base64", "not a token!"],
    ["a token that is not JSON", btoa("not json")],
    ["a token without an app id", tokenOf({ apiKey: "sk_live_made_up" })],
    ["an app id that is not text", tokenOf({ appId: 42 })],
    ["an app id that would name another host", tokenOf({ appId: "evil.example.com/" })],
    ["an app id that would name another host with a path", tokenOf({ appId: "example.com#" })]
  ])("is nowhere with %s", (_label, token) => {
    expect(getUploadThingOrigins(token)).toEqual([]);
  });
});

describe("the URL of a stored file", () => {
  const origins = ["https://app-id.ufs.sh"];

  it("is in the photo storage and ends with the file's key", () => {
    expect(isStoredFileUrl("https://app-id.ufs.sh/f/AbC123", "AbC123", origins)).toBe(true);
    expect(isStoredFileUrl("http://127.0.0.1:3000/_ut/f/a%20b", "a b", ["http://127.0.0.1:3000"])).toBe(true);
  });

  it.each([
    ["another origin", "https://example.com/f/AbC123"],
    ["another file", "https://app-id.ufs.sh/f/other"],
    ["a longer key that ends the same", "https://app-id.ufs.sh/f/xAbC123"],
    ["the key only in the query", "https://app-id.ufs.sh/f/other?key=/AbC123"],
    ["the key only after a #", "https://app-id.ufs.sh/f/other#/AbC123"],
    ["no URL at all", "AbC123"]
  ])("is not %s", (_label, url) => {
    expect(isStoredFileUrl(url, "AbC123", origins)).toBe(false);
  });

  it("is never the URL of a file without a key", () => {
    expect(isStoredFileUrl("https://app-id.ufs.sh/f/", "", origins)).toBe(false);
  });
});

describe("deleting files from photo storage", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("hands the file keys to the storage", async () => {
    const deleteStoredFiles = vi.fn().mockResolvedValue(undefined);

    await deleteUnusedFiles(deleteStoredFiles, ["file_1", "file_2"]);

    expect(deleteStoredFiles).toHaveBeenCalledWith(["file_1", "file_2"]);
  });

  it("does not call the storage when there is nothing to delete", async () => {
    const deleteStoredFiles = vi.fn();

    await deleteUnusedFiles(deleteStoredFiles, []);

    expect(deleteStoredFiles).not.toHaveBeenCalled();
  });

  it("logs a storage failure with the file keys and does not fail", async () => {
    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const failure = new Error("UploadThing is down.");

    await expect(deleteUnusedFiles(vi.fn().mockRejectedValue(failure), ["file_1", "file_2"])).resolves.toBeUndefined();

    expect(logged).toHaveBeenCalledWith(expect.stringContaining("file_1, file_2"), failure);
  });

  it("stops waiting for a storage that does not answer, and logs that", async () => {
    vi.useFakeTimers();

    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const done = deleteUnusedFiles(() => new Promise(() => undefined), ["file_1"]);

    await vi.advanceTimersByTimeAsync(5_000);
    await expect(done).resolves.toBeUndefined();

    expect(logged).toHaveBeenCalledWith(expect.stringContaining("file_1"), expect.any(Error));
  });
});
