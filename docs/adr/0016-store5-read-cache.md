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

Some changes show in an answer that does not hold them yet. Where the answers they can reach have a name, the change marks that name:

- A new Appointment, or another time for one, joins the list of the day it starts on (`appointment.list` selects by the start alone) and can join Today's list, which holds every overdue Scheduled Appointment. So the answer of a day is also about "the Appointments that start on this day, in this timezone", and Today's about "Today's list". Creating or re-timing an Appointment marks the day it starts on now and Today's list, and for an Appointment the server had, its id, which the day it has left and its own read are about. Every other cached day is still shown at once: booking an Appointment does not make the Calendar load its days again. The day is marked in every timezone a day was read in, so that an answer read before the Stylist's timezone changed is not missed.
- A new Client is in no answer of the Client list, a new Service Menu Item in none of the menu: see below.

A change that no name covers puts every cached answer out of date: the salon's address and the timezone, and a change the server refused because what the screen shows is gone or out of date. A change whose reply never came is treated as if it had been saved.

Two reads repeat so much of other things that no list of ids covers them: a Client's Appointment history (the Services, photos and status of every Appointment the Client is in, also one they are only now added to, and the name of another Client who is primary) and the sources of Appointment Copying (any Completed Appointment with a Service of one of the Clients, also one that is no source yet). Such an answer is about "any change": it is out of date after any change at all that this device made. That is more than strictly needed and cannot be wrong, and it matters most for the copy sources, because a copy acts on the list: the first source is the one a copy takes when it names none. Both stay cached while the Stylist only looks around, which is when showing them at once is worth something.

The Client list and the Service Menu are marked as lists: a new Client is in no cached answer yet, so saving, deleting or sharing a Client marks "the Client list", and saving or deleting a Service Menu Item marks "the Service Menu". So does adding Services from the Service Menu, because the menu says how often each item was used.

We mark answers out of date instead of rewriting them. A memory-only Store has no way to put a value into its cache except as the result of a fetch, and it does not tell a reader about anything but fetches. Its `MutableStore` writes through an updater that posts whole values to the server, which is not how this API changes things, and it is marked experimental. An in-memory source of truth would be the hand-made cache this decision is meant to remove.

## Keys and limits

A key is a small data class with everything the answer depends on, and each read has a Store of its own.

| Read | Key | Answers kept | Kept for |
| --- | --- | --- | --- |
| Today's list (`appointment.home`) | `DayKey(day, zone)` | 4 | 30 minutes |
| A Calendar day (`appointment.list`) | `DayKey(day, zone)` | 62 | 30 minutes |
| One Appointment (`appointment.get`) | `AppointmentKey(id)` | 100 | 30 minutes |
| The sources of Appointment Copying (`appointment.completedSources`) | `CopyTargetKey(appointmentId, clientIds)` | 20 | 30 minutes |
| The Client list (`clientProfile.list`) | `StylistKey(stylistId)` | 1 | 30 minutes |
| A Client's Appointment history (`clientProfile.appointmentHistory`) | `ClientKey(id)` | 30 | 30 minutes |
| The Service Menu (`serviceMenu.list`) | `StylistKey(stylistId)` | 1 | 30 minutes |

The timezone is part of a day's key, because the day's start and end are worked out in it: after a timezone change the lists are other keys and are loaded. The Clients of the Appointment are part of the copy sources' key, because a copy takes only the Services of Clients attached to both Appointments: when they change, the sheet reads another key and shows nothing of the list from before. The Stylist is the key of the two lists that are theirs as a whole, so one Stylist's list is another key than the next one's, on top of the cache being emptied at sign-out.

The Client list and the Service Menu are on screen for as long as a Stylist is signed in, because the pickers and forms of other screens read them. They are loading once, at the start, and from then on every refresh is behind what is shown. One Appointment, a history and a copy sheet are read by their own screen for as long as it is open. That includes the time the app is not in front: behind the camera, the photo picker, the SMS app or a locked phone. The cache keeps nothing of a read that nobody watches, so a screen that stopped reading there would come back with only what the cache has by then, which is nothing once the answer is older than 30 minutes or a change has overtaken it (a photo that finished uploading meanwhile): the Appointment would be loading again, or failed without a connection, and what was typed on its screen would be lost. A read that goes on keeps what it shows, gets every change, and sends no request of its own while the app is away: it asks the server once, when it starts. So these three are collected without regard to the lifecycle (`collectWhileOpen`), unlike every other flow of the app.

The age counts from the answer. Every key that is shown is asked again anyway, so the age does not decide how fresh the screen is. It limits how old an answer may be when it is shown at once, and for how long a refresh that keeps failing can leave it there.

## The Stylist's settings are not a cached read

`stylist.bootstrap` is the one query that does not go through a Store. It is not a read of something the app then shows by key:

- Its first call creates the Stylist, and its answer decides whether there is an app to show at all (sign-in, onboarding, the timezone every other key is worked out in). There is no screen that could show a cached copy before it has answered, and the memory is empty at every start.
- After that, the settings on screen are mostly what a save answered, not what a read did. Saves and fetches of the settings are kept in a strict order (`SettingsRequests`: one save at a time, each built from the reply before it, a fetch dropped when a save overtook it). A memory-only Store can only be filled by its fetcher, so the reply of a save would have to live beside it anyway.
- What the owner asked of a read already holds for them: the settings are shown from memory with no loading state, asked again behind that when the app comes back or is pulled to refresh, and kept when that fails.

So the view model keeps the Stylist of the session as before. It is the session's state, which the keys of the reads are made from, and it is emptied with them at sign-out.

## Consequences

- Right after a change, a cached day that held what changed shows a loading state once, and so do a Client's history and a copy sheet after any change. This is deliberate: the alternative is to show something known to be possibly wrong.
- Store answers through a flow, and a screen's first frame is drawn before a flow has answered. `ReadCache.now(key)` reads what the Store's memory has without asking the server (`StoreReadRequest.localOnly`), so a screen that reads a key for itself has the cached answer in its first frame. It relies on Store answering such a request without suspending; should a later version not do so, the first frame is a loading state and nothing else changes. A unit test fails when that happens.
- The library is a beta. The app uses a small part of it (`StoreBuilder.from`, `MemoryPolicy`, `Validator`, `stream` with a cached, a fresh or a local-only request, `clear`), all behind one class, `ReadCache`. `clear()` for all keys is marked experimental in Store.
