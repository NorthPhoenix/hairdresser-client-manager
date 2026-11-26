import { SpanKind } from "@opentelemetry/api";
import { createServer, ServerResponse, type IncomingMessage } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { recordingStatus } from "./index";
import { recordResponseStatus } from "./response";
import { setUpTelemetry, tearDownTelemetry } from "./testSupport";

afterEach(tearDownTelemetry);

// Its own class, so the test changes no other server's responses.
class Responses<Request extends IncomingMessage = IncomingMessage> extends ServerResponse<Request> {}

recordResponseStatus(Responses.prototype as never);

async function answer(write: (response: ServerResponse) => void) {
  const { events, tracer } = setUpTelemetry();
  const server = createServer({ ServerResponse: Responses }, (_request, response) => {
    tracer.startActiveSpan("GET /api/trpc/[trpc]", { kind: SpanKind.SERVER }, (span) => {
      write(response);
      // As Next.js does when Vercel calls a route's handler: the status it read too early.
      span.setAttribute("http.status_code", 200);
      span.end();
    });
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));

  try {
    const { port } = server.address() as AddressInfo;
    const response = await fetch(`http://127.0.0.1:${port}/`);

    await response.arrayBuffer();

    return { status: response.status, events: events() };
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

describe("the status a response is written with", () => {
  it("is the event's status, also when the framework says otherwise", async () => {
    const { status, events } = await answer((response) => {
      response.statusCode = 401;
      response.end();
    });

    expect(status).toBe(401);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ "http.status_code": 401 });
  });

  it("comes from an explicit writeHead too, and the response is unchanged", async () => {
    const { status, events } = await answer((response) => {
      response.writeHead(404, { "Content-Type": "text/plain" }).end("gone");
    });

    expect(status).toBe(404);
    expect(events[0]).toMatchObject({ "http.status_code": 404 });
  });

  it("is recorded once however often the prototype is set up", () => {
    const before = Responses.prototype.writeHead;

    recordResponseStatus(Responses.prototype as never);

    expect(Responses.prototype.writeHead).toBe(before);
  });
});

describe("the status of the Response a route handler returns", () => {
  // As on the first request of a new Vercel instance: the response is written through a
  // ServerResponse whose status response.ts never sees, and Next.js says 200 on the span.
  async function serve(handler: (request: Request) => Response | Promise<Response>) {
    const { events, tracer } = setUpTelemetry();
    const server = createServer((request, response) => {
      void tracer.startActiveSpan("GET /api/trpc/[trpc]", { kind: SpanKind.SERVER }, async (span) => {
        try {
          const answer = await handler(new Request(`http://localhost${request.url}`));

          response.statusCode = answer.status;
          response.end(await answer.text());
        } catch {
          response.statusCode = 500;
          response.end();
        }

        span.setAttribute("http.status_code", 200);
        span.end();
      });
    });

    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));

    try {
      const { port } = server.address() as AddressInfo;
      const response = await fetch(`http://127.0.0.1:${port}/`);

      await response.arrayBuffer();

      return { status: response.status, events: events() };
    } finally {
      await new Promise((resolve) => server.close(resolve));
    }
  }

  it("is the event's status, also when Node's writing of it is not recorded", async () => {
    const { status, events } = await serve(recordingStatus(async () => new Response("signed out", { status: 401 })));

    expect(status).toBe(401);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ "http.status_code": 401, outcome: "refused" });
  });

  it("comes from a handler that answers at once too", async () => {
    const { status, events } = await serve(recordingStatus(() => new Response(null, { status: 405, headers: { Allow: "GET, HEAD" } })));

    expect(status).toBe(405);
    expect(events[0]).toMatchObject({ "http.status_code": 405, outcome: "refused" });
  });

  it("is 500 for a handler that throws, as Next.js answers then, at once or later", async () => {
    for (const handler of [
      recordingStatus((): Response => {
        throw new Error("database away");
      }),
      recordingStatus(async (): Promise<Response> => {
        throw new Error("database away");
      })
    ]) {
      const { status, events } = await serve(handler);

      expect(status).toBe(500);
      expect(events[0]).toMatchObject({ "http.status_code": 500, outcome: "error" });
    }
  });

  it("leaves the status of Next.js's own signals, such as redirect(), to Next.js", async () => {
    const { events } = await serve(
      recordingStatus(async (): Promise<Response> => {
        throw Object.assign(new Error("NEXT_REDIRECT"), { digest: "NEXT_REDIRECT;replace;/elsewhere;307;" });
      })
    );

    expect(events[0]?.["http.status_code"]).toBe(200);
  });

  it("gives way to the status Node writes when that is recorded", async () => {
    const { status, events } = await answer((response) => {
      recordingStatus(() => new Response("ok"))();
      response.writeHead(503).end();
    });

    expect(status).toBe(503);
    expect(events[0]).toMatchObject({ "http.status_code": 503 });
  });

  it("says success for a 200 as before", async () => {
    const { events } = await serve(recordingStatus(async () => new Response("ok")));

    expect(events[0]).toMatchObject({ "http.status_code": 200, outcome: "success" });
  });

  it("leaves the handler's arguments, its Response and its failure as they are", async () => {
    const answer = new Response(null, { status: 404 });
    const seen: unknown[][] = [];
    const handler = recordingStatus((...args: unknown[]) => {
      seen.push(args);

      return answer;
    });
    const params = { params: Promise.resolve({ token: "t" }) };

    expect(handler("request", params)).toBe(answer);
    expect(seen).toEqual([["request", params]]);
    expect(await recordingStatus(async () => answer)()).toBe(answer);

    const failure = new Error("broken");

    expect(() => recordingStatus((): Response => {
      throw failure;
    })()).toThrow(failure);
    await expect(recordingStatus(async (): Promise<Response> => {
      throw failure;
    })()).rejects.toBe(failure);
  });

  it("does nothing outside a request", async () => {
    const { events } = setUpTelemetry();
    const answer = new Response(null, { status: 401 });

    expect(await recordingStatus(async () => answer)()).toBe(answer);
    expect(events()).toEqual([]);
  });
});
