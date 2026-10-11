import { vi } from "vitest";
import type { TRPCContext } from "./context";

/**
 * Gives a mocked database what `stylistTransactionProcedure` needs: the lock statement, and a
 * `$transaction` that runs its callback with the same mocks. Like the lock it stands in for, it
 * runs one transaction at a time, so a test can start two mutations together and see the second
 * one read what the first one wrote.
 */
export function withTransaction(models: object) {
  const tx = {
    ...models,
    $executeRaw: vi.fn().mockResolvedValue(1)
  };
  let last: Promise<unknown> = Promise.resolve();

  return {
    ...tx,
    $transaction: vi.fn((run: (tx: unknown) => Promise<unknown>) => {
      const result = last.then(() => run(tx));

      last = result.catch(() => undefined);

      return result;
    })
  } as unknown as TRPCContext["db"] & {
    $executeRaw: ReturnType<typeof vi.fn>;
    $transaction: ReturnType<typeof vi.fn>;
  };
}

/**
 * The first validation issue of a refused input: the app reads its message to tell which rule was
 * broken, and its path to tell which field.
 */
export async function firstIssueOf(call: Promise<unknown>) {
  const error = await call.then(
    () => {
      throw new Error("The call was not refused.");
    },
    (reason: { code?: string; message: string }) => reason
  );
  const issues = JSON.parse(error.message) as { message: string; path: (string | number)[] }[];

  return {
    code: error.code,
    message: issues[0]?.message,
    field: issues[0]?.path.join(".")
  };
}
