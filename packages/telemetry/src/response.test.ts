import { SpanKind } from "@opentelemetry/api";
import { createServer, ServerResponse, type IncomingMessage } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
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
