// Local development backend for the native Android app.
//
// Serves the real tRPC router and the real UploadThing route handler against a
// local database, with two substitutions so no provider credentials are needed:
//   - Clerk is replaced by a `Bearer dev_<name>` header (see auth.ts).
//   - UploadThing's storage service is replaced by a local emulator under /_ut.
//
// Never deploy this. It binds to loopback only and trusts the caller's identity.
import { createHash, createHmac } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable } from "node:stream";
import { appRouter, createTRPCContext } from "@hcm/api";
import { db } from "@hcm/db";
import { fetchRequestHandler } from "@trpc/server/adapters/fetch";
import { createRouteHandler, createUploadthing, UploadThingError, type FileRouter } from "uploadthing/server";
import { z } from "zod";
import { getDevUserId } from "./auth.ts";

const port = Number(process.env.PORT ?? 3000);
const origin = process.env.DEV_BACKEND_ORIGIN ?? `http://127.0.0.1:${port}`;
const storageDir = process.env.DEV_BACKEND_STORAGE ?? join(tmpdir(), "hcm-dev-backend-uploads");
const uploadThingApiKey = "sk_live_hcm_dev_backend_only";
const uploadThingAppId = "hcmdevbackend";
const uploadThingToken = Buffer.from(
  JSON.stringify({ apiKey: uploadThingApiKey, appId: uploadThingAppId, regions: ["dev"] })
).toString("base64");

const f = createUploadthing();

// Mirrors apps/web/app/api/uploadthing/core.ts, with dev auth in place of Clerk.
const uploadRouter = {
  appointmentPhoto: f({ image: { maxFileSize: "8MB", maxFileCount: 10 } })
    .input(
      z.object({
        appointmentId: z.string(),
        clientId: z.string(),
        category: z.enum(["before", "after", "other"])
      })
    )
    .middleware(async ({ req, input }) => {
      const userId = getDevUserId(req.headers.get("authorization"));

      if (!userId) {
        throw new UploadThingError("Authentication required.");
      }

      const appointment = await db.appointment.findFirst({
        where: { id: input.appointmentId, stylist: { clerkId: userId } },
        include: { participants: true }
      });

      if (!appointment) {
        throw new UploadThingError("Appointment not found.");
      }

      if (!appointment.participants.some((participant) => participant.clientId === input.clientId)) {
        throw new UploadThingError("Photo Client must be attached to the Appointment.");
      }

      return { userId, appointmentId: input.appointmentId, clientId: input.clientId, category: input.category };
    })
    .onUploadComplete(({ metadata, file }) => ({
      appointmentId: metadata.appointmentId,
      clientId: metadata.clientId,
      category: metadata.category,
      fileKey: file.key,
      url: file.ufsUrl,
      thumbnailUrl: file.ufsUrl
    }))
} satisfies FileRouter;

const uploadThingHandler = createRouteHandler({
  router: uploadRouter,
  config: {
    token: uploadThingToken,
    isDev: false,
    ingestUrl: `${origin}/_ut`,
    callbackUrl: `${origin}/api/uploadthing`,
    logLevel: "Warning"
  }
});

type PendingUpload = {
  metadata: Record<string, unknown>;
  callbackUrl: string;
  callbackSlug: string;
  resolve?: (serverData: unknown) => void;
};

const pendingUploads = new Map<string, PendingUpload>();

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers }
  });
}

