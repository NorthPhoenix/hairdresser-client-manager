import { describe, expect, it } from "vitest";
import { phonePattern } from "./testSupport";

describe("phonePattern", () => {
  it("finds a phone however it was written, also cut short to seven digits in a row", () => {
    const pattern = phonePattern("+1 312 555 0100");
    const written = [
      "+1 312 555 0100",
      "+13125550100",
      "3125550100",
      "(312) 555-0100",
      "312.555.0100",
      "312/555-0100",
      "312 – 555 – 0100",
      "+1\u2011312\u2011555\u20110100",
      "312\u2012555\u20100100",
      "555 0100",
      "555\u00a00100",
      "55-50-100",
      "+1 312 555 ****",
      "(312) 555-01…"
    ];

    for (const phone of written) {
      expect(phone).toMatch(pattern);
    }

    expect("+7 999 123-45-67").toMatch(phonePattern("+7 999 123 45 67"));
    expect("+7 999 123 •• ••").toMatch(phonePattern("+7 999 123 45 67"));
  });

  it("does not take random ids, timings or another number for it", () => {
    const pattern = phonePattern("+1 312 555 0100");
    // The trace id is one that once failed a check for "555".
    const line = '{"timestamp":"2026-11-22T12:17:27.555Z","duration_ms":14.555,"trace_id":"48e76555d0d6e8fa1cbb2f0911336dba","span_id":"5550a1b2c3d4e5f6"}';

    expect(line).not.toMatch(pattern);
    expect("+1 415 555 0199").not.toMatch(pattern);
    expect("555, 0100").not.toMatch(pattern);
    // Six digits are not looked for: random ids would hold them too often.
    expect("(312) 555-…").not.toMatch(pattern);
  });

  it("refuses a number too short to look for without matching ids by chance", () => {
    expect(() => phonePattern("")).toThrow();
    expect(() => phonePattern("555 01")).toThrow();
  });
});
