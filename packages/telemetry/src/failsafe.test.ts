// Nothing in telemetry may fail a request: every public function swallows its own failures, and
// `traced` runs the work exactly once even when the tracing around it breaks.
import { context, trace } from "@opentelemetry/api";
import { afterEach, describe, expect, it, vi } from "vitest";
import { annotate, currentTraceId, hashPrefix, increment, recordError, requestFields, traced, traceRequest } from "./index";
import { WideEventProcessor } from "./processor";
import { setUpTelemetry, tearDownTelemetry } from "./testSupport";

afterEach(() => {
  vi.restoreAllMocks();
  tearDownTelemetry();
});

describe("without a registered SDK", () => {
  it("does nothing and returns what the work returns", async () => {
    tearDownTelemetry();

    expect(() => annotate({ a: 1 })).not.toThrow();
    expect(() => increment("a")).not.toThrow();
    expect(() => recordError(new Error("x"), { expected: true })).not.toThrow();
    expect(traced("unit", () => 7)).toBe(7);
    await expect(traced("unit", async () => 8)).resolves.toBe(8);
    await expect(traceRequest({ method: "GET", route: "/" }, async () => 9)).resolves.toBe(9);
    expect(currentTraceId()).toBeUndefined();
  });

  it("prints an unexpected error on its own line so it is not lost", () => {
    tearDownTelemetry();
    const log = vi.spyOn(console, "log").mockImplementation(() => undefined);

    recordError(new Error("database is down"));

    expect(log).toHaveBeenCalledTimes(1);
    expect(JSON.parse(String(log.mock.calls[0]?.[0]))).toMatchObject({ event: "hcm.error", outcome: "error", "error.message": "database is down" });
  });
});

describe("traced", () => {
  it("passes on what the work throws or rejects with, unchanged", async () => {
    setUpTelemetry();
    const failure = new Error("refused");

    expect(() =>
      traced("unit", () => {
        throw failure;
      })
    ).toThrow(failure);
    await expect(traced("unit", async () => Promise.reject(failure))).rejects.toBe(failure);
  });

  it("runs the work exactly once when starting a span fails", () => {
    setUpTelemetry();
    vi.spyOn(trace, "getTracer").mockImplementation(() => {
      throw new Error("tracer is broken");
    });
    const work = vi.fn(() => "done");

    expect(traced("unit", work)).toBe("done");
    expect(work).toHaveBeenCalledTimes(1);
  });

  it("runs the work exactly once when the context manager fails", () => {
    setUpTelemetry();
    vi.spyOn(context, "with").mockImplementation(() => {
      throw new Error("context is broken");
    });
    const work = vi.fn(() => "done");

    expect(traced("unit", work)).toBe("done");
    expect(work).toHaveBeenCalledTimes(1);
  });
});

describe("the processor", () => {
  it("does not fail the request when printing fails, and says so in the next event", async () => {
    let fail = true;
    const lines: string[] = [];

    setUpTelemetry({
      write: (line) => {
        if (fail) {
          throw new Error("stdout is closed");
        }

        lines.push(line);
      }
    });

    await expect(traceRequest({ method: "GET", route: "/" }, async () => new Response("ok"))).resolves.toBeInstanceOf(Response);

    fail = false;
    await traceRequest({ method: "GET", route: "/" }, async () => new Response("ok"));

    expect(JSON.parse(lines[0]!)).toMatchObject({ "telemetry.internal_errors": 1 });
  });

  it("survives spans it cannot make sense of", () => {
    const processor = new WideEventProcessor({ serviceName: "hcm-test", write: () => undefined });

    expect(() => processor.onStart({} as never, {} as never)).not.toThrow();
    expect(() => processor.onStart(null as never, null as never)).not.toThrow();
    expect(() => processor.onEnd({} as never)).not.toThrow();
    expect(() => processor.onEnd(null as never)).not.toThrow();
    expect(() => processor.onEnding(null as never)).not.toThrow();
  });
});

describe("annotations and errors", () => {
  it("take hostile values without throwing", async () => {
    const { events } = setUpTelemetry();
    const hostile = Object.defineProperty({}, "boom", {
      enumerable: true,
      get() {
        throw new Error("getter");
      }
    });
    const hostileError = Object.defineProperty({}, "message", {
      get() {
        throw new Error("getter");
      }
    });

    await traceRequest({ method: "GET", route: "/" }, async () => {
      expect(() => annotate(hostile)).not.toThrow();
      expect(() => annotate(null as never)).not.toThrow();
      expect(() => increment("count", Number.NaN)).not.toThrow();
      expect(() => recordError(hostileError)).not.toThrow();
      expect(() => recordError(undefined)).not.toThrow();
      expect(() => recordError("a string was thrown")).not.toThrow();
      return new Response("ok");
    });

    expect(events()).toHaveLength(1);
  });

  it("takes a function that works the fields out, and counts one that throws", async () => {
    const { events } = setUpTelemetry();
    const compute = vi.fn(() => ({ "client.id": "c1", "client.photo_count": 2 }));

    await traceRequest({ method: "GET", route: "/" }, async () => {
      annotate(compute);
      expect(() =>
        annotate(() => {
          throw new Error("row without a field");
        })
      ).not.toThrow();
      annotate({ "client.is_new": true });
      return new Response("ok");
    });

    expect(compute).toHaveBeenCalledTimes(1);
    expect(events()[0]).toMatchObject({ "client.id": "c1", "client.photo_count": 2, "client.is_new": true, "telemetry.internal_errors": 1 });
  });

  it("does not call the function when no event is running", () => {
    tearDownTelemetry();
    const compute = vi.fn(() => ({ a: 1 }));

    annotate(compute);

    expect(compute).not.toHaveBeenCalled();
  });

  it("helpers never throw", () => {
    expect(hashPrefix("token")).toMatch(/^[0-9a-f]{12}$/);
    expect(requestFields(new Headers({ "x-vercel-id": "fra1::abc", "content-length": "12" }))).toMatchObject({ "request.id": "fra1::abc", "http.request_bytes": 12 });
    expect(requestFields(null as never)).toEqual({});
  });
});
