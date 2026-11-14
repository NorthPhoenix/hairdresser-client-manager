import { annotate, recordError, traced } from "@hcm/telemetry";
import { z } from "zod";

/**
 * The Android release whose APK is on the download server, as `latest.json` next to the APK says.
 * The server offers an update only up to this release, and links to its own file, so an app is
 * never sent to download a version that is not there yet: a deploy that names a new release in
 * releases.json before its APK is published offers nothing new until it is (docs/adr/0018).
 */
export type PublishedRelease = {
  versionCode: number;
  versionName: string;
  /** The APK of exactly this release, which does not change once it is published. */
  downloadUrl: string;
};

/** What `apps/android/scripts/publish-apk.sh` writes. More fields may come; they are ignored. */
const manifest = z.object({
  versionCode: z.number().int().min(1).max(2_100_000_000),
  versionName: z.string().trim().min(1).max(50),
  // A file name in the same folder as the manifest: "client-manager-1.7.0.apk".
  file: z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.apk$/)
});

/** The manifest's address: `latest.json` in the folder of [downloadUrl] (APP_DOWNLOAD_URL). Null for an address that is not http(s). */
export function manifestUrlFor(downloadUrl: string | null | undefined): string | null {
  const text = downloadUrl?.trim();

  if (!text) {
    return null;
  }

  try {
    const url = new URL("latest.json", text);

    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
  } catch {
    return null;
  }
}

/** The release [json] names, with its file's address beside [manifestUrl]. Null for a manifest that breaks a rule. */
export function parseManifest(json: unknown, manifestUrl: string): PublishedRelease | null {
  const parsed = manifest.safeParse(json);

  if (!parsed.success) {
    return null;
  }

  return {
    versionCode: parsed.data.versionCode,
    versionName: parsed.data.versionName,
    downloadUrl: new URL(parsed.data.file, manifestUrl).toString()
  };
}

export type PublishedReleaseReader = () => Promise<PublishedRelease | null>;

type ReaderOptions = {
  manifestUrl: string;
  fetch?: typeof globalThis.fetch;
  /** How long an answer is used before the manifest is read again. */
  freshForMs?: number;
  /** How long a release read before is still used while the manifest cannot be read. */
  staleForMs?: number;
  timeoutMs?: number;
  now?: () => number;
};

/**
 * Reads the manifest at most once per [freshForMs] for everyone who asks meanwhile. A manifest
 * that cannot be read (the download server is off, a timeout, a file that breaks a rule) answers
 * with the release read last, for up to [staleForMs], and then with null: the server does not
 * know what is published, and offers nothing new.
 */
export function createPublishedReleaseReader({
  manifestUrl,
  fetch = globalThis.fetch,
  freshForMs = 60_000,
  staleForMs = 60 * 60_000,
  timeoutMs = 2_000,
  now = Date.now
}: ReaderOptions): PublishedReleaseReader {
  let lastGood: { release: PublishedRelease; at: number } | null = null;
  let checkedAt = Number.NEGATIVE_INFINITY;
  let answer: PublishedRelease | null = null;
  let reading: Promise<PublishedRelease | null> | null = null;

  async function read(): Promise<PublishedRelease | null> {
    // The one outgoing call of this reader, so the time it takes is a span of its own.
    return traced("release.read_manifest", async (span) => {
      // What went wrong, in the domain's words, if anything does.
      let reason = "manifest_unreadable";

      try {
        // Not cached by the framework: Next.js caches no fetch unless asked to.
        const response = await fetch(manifestUrl, { signal: AbortSignal.timeout(timeoutMs) });

        span.annotate({ "release.manifest_status": response.status });

        if (!response.ok) {
          reason = "manifest_http_error";
          throw new Error(`The release manifest answered ${response.status}.`);
        }

        reason = "manifest_unreadable";

        const release = parseManifest(await response.json(), manifestUrl);

        if (!release) {
          reason = "manifest_invalid";
          throw new Error("The release manifest breaks a rule.");
        }

        lastGood = { release, at: now() };
        annotate({ "release.manifest_result": "read", "release.published_version_code": release.versionCode });

        return release;
      } catch (error) {
        const stale = lastGood && now() - lastGood.at <= staleForMs ? lastGood.release : null;

        annotate({
          "release.manifest_result": stale ? "failed_used_stale" : "failed_none",
          "release.published_version_code": stale?.versionCode
        });
        recordError(error, { handled: true, reason: error instanceof Error && error.name === "TimeoutError" ? "manifest_timeout" : reason });

        return stale;
      }
    });
  }

  return async () => {
    if (now() - checkedAt < freshForMs) {
      annotate({ "release.manifest_source": "cache", "release.published_version_code": answer?.versionCode });

      return answer;
    }

    // Others who ask while the manifest is read share that one reading.
    annotate({ "release.manifest_source": reading ? "joined_reading" : "fetched" });

    reading ??= read().then((release) => {
      answer = release;
      checkedAt = now();
      reading = null;
      return release;
    });

    return reading;
  };
}
