# Separate mobile management from public web shares

In v1, the Turborepo is split so the Expo mobile app owns authenticated Stylist management workflows, while the Next.js web app owns only public Profile Share pages. Shared packages should stay limited to cross-app concerns, such as the database model and localization primitives, so the web app does not grow a duplicate Stylist management surface and shared code does not become a dumping ground for mobile-only workflow logic.
