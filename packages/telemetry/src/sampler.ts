// The tail sampler: whether an event is printed, decided once the request is over and everything
// about it is known. Failures, refusals, slow requests and chosen Stylists are always kept; the
// rest by chance, at `sampleRate` (1 by default, so every event). The decision is written into the
// event, so a count over sampled events can be weighed back: an event kept at rate 0.1 stands for 10.
import type { TelemetryConfig } from "./config";

export type SampleReason = "error" | "refused" | "slow" | "stylist" | "rate" | "dropped";

export type SampleDecision = {
  keep: boolean;
  reason: SampleReason;
  /** The chance an event like this one had to be kept: 1 for every reason but "rate". */
  rate: number;
};

export type SampleInput = {
  outcome: "success" | "refused" | "error";
  durationMs: number;
  /** Whether any error was recorded, also one the code got over. */
  hasErrors?: boolean;
  stylistId?: string;
};

export function decideSample(input: SampleInput, config: Pick<TelemetryConfig, "sampleRate" | "slowMs" | "keepStylistIds">, random: () => number = Math.random): SampleDecision {
  if (input.outcome === "error") {
    return { keep: true, reason: "error", rate: 1 };
  }

  if (input.outcome === "refused") {
    return { keep: true, reason: "refused", rate: 1 };
  }

  // A successful request that got over a failure (`recordError` with `handled`).
  if (input.hasErrors) {
    return { keep: true, reason: "error", rate: 1 };
  }

  if (input.durationMs >= config.slowMs) {
    return { keep: true, reason: "slow", rate: 1 };
  }

  if (input.stylistId !== undefined && config.keepStylistIds.has(input.stylistId)) {
    return { keep: true, reason: "stylist", rate: 1 };
  }

  const keep = config.sampleRate >= 1 || random() < config.sampleRate;

  return { keep, reason: keep ? "rate" : "dropped", rate: config.sampleRate };
}
