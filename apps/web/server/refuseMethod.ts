import { recordError, recordingStatus } from "@hcm/telemetry";

/**
 * What a route that only shows something (a Share Image, a photo of a Profile Share) answers for
 * any other method: 405, naming the methods it answers, as proxy.ts answers for a page. These
 * routes do not go through the proxy (docs/adr/0010), and without this the framework answers
 * OPTIONS with 204 and another method with a 405 that names none. Its event says 405, also on a
 * new instance (recordingStatus says why).
 */
export const refuseMethod = recordingStatus((): Response => {
  recordError(new Error("Only the API takes anything but reading."), { expected: true, code: "METHOD_NOT_ALLOWED", reason: "method_not_allowed" });

  return new Response(null, {
    status: 405,
    headers: {
      Allow: "GET, HEAD"
    }
  });
});
