import { describe, expect, it } from "vitest";
import { phonePattern } from "./testSupport";

describe("phonePattern", () => {
  it("finds a phone however it was written", () => {
    const pattern = phonePattern("+1 312 555 0100");

    for (const written of ["+1 312 555 0100", "+13125550100", "3125550100", "(312) 555-0100", "312.555.0100", "555 0100", "55-50-100", "555 0100"]) {
      expect(written).toMatch(pattern);
    }

    expect("+7 999 123-45-67").toMatch(phonePattern("+7 999 123 45 67"));
  });

  it("does not take random ids, timings or another number for it", () => {
    const pattern = phonePattern("+1 312 555 0100");
    // The trace id is one that once failed a check for "555".
    const line = '{"timestamp":"2026-11-22T12:17:27.555Z","duration_ms":14.555,"trace_id":"48e76555d0d6e8fa1cbb2f0911336dba","span_id":"5550a1b2c3d4e5f6"}';

    expect(line).not.toMatch(pattern);
    expect("+1 312 555 0199").not.toMatch(pattern);
    expect("555, 0100").not.toMatch(pattern);
  });
});
