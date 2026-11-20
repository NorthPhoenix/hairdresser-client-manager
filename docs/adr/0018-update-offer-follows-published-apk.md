# The update offer follows the published APK, not the deploy

The API tells the Android app which release is the latest (`app.releases`), and the app offers it with a link to download. Until now the latest release was simply the newest entry in `packages/api/src/releases.json`, so it changed with the deploy of `main`. The APK is published separately: built by hand and copied to the download folder on the maintainer's server, which Tailscale Funnel serves. When that copy came late, or never (1.6.0 was deployed on 2026-11-07 and its APK was never published; 1.7.0 replaced it twelve hours later), every app from 1.4.0 offered "Version 1.6.0 is out" and its button downloaded 1.5.0 again. Installing that changed nothing, and the offer stayed.

## The download folder says what is published

Publishing an APK (`apps/android/scripts/publish-apk.sh`) writes three files to the download folder, in this order: the APK under its own name (`client-manager-1.7.0.apk`, never changed afterwards), the same file as `client-manager.apk` for links that name no version, and last `latest.json`:

```json
{ "versionCode": 9, "versionName": "1.7.0", "file": "client-manager-1.7.0.apk", "sha256": "…", "publishedAt": "…" }
```

The server reads `latest.json` from the folder of `APP_DOWNLOAD_URL`, at most once a minute per server instance (see "The server reads ahead" for when it reads less often, and for a read ahead that failed), and:

- counts only the releases up to the one it names: the latest it offers is the newest of those, and a newer entry in releases.json is left out as if it did not exist yet;
- links to that release's own file, so the download is always the version offered, also while a newer one is being copied in;
- when it cannot read the file, keeps what it read last for an hour, and after that offers nothing newer than the app that asks. It never offers what it cannot see.

So the deploy and the APK can be published in either order. Nothing changes in the app: every app from 1.4.0 reads the same answer, with the same fields, and apps before 1.4.0 never asked.

## The app keeps the last answer

Reading `latest.json` makes `app.releases` the slowest read of an app start (p50 about 0.4 s in production, against about 0.08 s for Home's list), so the update card, and "What's new" after an update, used to slide in above the Appointments a moment after them. Since 2026-11 the app keeps the last answer in its preferences and starts from it; the server's answer replaces it, and is kept in turn when it differs. An answer from before the running version is not used, since it lacks some of the notes that version's "What's new" shows.

This is not domain data (ADR 0009) and not part of the read cache (ADR 0016): it is the same for every Stylist, holds nothing of anyone's, and stays across sign-out. What the server offered once stays offered while the app cannot reach the server; a published APK is never replaced, so its download stays the version offered.

## The server reads ahead

The app's first requests are spread over several server instances, and most of its `app.releases` calls came to one that had never read `latest.json`: 47 of 68 in the week to 2026-11-19, and 16 more came after the instance's answer had expired (on average almost six minutes later). Each of those waited about 0.4 s for the download server, which is exactly when a card is due, since the app's kept answer covers every other start. So:

- each instance of the tRPC route starts reading `latest.json` as its module loads (`apps/web/server/publishedRelease.ts`), and its first request keeps it running until that reading has ended (Next.js `after`), since on Vercel an instance is frozen once it has answered and has nothing left to do. A read ahead that fails while no request waits for it is not kept, and the next request reads again: it may have failed only because it was frozen with an instance whose first request was not the route's own (an automatic answer to OPTIONS, say). A request that waited for one that had been frozen (it started longer ago than its timeout) reads for itself. One that waited while it failed takes its answer and keeps it for a minute, as it would have kept a reading of its own, which would most likely have failed too (loading takes about 0.1 s of the read ahead's 2 s): while the download server is off, the requests after it do not wait for another timeout;
- an answer that names the newest release in releases.json, or a later one, is used for half an hour instead of a minute. This server offers nothing newer than that whatever `latest.json` says next, and a deploy that brings a newer release starts new instances, which read it afresh. Reading it again would tell only whether the download server is still there, or move the download to an APK published before its deploy, which the deploy does anyway. An answer that names an older release (a deploy before its APK) is read again after a minute as before, so a published APK is still offered within a minute. A release kept because `latest.json` could not be read is asked for again after a minute too.

The hour for which the release read last stays offered while `latest.json` cannot be read still counts from the last time it was read. So a download server that goes off still stops the offer at most an hour later, but on an instance with a half-hour answer that read may be up to half an hour old, and the offer can stop after half an hour instead.

## Alternatives

- **Mark a release as published in releases.json** with a second deploy after the APK is copied. No network read on the server, but every release takes two deploys, and a forgotten second one leaves the release unannounced.
- **Keep the APK where the server is**, in the repository or at the hosting provider. The repository is public and the APK would be a 3 MB binary in every clone; the hosting plan has no file storage for it.
- **Let the app check the download itself** (a HEAD request, or the file's size). That needs a new app version, so it would help no app that is installed now, which is the point of the change.
- **A cache shared by the server instances** (Next.js's data cache for the `fetch`, or a store at the hosting provider). One reading a minute for everyone, but how it behaves on Vercel cannot be tried locally, and a shared stale answer would delay a published APK for every instance at once.
