# Lazily create Stylists from Clerk identity

In v1, Clerk is the authentication provider and the database Stylist is the app-domain identity. The backend lazily upserts a Stylist by Clerk user id on the first authenticated mobile API call, using request-provided defaults such as language and timezone when needed, and domain records then relate to the database Stylist id rather than storing Clerk ids directly.

Stylist domain state, including language, timezone, salon address, and onboarding completion, lives in the app database rather than Clerk metadata. Clerk metadata can be considered later only for tiny auth-adjacent claims if a proven hot path needs them, but it is not the source of truth for Stylist settings.

Lazy creation creates only the minimum valid Stylist record, using Clerk id plus initial language and timezone defaults when available. It does not mark onboarding complete; `onboardingCompletedAt` is set only by an explicit onboarding or settings mutation after the Stylist confirms the required setup fields.
