import type { IncomingMessage, Server, ServerResponse } from "node:http";

// Set on the class of HTTP servers once TRACE is refused there: development runs `register`
// again when this file changes.
const refusesTrace = Symbol.for("hcm.refusesTrace");

/**
 * Refuses TRACE with 405 before the framework sees it. The framework hands every request to
 * proxy.ts as a Fetch Request, and a Fetch Request cannot carry that method: TRACE ended in a
 * server error on every address before any code of this app ran, so it cannot be refused in
 * proxy.ts with the other methods. Nothing on this server answers TRACE.
 *
 * This reaches into the HTTP server of Node, which is where the framework listens (`next dev`,
 * `next start`). A host that hands requests over in another way has to refuse TRACE itself.
 */
function refuseTrace(servers: Server & { [refusesTrace]?: true }) {
  if (servers[refusesTrace]) {
    return;
  }

  const emit = servers.emit;

  servers[refusesTrace] = true;
  servers.emit = function (this: Server, name: string | symbol, ...args: unknown[]) {
    const [request, response] = args as [IncomingMessage, ServerResponse];

    if (name !== "request" || request.method !== "TRACE") {
      return emit.call(this, name, ...args);
    }

    response
      .writeHead(405, {
        // As proxy.ts has it: only the API takes anything but reading.
        Allow: request.url?.startsWith("/api/") ? "GET, HEAD, POST" : "GET, HEAD",
        "Content-Length": 0
      })
      .end();

    return true;
  } as Server["emit"];
}

// Run by the framework once, when the server starts.
export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    const { Server } = await import("node:http");

    refuseTrace(Server.prototype);
  }
}
