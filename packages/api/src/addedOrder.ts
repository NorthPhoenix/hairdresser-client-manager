/**
 * Services and Color Formulas are listed oldest first, in the order they were added. Rows written
 * by one request can share a timestamp, and rows with equal timestamps have no order of their own:
 * editing one of them moves it. The id settles them, so the order never changes between loads.
 */
export const addedOrder: [{ createdAt: "asc" }, { id: "asc" }] = [{ createdAt: "asc" }, { id: "asc" }];

/** The same order, for rows that are already loaded. */
export function inAddedOrder<Row extends { id: string; createdAt: Date }>(rows: Row[]) {
  return [...rows].sort(
    (a, b) => a.createdAt.getTime() - b.createdAt.getTime() || Number(a.id > b.id) - Number(a.id < b.id)
  );
}
