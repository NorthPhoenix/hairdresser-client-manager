# Store 5 is the Android app's read cache

The Android app reads the server through an in-memory cache, and the cache is a library: Store 5 of the Mobile Native Foundation (`org.mobilenativefoundation.store:store5`, version 5.1.0-beta01). What was read before is shown at once and asked again behind it. A loading state is only for what was never read.

Before this, the app kept each list it had loaded in the view model and loaded it again whenever the question changed. Going from one Calendar day to the next cleared the list and showed a loading state every time, also for a day that had been on screen a moment ago.

## A library, and this one

A read cache looks small and is not: one request shared by everyone who asks for the same thing, answers that expire, a size limit, and what to do with an answer that arrives after the question has changed. We did not want to write and maintain that ourselves.

The model we wanted is TanStack Query: a read is named by a key, a key that was read before is served from the cache while it is fetched again, and a change says which keys it has made out of date. For Kotlin, Soil Query is the closest in shape, but at the time of the decision it had about 190 stars, no stable release, and no release at all since October 2025. Store 5 had about 3.4k stars and regular releases. Its model is a fetcher per key with a memory cache in front, which is what we need, and it leaves the part that is specific to this app (what a change makes of the cached answers) to us.

5.1.0-beta01 (2026-09-08) is the newest version and builds with this project's Kotlin (2.4.20). 5.0.0 is the last version without a pre-release label and is the one to fall back to if a later beta does not build.

## Memory only

The cache has no source of truth on disk, and must not get one: ADR 0009 says the device is not where domain data lives. Each Store is built from a fetcher and a memory policy and nothing else. The cache is emptied when the Stylist signs out and when a different Stylist signs in, and an answer that was still on its way for the Stylist before is neither shown nor kept.

There is no second cache beside it. The view model no longer keeps lists of loaded data. What it exposes to a screen is a view of what a read of the Store shows at this moment, and it is gone when nothing reads that key any more.

## How a read behaves

- A screen watches a key. A key with a cached answer shows that answer at once, with no loading state, and is asked again behind it. Only a key with nothing cached is loading.
- When the request behind a cached answer fails, the answer stays on screen and nothing is said. A key with no answer shows the failure and offers a retry.
- Pull to refresh, 'Try again' and the reload after a change ask again explicitly. Their failure goes to whoever asked, who reports it. Pull to refresh keeps its own indicator.
- Everyone who asks for a key while a request for it is on its way shares that request.
- Several keys stay cached, so going back to a Calendar day shows it at once.

## What a change makes of the cache

The app still asks the server again after every change, for what is on screen. Two more things happen, because the reload can fail and because most of the cache is not on screen.

What is on screen is corrected at once with what the server has just confirmed: the new status, the saved Client's phone number, the Appointment that was deleted. This is the same as before the cache.

Every cached answer that the change may have made wrong is never served again. The app counts the changes the server has confirmed, and every answer carries the count at the time its request was sent, together with the ids of the Appointments and Clients it holds. A change names what it touched. An answer that holds one of those, and was asked for before the change, is out of date: Store's validator treats it as not cached, and an answer that arrives after the change is dropped and asked for again. So a Calendar day that is not on screen is loaded anew the next time it is shown if it held the changed Appointment or Client, and stays cached if it did not. A saved Client's old phone number cannot come back from the cache, on whatever day the Appointment is.

Some changes can show in an answer that does not hold them yet: a new Appointment, another time for one, the salon's address. They put every cached answer out of date. A change whose reply never came is treated as if it had been saved.

We mark answers out of date instead of rewriting them. A memory-only Store has no way to put a value into its cache except as the result of a fetch, and it does not tell a reader about anything but fetches. Its `MutableStore` writes through an updater that posts whole values to the server, which is not how this API changes things, and it is marked experimental. An in-memory source of truth would be the hand-made cache this decision is meant to remove.

## Keys and limits

A key is a small data class: `DayKey(day, zone)` for Today's list (`appointment.home`) and for a Calendar day (`appointment.list`), each in a Store of its own, and `AppointmentKey(id)` for one Appointment (`appointment.get`). The timezone is part of a day's key, because the day's start and end are worked out in it: after a timezone change the lists are other keys and are loaded.

| Read | Answers kept | Kept for |
| --- | --- | --- |
| Today's list | 4 | 30 minutes |
| Calendar days | 62 | 30 minutes |
| One Appointment | 100 | 30 minutes |

The age counts from the answer. Every key that is shown is asked again anyway, so the age does not decide how fresh the screen is. It limits how old an answer may be when it is shown at once, and for how long a refresh that keeps failing can leave it there.

## Consequences

- The remaining reads (Clients, a Client's Appointment history, the Service Menu, the sources of Appointment Copying, the Stylist's settings) move onto the same cache, each with its key.
- Right after a change, a cached day that held what changed shows a loading state once. This is deliberate: the alternative is to show something known to be possibly wrong.
- A screen opened for something that is cached but on no other screen draws one empty frame before the cached answer, because Store answers through a flow.
- The library is a beta. The app uses a small part of it (`StoreBuilder.from`, `MemoryPolicy`, `Validator`, `stream` with a cached or a fresh request, `clear`), all behind one class, `ReadCache`. `clear()` for all keys is marked experimental in Store.
