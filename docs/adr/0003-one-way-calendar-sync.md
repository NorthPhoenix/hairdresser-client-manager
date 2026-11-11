# One-way Calendar Integration through the phone's calendar

Calendar Integration mirrors Appointments from the app to one of the Stylist's Google calendars, and the app remains the source of truth. We are not importing arbitrary Google Calendar events or reconciling edits made directly in Google Calendar, which avoids sync conflicts while still giving Stylists calendar visibility.

## The phone writes the events, not the server

The Android app writes the mirrored events into the phone's own calendar store (`CalendarContract`), in a Google calendar that is on the phone, and Android's Google account sync carries them to Google Calendar everywhere else. The server is not involved: it keeps no Google tokens and runs no background jobs, and there is no Google OAuth redirect to the web app.

We chose this over the Google Calendar API from the server because the app has one Stylist client, the Android app (ADR 0013), and one Stylist on one phone. The server route needs a Google OAuth consent screen that Google verifies for calendar scopes, refresh tokens that an unverified app in testing loses every seven days, encrypted token storage, and a job runner the hosting plan does not have. Writing on the phone needs only the calendar permission.

## A calendar of its own

By default the events go into a calendar of their own, named "Клиенты" (or "Clients" in English), so the Stylist can show, hide or color all of them at once in Google Calendar. Android cannot create a Google calendar through `CalendarContract`, so the app creates it once through the Google Calendar API with the `calendar.app.created` scope. It gets that access on the phone with Google's Android authorization API (Google Play services, no server), creates the calendar, and does not keep the access: the events go through `CalendarContract`. That needs the Google Cloud setup in `docs/setup.md`. Without it, or when it fails, the Stylist creates the calendar on calendar.google.com and the app finds it on the phone by its name. She can also pick any other Google calendar on the phone she may write to.

## Mirroring is a comparison, not a hook per change

What an event shows depends on many changes besides those of its Appointment: a Client's new name or address, a Client Merge, a Client deleted from a group Appointment, a new salon address. Instead of following each of them, the app compares: it builds the events the Appointments should have now (from `appointment.list`), compares them with the events it wrote, and creates, changes or deletes what differs. It does this after every change the Stylist makes in the app, and when the app is opened.

To know its events the app keeps, on the phone, which event it wrote for which Appointment and what it wrote then. It also tags each event with the Appointment (`CUSTOM_APP_URI`). An event whose Appointment is not there any more, or is canceled, is deleted. An event that was deleted in Google Calendar is written again. One that was edited there is replaced the next time its Appointment changes. That list is device-local bookkeeping about the copy, not domain data (ADR 0009).

## What is mirrored

The Appointments that start after the Calendar Integration was connected are mirrored; none from before it. Canceled Appointments are not.

When an Appointment has no end time, the mirrored event uses a default 60-minute duration. The app Appointment remains start-time-only unless the Stylist sets an end time.

Mirrored events use the Stylist's timezone.

Completing or marking an Appointment as a no-show does not change the mirrored event. Canceling or deleting an Appointment removes the mirrored event. Setting a canceled Appointment back to scheduled mirrors it again.

Group Appointments mirror as one event for the Appointment, not one event per Client.

The event is named after the primary Client, with the number of other Clients in a group Appointment. Changing the primary Client changes the event details that depend on the primary Client.

Calendar Integration does not send Google Calendar invites to Clients. The events have no notifications of their own; the app has Stylist Reminders for that.

Mirrored events include the Appointment location when available.

Mirrored events include the names of the Services, the other Clients of a group Appointment, and the primary Client's phone. They exclude Color Formulas, notes, prices, Tips and Appointment Photos.

Calendar sync failures are non-blocking: the Appointment remains saved in the app. When the phone cannot mirror for a reason the Stylist can fix (calendar access was taken away, the calendar is gone from the phone), Home and the Calendar Integration screen say what to do. A failure that passes on its own, such as no connection to the server, is retried on the next change or opening.

Disconnecting stops the mirroring and leaves the events that were mirrored in the calendar.

## Consequences

The mirror is only as current as the phone: a change saved elsewhere reaches Google Calendar once this phone mirrors again. The connection belongs to the phone and to the Stylist signed in on it; signing out pauses it and signing in as the same Stylist resumes it.

On a new phone the app knows only the events it can find by their tag. If Google's sync does not keep the tag, the events the old phone wrote stay in the calendar beside the ones the new phone writes.