// Stands in for UploadThing's ingest service: accepts the presigned PUT, stores
// the file on disk, then calls the app's signed upload callback like the real one.
async function handleUploadThingEmulator(request: Request, url: URL): Promise<Response> {
  const path = url.pathname.replace(/^\/_ut/, "");

  if (request.method === "POST" && path === "/route-metadata") {
    const body = (await request.json()) as {
      fileKeys: string[];
      metadata: Record<string, unknown>;
      callbackUrl: string;
      callbackSlug: string;
    };

    for (const fileKey of body.fileKeys) {
      pendingUploads.set(fileKey, {
        metadata: body.metadata,
        callbackUrl: body.callbackUrl,
        callbackSlug: body.callbackSlug
      });
    }

    return json({ ok: true });
  }

  if (request.method === "POST" && path === "/callback-result") {
    const body = (await request.json()) as { fileKey: string; callbackData?: unknown };
    pendingUploads.get(body.fileKey)?.resolve?.(body.callbackData ?? null);
    return json({ ok: true });
  }

  const fileKey = decodeURIComponent(path.slice(1));

  if (path.startsWith("/f/") && request.method === "GET") {
    const storedKey = decodeURIComponent(path.slice(3));

    try {
      const [bytes, type] = await Promise.all([
        readFile(join(storageDir, storedKey)),
        readFile(join(storageDir, `${storedKey}.type`), "utf8")
      ]);
      return new Response(bytes, { headers: { "content-type": type, "cache-control": "public, max-age=3600" } });
    } catch {
      return json({ error: "Not found" }, 404);
    }
  }

  const pending = pendingUploads.get(fileKey);

  if (!pending) {
    return json({ error: "Unknown file key" }, 404);
  }

  if (request.method === "HEAD") {
    return new Response(null, { headers: { "x-ut-range-start": "0" } });
  }

  if (request.method === "PUT") {
    const form = await request.formData();
    const file = form.get("file");

    if (!(file instanceof File)) {
      return json({ error: "Missing file" }, 400);
    }

    const bytes = Buffer.from(await file.arrayBuffer());
    const type = url.searchParams.get("x-ut-file-type") ?? file.type;
    await mkdir(storageDir, { recursive: true });
    await writeFile(join(storageDir, fileKey), bytes);
    await writeFile(join(storageDir, `${fileKey}.type`), type);

    const fileUrl = `${origin}/_ut/f/${encodeURIComponent(fileKey)}`;
    const fileHash = createHash("md5").update(bytes).digest("hex");
    const payload = JSON.stringify({
      status: "uploaded",
      origin: `${origin}/_ut`,
      metadata: pending.metadata,
      file: {
        name: decodeURIComponent(url.searchParams.get("x-ut-file-name") ?? file.name),
        size: bytes.length,
        type,
        key: fileKey,
        customId: null,
        url: fileUrl,
        appUrl: fileUrl,
        ufsUrl: fileUrl,
        fileHash
      }
    });
    const serverData = new Promise<unknown>((resolve) => {
      pending.resolve = resolve;
    });
    const callbackUrl = new URL(pending.callbackUrl);
    callbackUrl.searchParams.set("slug", pending.callbackSlug);
    const callbackResponse = await fetch(callbackUrl, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "uploadthing-hook": "callback",
        "x-uploadthing-signature": `hmac-sha256=${createHmac("sha256", uploadThingApiKey).update(payload).digest("hex")}`
      },
      body: payload
    });

    if (!callbackResponse.ok) {
      return json({ error: `Upload callback failed with ${callbackResponse.status}` }, 500);
    }

    const result = await serverData;
    pendingUploads.delete(fileKey);

    return json({ url: fileUrl, appUrl: fileUrl, ufsUrl: fileUrl, fileHash, serverData: result });
  }

  return json({ error: "Unsupported" }, 405);
}

async function handle(request: Request): Promise<Response> {
  const url = new URL(request.url);

  if (url.pathname.startsWith("/api/trpc")) {
    return fetchRequestHandler({
      endpoint: "/api/trpc",
      req: request,
      router: appRouter,
      createContext: () =>
        createTRPCContext({
          auth: async () => ({ userId: getDevUserId(request.headers.get("authorization")) })
        })
    });
  }

  if (url.pathname === "/api/uploadthing") {
    return uploadThingHandler(request);
  }

  if (url.pathname.startsWith("/_ut/")) {
    return handleUploadThingEmulator(request, url);
  }

  if (url.pathname === "/") {
    return json({ name: "@hcm/dev-backend", ok: true });
  }

  return json({ error: "Not found" }, 404);
}

function toRequest(incoming: IncomingMessage): Request {
  const hasBody = incoming.method !== "GET" && incoming.method !== "HEAD";
  const headers = new Headers();

  for (const [name, value] of Object.entries(incoming.headers)) {
    if (value !== undefined) {
      headers.set(name, Array.isArray(value) ? value.join(", ") : value);
    }
  }

  return new Request(new URL(incoming.url ?? "/", origin), {
    method: incoming.method,
    headers,
    ...(hasBody ? { body: Readable.toWeb(incoming) as ReadableStream, duplex: "half" } : {})
  } as RequestInit);
}

async function respond(outgoing: ServerResponse, response: Response) {
  outgoing.statusCode = response.status;
  response.headers.forEach((value, name) => outgoing.setHeader(name, value));
  outgoing.end(response.body ? Buffer.from(await response.arrayBuffer()) : undefined);
}

createServer((incoming, outgoing) => {
  const startedAt = Date.now();

  handle(toRequest(incoming))
    .catch((error: unknown) => {
      console.error(error);
      return json({ error: "Internal dev backend error" }, 500);
    })
    .then((response) => {
      console.log(`${incoming.method} ${incoming.url?.split("?")[0]} -> ${response.status} (${Date.now() - startedAt}ms)`);
      return respond(outgoing, response);
    });
}).listen(port, "127.0.0.1", () => {
  console.log(`HCM dev backend listening on ${origin} (uploads in ${storageDir})`);
});
