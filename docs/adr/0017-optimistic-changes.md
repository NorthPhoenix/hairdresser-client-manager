# Some changes in the Android app are shown before the server has answered

In the Android app a change the Stylist makes is one of two kinds. Most are waited for: the app sends the request, shows that it is busy, and disables what must not be tapped until the answer is there. Some are shown ahead of the answer (optimistic changes): the screen has the new value, or the item gone, with the tap, nothing is disabled, and the Stylist goes on. When such a change cannot be saved, the screen goes back to what the server has and the failure is reported like any other.

Before this, every change raised one flag, `busy`, and screens disabled most of their controls with it. A status chip showed its new status only after the reply, and the Stylist could not do anything else in the meantime.

## Which changes are shown ahead

A change is shown ahead when both hold: its result is known exactly before the server answers, and nothing on the screen has to wait for the answer.

- An Appointment's status, its note and its final total override.
- The category of an Appointment Photo.
- A choice of a Profile Share that exists.
- A delete the Stylist has confirmed: an Appointment Photo, a Service on an Appointment, a Color Formula, a Service Menu Item.

Everything else is waited for, because the answer decides what happens next: anything saved through a form (the answer can refuse a value, and holds the id of what was created), Edit Appointment (the answer carries the overlap warning), the Clients of an Appointment (deliberately one at a time: each change is offered for what the one before it left), Appointment Copying, deleting an Appointment or a Client (the screen closes on the answer), creating and revoking a Profile Share, a Share Image, photo uploads and sign-in.

## The shown value is an edit laid over the read cache

There is no second copy of the data. The read cache (ADR 0016) keeps what the server answered; a change that is shown ahead is an edit, held by the cache for as long as the change is on its way and applied to every answer it shows, also to one that arrives or is taken from the cache meanwhile. So a refresh that lands while a change is being saved does not bring the old value back, and a screen opened in the meantime shows the change too.

Each read therefore has two values: what is shown, and what the server is known to have. They are the same unless a change is on its way. When the change is saved, the edit becomes what the server has and is taken away; when it fails, it is only taken away. Going back is not a stored "previous value": after two quick taps of which the second fails, the screen shows what the first one saved.

The alternative was a list of pending changes with their previous values, kept beside the cache and undone on failure. It was rejected because an undo is wrong as soon as anything else has changed the data in between, which a reload does all the time.

## One flag for waiting, one for saving

`busy` now means that a change the Stylist waits for is in flight, and is the only thing that disables controls. A second flag, `saving`, is true while any change is in flight and only drives the thin progress line. A change that is shown ahead raises `saving` and never `busy`.

The rules for a change are otherwise the same for both kinds, and are enforced in one place (`ActionRunner`): a request that was sent and never answered is reported as an unknown outcome and reloaded, "no longer exists" reloads everything, a reload that fails stays owed, and a second call with the same key does nothing.

## The server gets the changes in the order of the taps

Because nothing is disabled, the Stylist can make the next change while the last one is still on its way, and the screen offers that next change for what it already shows. Two requests that overtake each other would then be refused for no reason the Stylist can see: a Client removed from an Appointment before the delete of their last Service has arrived, a status sent for an Appointment whose delete came first.

So the requests of changes that are shown ahead go out one at a time, in the order they were made, and a change that is waited for is sent only after the changes shown ahead before it have been answered. A value that can be tapped again (a status chip, a switch) is still ordered per value on top of that: a tap replaces any tap still waiting, and the last tap is what is saved.

## What this costs

- After a request with an unknown outcome the screen goes back to the last value known to be saved until the reload answers. If the change was saved after all, the old value is visible for that moment.
- A failure is reported with the notice the app already uses, which does not name the screen. A Stylist who has moved on sees the notice and the value back, not a way to try again from where they are.
- Text typed into the note or the final total stays in its field when the save fails, for as long as that screen is open. It is not kept if the Stylist has left the screen.
- A slow request holds up the changes made after it, also one that is waited for. Before, it held up the whole screen.
