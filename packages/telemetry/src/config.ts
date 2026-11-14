// What can be configured, from the environment. Every value has a default that suits this app as
// it is today (about 250 requests a day): keep every event.

export type TelemetryConfig = {
  /** HCM_TELEMETRY=off turns off events and export. Default: on. */
  enabled: boolean;
  /** HCM_TELEMETRY_EXPORT=off keeps events on stdout but exports no spans. Default: on where an exporter is set up. */
  exportEnabled: boolean;
  /** HCM_TELEMETRY_SAMPLE_RATE: the share of ordinary requests whose event is printed, 0 to 1. Default 1. */
  sampleRate: number;
  /** HCM_TELEMETRY_SLOW_MS: a request at least this long is always kept. Default 1000. */
  slowMs: number;
  /** HCM_TELEMETRY_KEEP_STYLISTS: comma-separated Stylist ids whose requests are always kept. */
  keepStylistIds: ReadonlySet<string>;
};

function numberFrom(text: string | undefined, fallback: number, min: number, max: number) {
  const value = text === undefined || text.trim() === "" ? Number.NaN : Number(text);

  return Number.isFinite(value) && value >= min && value <= max ? value : fallback;
}

export function readConfig(env: Record<string, string | undefined> = process.env): TelemetryConfig {
  const off = (value: string | undefined) => ["off", "0", "false", "no"].includes((value ?? "").trim().toLowerCase());

  return {
    enabled: !off(env.HCM_TELEMETRY),
    exportEnabled: !off(env.HCM_TELEMETRY_EXPORT),
    sampleRate: numberFrom(env.HCM_TELEMETRY_SAMPLE_RATE, 1, 0, 1),
    slowMs: numberFrom(env.HCM_TELEMETRY_SLOW_MS, 1000, 0, 600_000),
    keepStylistIds: new Set(
      (env.HCM_TELEMETRY_KEEP_STYLISTS ?? "")
        .split(",")
        .map((id) => id.trim())
        .filter((id) => id.length > 0)
    )
  };
}

/** Where this process runs, from the variables Vercel sets. Locally most are absent. */
export function readDeployment(env: Record<string, string | undefined> = process.env) {
  return {
    "deployment.env": env.VERCEL_ENV ?? (env.NODE_ENV === "production" ? "production" : "local"),
    "deployment.id": env.VERCEL_DEPLOYMENT_ID,
    "deployment.region": env.VERCEL_REGION,
    "deployment.git_sha": env.VERCEL_GIT_COMMIT_SHA,
    "deployment.git_ref": env.VERCEL_GIT_COMMIT_REF
  };
}
