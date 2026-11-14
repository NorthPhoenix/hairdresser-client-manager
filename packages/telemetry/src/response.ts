// The status a server really answers with, for the request's event.
import { annotate } from "./index";

const recordsStatus = Symbol.for("hcm.telemetry.recordsStatus");

type WritesHead = { writeHead: (...args: never[]) => unknown; [recordsStatus]?: true };

/**
 * Makes every response written through `responses` (the prototype of Node's ServerResponse) put
 * its status into the event of the request it answers, as `http.status_code`.
 *
 * Next.js sets the status of a request's root span itself, but when Vercel calls a route's
 * handler directly it reads the status before the response is written: every route answered
 * 200 there, also a 401 or a 404. `writeHead` is where Node sends the status line, also for a
 * response that never calls it (Node calls it for them), and it runs in the request's context.
 * Recording never changes the response: the original `writeHead` gets the same arguments.
 */
export function recordResponseStatus(responses: WritesHead): void {
  if (responses[recordsStatus]) {
    return;
  }

  const writeHead = responses.writeHead;

  responses[recordsStatus] = true;
  responses.writeHead = function (this: unknown, ...args: never[]) {
    const status: unknown = args[0];

    if (typeof status === "number") {
      // annotate swallows its own failures and does nothing outside a request.
      annotate({ "http.status_code": status });
    }

    return writeHead.apply(this, args);
  } as WritesHead["writeHead"];
}
