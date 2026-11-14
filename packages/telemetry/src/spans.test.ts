import { SpanKind, SpanStatusCode, trace } from "@opentelemetry/api";
import type { ReadableSpan, SpanExporter } from "@opentelemetry/sdk-trace-base";
import { afterEach, describe, expect, it } from "vitest";
import { annotate, recordError } from "./index";
import { setUpTelemetry, tearDownTelemetry } from "./testSupport";
import prismaErrors from "./fixtures/prisma-validation-errors.json";

afterEach(tearDownTelemetry);

type Exported = { name: string; attributes: Record<string, unknown>; events: { name: string; attributes?: Record<string, unknown> }[]; status: { code: number; message?: string } };

/**
 * An exporter that copies each span as it is handed over, as Vercel's exporter serializes it: a
 * change made to a span after that would not reach the collector.
 */
function snapshotExporter() {
  const spans: Exported[] = [];
  const exporter: SpanExporter = {
    export(batch: ReadableSpan[], done) {
      for (const span of batch) {
        spans.push(JSON.parse(JSON.stringify({ name: span.name, attributes: span.attributes, events: span.events, status: span.status })) as Exported);
      }

      done({ code: 0 });
    },
    shutdown: () => Promise.resolve()
  };

  return { spans, exporter };
}

const token = "q7Zr2LmXw9PbT4kVn8Hc3YsJd6Ga1Ef0";
const fileKey = "Xk29fileKeyOfAClientPhoto0001.jpg";

/** A request as Next.js and @vercel/otel trace it on Vercel, with an outgoing call to storage. */
function frameworkRequest(tracer: ReturnType<typeof trace.getTracer>) {
  tracer.startActiveSpan(
    "GET",
    {
      kind: SpanKind.SERVER,
      attributes: {
        "http.method": "GET",
        "http.target": `/profile-shares/${token}?lang=en`,
        "http.referer": `https://hcm.example.app/profile-shares/${token}?lang=en`,
        "url.query": "lang=en",
        "next.span_type": "BaseServer.handleRequest"
      }
    },
    (root) => {
      const call = tracer.startSpan(`fetch GET https://webfixture.ufs.sh/f/${fileKey}`, {
        kind: SpanKind.CLIENT,
        attributes: {
          "http.method": "GET",
          "http.url": `https://webfixture.ufs.sh/f/${fileKey}`,
          "url.full": `https://webfixture.ufs.sh/f/${fileKey}`,
          "resource.name": `https://webfixture.ufs.sh/f/${fileKey}`,
          "http.host": "webfixture.ufs.sh",
          "net.peer.name": "webfixture.ufs.sh",
          "http.status_code": 200
        }
      });

      call.end();

      // The framework records a failure it caught as it is, Prisma's arguments and all.
      const failed = tracer.startSpan("render route (app) /profile-shares/[token]");
      const error = Object.assign(new Error(prismaErrors.unknownArgument.message), { name: "PrismaClientValidationError", stack: prismaErrors.unknownArgument.stack });

      failed.recordException(error);
      failed.setStatus({ code: SpanStatusCode.ERROR, message: error.message });
      failed.end();

      // Next.js names the route just before the span ends.
      root.setAttributes({ "http.route": "/profile-shares/[token]", "next.route": "/profile-shares/[token]", "http.status_code": 200 });
      root.updateName("GET /profile-shares/[token]");
      root.end();
    }
  );
}

describe("exported spans", () => {
  for (const exporterFirst of [false, true]) {
    it(`carry no path, address, file key or argument of a Client${exporterFirst ? " (exporter set up first)" : ""}`, () => {
      const { spans, exporter } = snapshotExporter();
      const { tracer, lines } = setUpTelemetry({ exporter, exporterFirst });

      frameworkRequest(tracer);

      const root = spans.find((span) => span.attributes["next.span_type"] === "BaseServer.handleRequest");
      const call = spans.find((span) => span.attributes["http.method"] === "GET" && span.attributes["net.peer.name"]);
      const failed = spans.find((span) => span.name.startsWith("render"));

      expect(root?.attributes).toMatchObject({ "http.target": "/profile-shares/[token]", "http.referer": "https://hcm.example.app" });
      expect(root?.attributes).not.toHaveProperty("url.query");
      expect(call?.name).toBe("fetch GET webfixture.ufs.sh");
      expect(call?.attributes).toMatchObject({ "http.url": "https://webfixture.ufs.sh", "url.full": "https://webfixture.ufs.sh", "resource.name": "https://webfixture.ufs.sh" });
      expect(failed?.events[0]?.attributes?.["exception.message"]).toContain("[arguments removed]");
      expect(failed?.status.message).toContain("[arguments removed]");

      const everything = JSON.stringify(spans) + lines.join("\n");

      for (const secret of [token, fileKey, "Анна", "Allergic", "lang=en"]) {
        expect(everything).not.toContain(secret);
      }
    });
  }

  it("carry the cleaned error that code recorded", () => {
    const { spans, exporter } = snapshotExporter();
    const { tracer } = setUpTelemetry({ exporter });

    tracer.startActiveSpan("POST /api/trpc/[trpc]", { kind: SpanKind.SERVER }, (root) => {
      recordError(Object.assign(new Error(prismaErrors.invalidValue.message), { name: "PrismaClientValidationError", stack: prismaErrors.invalidValue.stack }));
      root.end();
    });

    expect(JSON.stringify(spans)).not.toContain("Анна");
    expect(JSON.stringify(spans)).toContain("[arguments removed]");
  });

  it("keep the status that proxy.ts annotated over the framework's", () => {
    const { spans, exporter } = snapshotExporter();
    const { tracer, events } = setUpTelemetry({ exporter });

    tracer.startActiveSpan("GET", { kind: SpanKind.SERVER, attributes: { "http.method": "GET", "next.span_type": "BaseServer.handleRequest" } }, (root) => {
      annotate({ "http.status_code": 405 });
      root.setAttribute("http.status_code", 200);
      root.end();
    });

    expect(spans[0]?.attributes["http.status_code"]).toBe(405);
    expect(events()[0]).toMatchObject({ "http.status_code": 405, outcome: "refused" });
  });
});
