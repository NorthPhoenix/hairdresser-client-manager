import { describe, expect, it } from "vitest";
import { decideSample } from "./sampler";

const config = { sampleRate: 0.1, slowMs: 1000, keepStylistIds: new Set(["stylist_kept"]) };

describe("the tail sampler", () => {
  it("keeps failures and refusals whatever the rate", () => {
    expect(decideSample({ outcome: "error", durationMs: 5 }, { ...config, sampleRate: 0 })).toEqual({ keep: true, reason: "error", rate: 1 });
    expect(decideSample({ outcome: "refused", durationMs: 5 }, { ...config, sampleRate: 0 })).toEqual({ keep: true, reason: "refused", rate: 1 });
  });

  it("keeps slow requests and chosen Stylists", () => {
    expect(decideSample({ outcome: "success", durationMs: 1000 }, config, () => 0.99).reason).toBe("slow");
    expect(decideSample({ outcome: "success", durationMs: 5, stylistId: "stylist_kept" }, config, () => 0.99).reason).toBe("stylist");
  });

  it("keeps the rest by chance, and records the rate", () => {
    expect(decideSample({ outcome: "success", durationMs: 5 }, config, () => 0.05)).toEqual({ keep: true, reason: "rate", rate: 0.1 });
    expect(decideSample({ outcome: "success", durationMs: 5 }, config, () => 0.5)).toEqual({ keep: false, reason: "dropped", rate: 0.1 });
  });

  it("keeps everything at the default rate of 1", () => {
    expect(decideSample({ outcome: "success", durationMs: 5 }, { ...config, sampleRate: 1 }, () => 0.999999).keep).toBe(true);
  });
});
