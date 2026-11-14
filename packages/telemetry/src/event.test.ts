import { SpanKind, SpanStatusCode, context, trace } from "@opentelemetry/api";
import { afterEach, describe, expect, it } from "vitest";
import { maxLineLength } from "./event";
import { annotate, increment, recordError, traced, traceRequest } from "./index";
import { setUpTelemetry, tearDownTelemetry } from "./testSupport";

afterEach(tearDownTelemetry);

/** A span that stands in for what Prisma's instrumentation records for one operation. */
async function prismaOperation(tracer: ReturnType<typeof trace.getTracer>, name: string, queryMs: number) {
  await tracer.startActiveSpan("prisma:client:operation", { attributes: { name, model: name.split(".")[0], method: name.split(".")[1] } }, async (operation) => {
    // Exact times, so the sums are exact.
    const start = Date.now();
    const query = tracer.startSpan("prisma:client:db_query", { kind: SpanKind.CLIENT, startTime: start, attributes: { "db.query.text": "SELECT 1" } });

    query.end(start + queryMs);
    operation.end();
  });
}

describe("the wide event", () => {
  it("is one line per request with what code anywhere in the request annotated", async () => {
    const { events, tracer, lines } = setUpTelemetry();

    await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
      annotate({ "trpc.procedure": "client.list", stylist: { id: "stylist_1" } });
      await traced("client.load_page", async () => {
        // Deep in the call stack, with nothing passed down.
        annotate({ "client.count": 12 });
        increment("client.page_count");
        increment("client.page_count");
        await prismaOperation(tracer, "Client.findMany", 30);
        await prismaOperation(tracer, "Client.findMany", 10);
        await prismaOperation(tracer, "Stylist.upsert", 5);
      });
      const fetchStart = Date.now();
      const fetchSpan = tracer.startSpan("fetch GET https://api.example.com/x", {
        kind: SpanKind.CLIENT,
        startTime: fetchStart,
        attributes: { "http.method": "GET", "http.url": "https://api.example.com/x?file=secret", "http.status_code": 200 }
      });
      fetchSpan.end(fetchStart + 40);

      return new Response("ok", { status: 200 });
    });

    expect(lines).toHaveLength(1);
    expect(lines[0]).not.toContain("\n");

    const [event] = events();

    expect(event).toMatchObject({
      event: "hcm.request",
      schema_version: 1,
      name: "POST /api/trpc/[trpc]",
      outcome: "success",
      "http.method": "POST",
      "http.route": "/api/trpc/[trpc]",
      "http.status_code": 200,
      "service.name": "hcm-test",
      "trpc.procedure": "client.list",
      "stylist.id": "stylist_1",
      "client.count": 12,
      "client.page_count": 2,
      "instance.cold_start": true,
      "instance.request_seq": 1,
      "db.query_count": 3,
      "db.operation_count": 3,
      "db.operations": { "Client.findMany": 2, "Stylist.upsert": 1 },
      "db.slowest_query_ms": 30,
      "external.call_count": 1,
      "external.hosts": ["api.example.com"],
      "slowest_span.name": "http:api.example.com",
      "sample.reason": "rate",
      "sample.rate": 1,
      "trace.continued": false
    });
    expect(event?.trace_id).toMatch(/^[0-9a-f]{32}$/);
    expect(event?.["db.query_ms"]).toBe(45);
    // Prisma 7 never marks a query's span as failed, so a count of failed queries would always be 0.
    expect(event).not.toHaveProperty("db.error_count");
    expect(JSON.stringify(event)).not.toContain("secret");
    expect(event).not.toHaveProperty("error.type");
  });

  it("continues an incoming traceparent", async () => {
    const { events } = setUpTelemetry();
    const traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

    await traceRequest({ method: "GET", route: "/", headers: new Headers({ traceparent: `00-${traceId}-00f067aa0ba902b7-01` }) }, async () => new Response(null, { status: 204 }));

    expect(events()[0]).toMatchObject({ trace_id: traceId, parent_span_id: "00f067aa0ba902b7", "trace.continued": true, "http.status_code": 204 });
  });

  it("records a refusal on purpose as refused, with its code and reason and no failure status", async () => {
    const { events } = setUpTelemetry();

    await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
      recordError(Object.assign(new Error("This Appointment overlaps another."), { name: "TRPCError", code: "CONFLICT" }), { expected: true, reason: "appointmentOverlaps" });
      return new Response(null, { status: 409 });
    });

    expect(events()[0]).toMatchObject({
      outcome: "refused",
      "error.type": "TRPCError",
      "error.code": "CONFLICT",
      "error.reason": "appointment_overlaps",
      "error.expected": true,
      "error.count": 1,
      "sample.reason": "refused"
    });
  });

  it("records a failure with its stack and cause, and keeps the first failure over a refusal", async () => {
    const { events } = setUpTelemetry();
    const cause = Object.assign(new Error("Connection terminated unexpectedly"), { code: "ECONNRESET" });

    await expect(
      traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
        recordError(new Error("not found"), { expected: true, code: "NOT_FOUND" });
        throw new Error("Could not save", { cause });
      })
    ).rejects.toThrow("Could not save");

    const [event] = events();

    expect(event).toMatchObject({
      outcome: "error",
      "http.status_code": 500,
      "error.type": "Error",
      "error.message": "Could not save",
      "error.code": "ECONNRESET",
      "error.retriable": true,
      "error.expected": false,
      "error.cause_message": "Connection terminated unexpectedly",
      "error.count": 2,
      "sample.reason": "error"
    });
    expect(event?.["error.stack"]).toContain("Caused by:");
  });

  it("takes the error from a child span's exception when nothing else was recorded", async () => {
    const { events, tracer } = setUpTelemetry();

    await traceRequest({ method: "GET", route: "/profile-shares/[token]" }, async () => {
      const span = tracer.startSpan("render route (app) /profile-shares/[token]");
      span.recordException(new TypeError("Cannot read properties of undefined"));
      span.setStatus({ code: SpanStatusCode.ERROR });
      span.end();
      return new Response(null, { status: 500 });
    });

    expect(events()[0]).toMatchObject({ outcome: "error", "error.type": "TypeError", "error.message": "Cannot read properties of undefined" });
  });

  it("prefers the error code recorded over the framework's record of the same failure", async () => {
    const { events, tracer } = setUpTelemetry();

    await traceRequest({ method: "GET", route: "/profile-shares/[token]" }, async () => {
      const failure = Object.assign(new Error("Can't reach database server"), { name: "PrismaClientKnownRequestError", code: "P1001" });
      const span = tracer.startSpan("render route (app) /profile-shares/[token]");
      span.recordException(failure);
      span.setStatus({ code: SpanStatusCode.ERROR });
      span.end();
      recordError(failure);
      return new Response(null, { status: 500 });
    });

    expect(events()[0]).toMatchObject({ "error.type": "PrismaClientKnownRequestError", "error.code": "P1001", "error.retriable": true, "error.count": 1 });
  });

  it("records a refusal once when a router records it with its reason and onError records it again", async () => {
    const { events } = setUpTelemetry();
    const refusal = Object.assign(new Error("This Appointment overlaps another."), { name: "TRPCError", code: "CONFLICT" });

    await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
      recordError(refusal, { expected: true, reason: "appointment_overlaps" });
      recordError(refusal, { expected: true, code: "CONFLICT" });
      return new Response(null, { status: 409 });
    });

    expect(events()[0]).toMatchObject({ outcome: "refused", "error.reason": "appointment_overlaps", "error.code": "CONFLICT", "error.count": 1 });
  });

  it("keeps a failure the code got over without changing the outcome", async () => {
    const { events } = setUpTelemetry({ config: { sampleRate: 0 } });

    await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
      recordError(new Error("UploadThing did not delete the files."), { handled: true, reason: "photo_storage_delete_failed" });
      return new Response(null, { status: 200 });
    });

    expect(events()[0]).toMatchObject({ outcome: "success", "error.handled": true, "error.reason": "photo_storage_delete_failed", "sample.reason": "error" });
  });

  it("is sampled after the request: dropped at rate 0 unless it failed or was slow", async () => {
    const { lines, events } = setUpTelemetry({ config: { sampleRate: 0, slowMs: 50 } });

    await traceRequest({ method: "GET", route: "/" }, async () => new Response(null, { status: 200 }));
    expect(lines).toHaveLength(0);

    await traceRequest({ method: "GET", route: "/" }, async () => new Response(null, { status: 404 }));
    await traceRequest({ method: "GET", route: "/" }, async () => {
      await new Promise((resolve) => setTimeout(resolve, 60));
      return new Response(null, { status: 200 });
    });

    expect(events().map((event) => event["sample.reason"])).toEqual(["refused", "slow"]);
    expect(events()[1]?.["instance.request_seq"]).toBe(3);
  });

  it("names the fields the privacy guard refused", async () => {
    const { events } = setUpTelemetry();

    await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
      annotate({ "client.phone": "+7 999 123 45 67", "client.note_length": 4 });
      return new Response(null, { status: 200 });
    });

    const [event] = events();

    expect(event?.["telemetry.blocked_keys"]).toEqual(["client.phone:personal"]);
    expect(JSON.stringify(event)).not.toContain("999");
    expect(event?.["client.note_length"]).toBe(4);
  });

  it("is never longer than one log line may be", async () => {
    const { lines } = setUpTelemetry();

    await traceRequest({ method: "POST", route: "/" }, async () => {
      annotate(Object.fromEntries(Array.from({ length: 400 }, (_, index) => [`field_${index}`, "x".repeat(150)])));
      recordError(Object.assign(new Error("boom"), { stack: "y".repeat(10_000) }));
      return new Response(null, { status: 500 });
    });

    expect(lines[0]!.length).toBeLessThanOrEqual(maxLineLength);
    expect(JSON.parse(lines[0]!)).toMatchObject({ "telemetry.truncated": true, outcome: "error", "error.message": "boom" });
  });

  it("labels the root span Next.js opens for proxy.ts, which names no route", async () => {
    const { events, tracer } = setUpTelemetry();

    tracer.startActiveSpan("GET", { kind: SpanKind.SERVER, attributes: { "http.method": "GET", "next.span_type": "BaseServer.handleRequest" } }, (root) => {
      tracer.startSpan("middleware GET", { attributes: { "next.span_type": "Middleware.execute", "http.target": "/profile-shares/abc" } }).end();
      root.setAttribute("http.status_code", 200);
      root.end();
    });

    expect(events()[0]).toMatchObject({ "http.route": "proxy", "next.proxy": true, outcome: "success" });
    expect(JSON.stringify(events()[0])).not.toContain("/profile-shares/abc");
  });

  it("opens a task event for work outside any request", async () => {
    const { events } = setUpTelemetry();

    await context.with(context.active(), () => traced("release_manifest.refresh", async () => annotate({ "release.version_code": 18 })));

    expect(events()[0]).toMatchObject({ event: "hcm.task", name: "release_manifest.refresh", "release.version_code": 18 });
    expect(events()[0]).not.toHaveProperty("instance.request_seq");
  });

  it("counts the proxy's own root span on Vercel as a request, with the status the proxy answered", async () => {
    const { events } = setUpTelemetry();
    // On Vercel, proxy.ts runs in a function of its own, under a root span of kind INTERNAL.
    const nextTracer = trace.getTracer("next.js");

    nextTracer.startActiveSpan("middleware POST", { attributes: { "next.span_type": "Middleware.execute", "http.method": "POST", "http.target": "/profile-shares/abc" } }, (root) => {
      annotate({ "proxy.decision": "method_refused", "http.status_code": 405 });
      root.end();
    });

    expect(events()).toHaveLength(1);
    expect(events()[0]).toMatchObject({
      event: "hcm.request",
      name: "POST",
      "http.method": "POST",
      "http.route": "proxy",
      "http.status_code": 405,
      outcome: "refused",
      "next.proxy": true,
      "instance.request_seq": 1,
      "instance.cold_start": true
    });
    expect(JSON.stringify(events()[0])).not.toContain("/profile-shares/abc");
  });

  it("gives up work that goes on after its request, and everything that work starts", async () => {
    const { events, tracer } = setUpTelemetry();
    let late: ReturnType<typeof tracer.startSpan> | undefined;

    tracer.startActiveSpan("GET /", { kind: SpanKind.SERVER }, (root) => {
      root.end();
      // A promise left running starts a span after the request ended, and that span starts more.
      late = tracer.startSpan("hcm.late_work", {}, trace.setSpan(context.active(), root));
    });

    const lateContext = trace.setSpan(context.active(), late!);
    const call = tracer.startSpan("fetch GET https://webfixture.ufs.sh/f/fileKeyOfAPhoto", { kind: SpanKind.CLIENT, attributes: { "http.method": "GET", "http.url": "https://webfixture.ufs.sh/f/fileKeyOfAPhoto" } }, lateContext);
    const query = tracer.startSpan("prisma:client:operation", {}, trace.setSpan(context.active(), call));

    query.end();
    call.end();
    late!.end();

    expect(events()).toHaveLength(1);
    expect(events()[0]).toMatchObject({ event: "hcm.request", name: "GET /" });
  });

  it("never names an event after the address of an outgoing call", async () => {
    const { events, tracer } = setUpTelemetry();

    // A call made outside any request, such as at start-up.
    tracer.startSpan("fetch GET https://webfixture.ufs.sh/f/fileKeyOfAPhoto", { kind: SpanKind.CLIENT, attributes: { "http.method": "GET", "http.url": "https://webfixture.ufs.sh/f/fileKeyOfAPhoto" } }).end();

    expect(events()[0]).toMatchObject({ event: "hcm.task", name: "fetch GET webfixture.ufs.sh" });
    expect(JSON.stringify(events())).not.toContain("fileKeyOfAPhoto");
  });

  it("opens no event for the framework's own work outside any request", async () => {
    const { events } = setUpTelemetry();
    const nextTracer = trace.getTracer("next.js");

    // Next.js starts some responses after the request's span ended.
    nextTracer.startActiveSpan("start response", { attributes: { "next.span_type": "NextNodeServer.startResponse" } }, (span) => {
      nextTracer.startSpan("render", { attributes: { "next.span_type": "AppRender.getBodyResult" } }).end();
      span.end();
    });

    expect(events()).toHaveLength(0);
  });
});
