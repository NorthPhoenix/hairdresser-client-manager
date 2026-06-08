# Use Prisma schema push for v1

For v1, the Prisma schema is the source of truth for database shape, and schema changes are applied with `prisma db push` rather than committed Prisma migrations. This keeps early schema iteration lightweight while the product model is still settling.

Agents should update `schema.prisma` and validate it, but should not automatically run `db:push` against shared databases. Humans run `db:push` for shared environments after reviewing the schema change; agents may run it only against local or throwaway databases.
