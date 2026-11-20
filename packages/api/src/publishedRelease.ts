import { annotate, recordError, traced } from "@hcm/telemetry";
import { z } from "zod";
import { appReleases } from "./appReleases";

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

/** What one reading of the manifest gave: the release to answer with, and whether the manifest said it just now. */
type ReadOutcome = { release: PublishedRelease | null; wasRead: boolean };

/** A [PublishedReleaseReader] that can also be told to read before anyone asks. */
export type PublishedReleaseSource = PublishedReleaseReader & {
  /**
   * Reads the manifest now, as if someone had asked, unless an answer is still fresh or a reading
   * is under way, which everyone who asks meanwhile shares. For a new server instance, so that it
   * has the answer by the time the app asks. Ends when that reading has ended, and never fails.
   */
  readAhead: () => Promise<void>;
};

type ReaderOptions = {
  manifestUrl: string;
  fetch?: typeof globalThis.fetch;
  /** How long an answer is used before the manifest is read again. */
  freshForMs?: number;
  /**
   * How long an answer that names [newestVersionCode] or a later release is used instead. This
   * server offers no release newer than its newest one whatever the manifest says next, so reading
   * it again would tell only whether the download server is still there, or move the download to
   * an APK published before its deploy (which brings new instances that read it at once).
   */
  settledForMs?: number;
  /** The newest release this server knows: the latest in releases.json. */
  newestVersionCode?: number;
  /** How long a release read before is still used while the manifest cannot be read. */
  staleForMs?: number;
  timeoutMs?: number;
  now?: () => number;
};

/**
 * Reads the manifest at most once per [freshForMs] for everyone who asks meanwhile, or once per
 * [settledForMs] while it names the newest release this server knows. A manifest that cannot be
 * read (the download server is off, a timeout, a file that breaks a rule) answers with the release
 * read last, for up to [staleForMs], and then with null: the server does not know what is
 * published, and offers nothing new.
 */
export function createPublishedReleaseReader({
  manifestUrl,
  fetch = globalThis.fetch,
  freshForMs = 60_000,
  settledForMs = 30 * 60_000,
  newestVersionCode = appReleases[0]?.versionCode ?? Number.POSITIVE_INFINITY,
  staleForMs = 60 * 60_000,
  timeoutMs = 2_000,
  now = Date.now
}: ReaderOptions): PublishedReleaseSource {
  let lastGood: { release: PublishedRelease; at: number } | null = null;
  let checkedAt = Number.NEGATIVE_INFINITY;
  // How long the answer is used: longer only for one the manifest said just now, never for one
  // that stood in for a manifest that could not be read, so that its next reading is not put off.
  let answerFreshForMs = freshForMs;
  let answer: PublishedRelease | null = null;
  // The reading under way, when it started, and whether it is a read ahead, which nobody asked for yet.
  let reading: { outcome: Promise<ReadOutcome>; ahead: boolean; startedAt: number } | null = null;

  async function read(): Promise<ReadOutcome> {
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

        return { release, wasRead: true };
      } catch (error) {
        const stale = lastGood && now() - lastGood.at <= staleForMs ? lastGood.release : null;

        annotate({
          "release.manifest_result": stale ? "failed_used_stale" : "failed_none",
          "release.published_version_code": stale?.versionCode
        });
        recordError(error, { handled: true, reason: error instanceof Error && error.name === "TimeoutError" ? "manifest_timeout" : reason });

        return { release: stale, wasRead: false };
      }
    });
  }

  function isFresh(): boolean {
    return now() - checkedAt < answerFreshForMs;
  }

  function keep(done: ReadOutcome) {
    answer = done.release;
    answerFreshForMs = done.wasRead && done.release && done.release.versionCode >= newestVersionCode ? settledForMs : freshForMs;
    checkedAt = now();
  }

  // Others who ask while the manifest is read share that one reading.
  function startReading(ahead: boolean): Promise<ReadOutcome> {
    if (!reading) {
      const outcome = read().then((done) => {
        reading = null;

        // A read ahead that failed with nobody waiting is not kept: the next to ask reads again. It
        // may have failed only because it was frozen with a new instance that answered something
        // else first, and answering "nothing is known" for a minute would take the update card
        // off the Home of every app that asks meanwhile. One that a request waited for is kept by
        // that request (see `ask`).
        if (done.wasRead || !ahead) {
          keep(done);
        }

        return done;
      });

      reading = { outcome, ahead, startedAt: now() };
    }

    return reading.outcome;
  }

  const ask: PublishedReleaseReader = async () => {
    if (isFresh()) {
      annotate({
        "release.manifest_source": "cache",
        // How long ago the release was read: the answer is always the one read last, also when it
        // stands in for a manifest that could not be read since.
        "release.manifest_age_ms": answer && lastGood ? Math.round(now() - lastGood.at) : undefined,
        "release.published_version_code": answer?.versionCode
      });

      return answer;
    }

    if (reading) {
      annotate({ "release.manifest_source": "joined_reading" });

      const { outcome, ahead, startedAt } = reading;
      // A reading that started longer ago than its timeout allows has been frozen with the
      // instance since (on Vercel, after the instance's first answer): whatever it ends with says
      // nothing about the download server now.
      const wasFrozen = now() - startedAt > timeoutMs;
      const joined = await traced("release.read_manifest_wait", () => outcome);

      if (ahead && !joined.wasRead) {
        if (wasFrozen) {
          // This request asks again, and reads for itself as it would have without a read ahead.
          return ask();
        }

        // It failed while this request waited, so with the instance running: kept as this
        // request's own reading would have been, unless something newer was kept meanwhile.
        if (checkedAt < startedAt) {
          keep(joined);
        }
      }

      // What the reading found, which its own span recorded where it started (another request, or
      // the read ahead of a new instance).
      annotate({ "release.published_version_code": joined.release?.versionCode });

      return joined.release;
    }

    annotate({ "release.manifest_source": "fetched" });

    return (await startReading(false)).release;
  };

  return Object.assign(ask, {
    readAhead: async () => {
      if (isFresh()) {
        return;
      }

      await startReading(true).catch(() => undefined);
    }
  });
}
