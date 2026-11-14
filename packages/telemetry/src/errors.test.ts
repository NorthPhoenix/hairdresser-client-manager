import { afterEach, describe, expect, it } from "vitest";
import { describeError, stripPrismaArguments } from "./errors";
import { recordError, traceRequest } from "./index";
import { setUpTelemetry, tearDownTelemetry } from "./testSupport";
import prismaErrors from "./fixtures/prisma-validation-errors.json";

afterEach(tearDownTelemetry);

// What the made-up Client in the fixture is called, where she lives and what her notes say.
const personal = ["Анна", "Петрова", "Allergic", "ammonia", "Ленина", "912 345", "st_1"];

/** A PrismaClientValidationError as Prisma 7.8 makes it, from the captured message and stack. */
function prismaValidationError(captured: { name: string; message: string; stack: string }) {
  const error = new Error(captured.message);

  error.name = captured.name;
  error.stack = captured.stack;

  return error;
}

function expectNothingPersonal(text: string | undefined) {
  for (const value of personal) {
    expect(text ?? "").not.toContain(value);
  }
}

describe("an error from Prisma that lists the call's arguments", () => {
  it("keeps the call and the explanation, and drops the arguments, in the message and the stack", () => {
    const info = describeError(prismaValidationError(prismaErrors.unknownArgument));

    expect(info.type).toBe("PrismaClientValidationError");
    expect(info.message).toBe("Invalid `prisma.client.create()` invocation:\n\n[arguments removed]\n\nUnknown argument `nope`. Did you mean `note`? Available options are marked with ?.");
    expectNothingPersonal(info.message);
    expectNothingPersonal(info.stack);
    expect(info.stack).toContain("PrismaClientValidationError: \nInvalid `prisma.client.create()` invocation:\n\n[arguments removed]\n\nUnknown argument `nope`.");
    // The frames are kept.
    expect(info.stack).toContain("    at zr.request (");
    expect(info.stack).toContain("    at async file:///app/server/clients.js:5:3");
  });

  it("does so for a value Prisma refused, and in the message of a cause", () => {
    const refused = prismaValidationError(prismaErrors.invalidValue);
    const info = describeError(new Error("The Clients could not be listed.", { cause: refused }));

    expect(info.causeMessage).toBe("Invalid `prisma.client.findMany()` invocation:\n\n[arguments removed]\n\nArgument `note`: Invalid value provided. Expected StringNullableFilter, String or Null, provided Int.");
    expect(info.causeType).toBe("PrismaClientValidationError");
    expectNothingPersonal(info.stack);
  });

  it("leaves a Prisma message without arguments as it is", () => {
    expect(stripPrismaArguments("Invalid `prisma.profileShare.findUnique()` invocation:\n\n\nCan't reach database server at 127.0.0.1:54329")).toBe(
      "Invalid `prisma.profileShare.findUnique()` invocation:\n\nCan't reach database server at 127.0.0.1:54329"
    );
    expect(stripPrismaArguments("Something else failed.")).toBe("Something else failed.");
  });

  it("drops the source lines and arguments when Prisma names the file of the call", () => {
    const message = [
      "Invalid `prisma.client.create()` invocation in",
      "/app/packages/api/src/routers/client.ts:42:5",
      "",
      "  41 const client =",
      "→ 42 await prisma.client.create({",
      "       data: {",
      '         name: "Анна Петрова",',
      "         nope: 1,",
      "         ~~~~",
      "       }",
      "     })",
      "",
      "Unknown argument `nope`."
    ].join("\n");

    expect(stripPrismaArguments(message)).toBe("Invalid `prisma.client.create()` invocation in\n/app/packages/api/src/routers/client.ts:42:5\n\n[arguments removed]\n\nUnknown argument `nope`.");
  });

  it("puts only the cleaned text into the event and onto the exported span", async () => {
    const { events } = setUpTelemetry();

    await traceRequest({ method: "POST", route: "/api/trpc/[trpc]" }, async () => {
      recordError(prismaValidationError(prismaErrors.unknownArgument));

      return new Response(null, { status: 500 });
    });

    const line = JSON.stringify(events()[0]);

    expect(events()[0]).toMatchObject({ "error.type": "PrismaClientValidationError", outcome: "error" });
    expectNothingPersonal(line);
  });
});

describe("the type and code of an error", () => {
  it("takes the name a minified class of Clerk keeps in `kind`, and the codes of its answer", () => {
    // As the production build has it: Clerk's ClerkAPIResponseError, renamed `a`, whose `name`
    // is its class's name.
    class a extends Error {
      static kind = "ClerkAPIResponseError";
      code = "api_response_error";
      status = 401;
      errors = [{ code: "authentication_invalid", message: "Unauthorized" }];

      override get name() {
        return this.constructor.name;
      }
    }

    const info = describeError(new a("Unauthorized"));

    expect(info).toMatchObject({ type: "ClerkAPIResponseError", code: "api_response_error", apiCode: "authentication_invalid", httpStatus: 401 });
  });

  it("does not take a minified class name for a type", () => {
    class e extends Error {}

    expect(describeError(new e("x")).type).toBe("Error");
    expect(describeError(new TypeError("x")).type).toBe("TypeError");
    expect(describeError(Object.assign(new Error("x"), { name: "TRPCError", code: "CONFLICT" }))).toMatchObject({ type: "TRPCError", code: "CONFLICT" });
  });

  it("reads Prisma's code of a failure to connect and Clerk's reason of a token it refused", () => {
    expect(describeError(Object.assign(new Error("Can't reach database server"), { name: "PrismaClientInitializationError", errorCode: "P1001" }))).toMatchObject({ code: "P1001", retriable: true });
    expect(describeError(Object.assign(new Error("JWT is expired"), { reason: "token-expired", action: "Sign in again." })).code).toBe("token-expired");
    // A `reason` alone, in words, is not a code.
    expect(describeError(Object.assign(new Error("x"), { reason: "because it was late" })).code).toBeUndefined();
  });
});
