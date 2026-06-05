# One-way Calendar Integration

In v1, Calendar Integration mirrors Appointments from the app to Google Calendar, and the app remains the source of truth. We are not importing arbitrary Google Calendar events or reconciling edits made directly in Google Calendar, which avoids sync conflicts while still giving Stylists calendar visibility.

When an Appointment has no end time, the mirrored Google Calendar event uses a default 60-minute duration. The app Appointment remains start-time-only unless the Stylist sets an end time.

Mirrored Google Calendar events use the Stylist's timezone.

Completing or marking an Appointment as a no-show does not change the mirrored Google Calendar event. Canceling or deleting an Appointment removes the mirrored Google Calendar event.

Group Appointments mirror as one Google Calendar event for the Appointment, not one event per Client.

For scheduled Appointments, changing the primary Client updates future reminder behavior and the mirrored Google Calendar event details that depend on the primary Client.

Calendar Integration does not send Google Calendar invites to Clients in v1.

Mirrored Google Calendar events include the Appointment location when available.

Mirrored Google Calendar events exclude Color Formulas, internal notes, prices, and Appointment Photos.

Calendar sync failures are non-blocking: the Appointment remains saved in the app, and the Stylist can see a simple sync warning or retry path.
