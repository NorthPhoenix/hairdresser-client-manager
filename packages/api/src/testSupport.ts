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
