# Hairdresser Client Manager

This context describes the client-management domain for independent hair and beauty professionals managing client records, appointments, service history, photos, reminders, and shared client information.

## Language

**Stylist**:
The solo professional who uses the app to manage clients, appointments, visit history, photos, reminders, and shared client records. A Stylist can have one optional salon or business address and has a configurable timezone, always a named IANA timezone, that defaults from the device timezone.
_Avoid_: Hair dresser, hairdresser, provider, user

**Stylist Account Deletion**:
Not a v1 concept. Removing a Stylist account and all stylist-owned data is deferred beyond v1.
_Avoid_: Account closure, data purge

**Stylist Display Name**:
Not a v1 concept. Client-facing Stylist identity uses the Stylist account identity rather than a separate app-specific display name: the first and last name on the Stylist's sign-in account, when the account has them. The account's email address is a personal contact detail and never stands in for the name; an account without a name shows none.
_Avoid_: Business name, public name

**Stylist Availability**:
Not a v1 concept. The app does not manage business hours, availability rules, or booking constraints.
_Avoid_: Business hours, schedule rules, availability calendar

**Stylist Onboarding**:
A minimal first-use setup where the Stylist confirms timezone and can optionally add a salon or business address. Google Calendar, Stylist Reminders, and Service Menu Items are optional after onboarding.
_Avoid_: Setup wizard, account setup, profile setup

**Home View**:
The Stylist's default working view, focused on unresolved past Appointments and today's Appointments, with quick access to create Clients and Appointments. A scheduled Appointment on it whose start time has passed (every unresolved one, and one of today's once it has started) can be marked completed, canceled, or no-show straight from its card, without opening it; a day with two or more unresolved Appointments can have all of them marked completed at once. Right after either, the Stylist can undo it, which sets those Appointments back to scheduled by the usual rules, conflict warnings included, and leaves alone one whose outcome was changed elsewhere in the meantime.
_Avoid_: Dashboard, landing page, overview

**Clients View**:
The Stylist's list and search view for finding, creating, importing, and opening Client Profiles.
_Avoid_: Contacts view, customer list, address book

**Client**:
A person whose relationship, contact details, appointments, visit history, notes, photos, and shared records are managed by exactly one Stylist. In v1, a Client has one display name on a single line with at least one visible character, has an editable language that defaults from the Stylist's current language at creation, and may have one primary phone number, one email address in the form of an email address, and one address.
_Avoid_: Customer, guest, contact

**Appointment**:
A scheduled or completed session between a Stylist and one or more Clients, with one primary Client and optional additional Clients. An Appointment has a start time in the Stylist's timezone, can optionally have an end time, start time, end time, and location stay editable after creation, scheduled Appointments produce conflict warnings that name the overlapping Appointments when an Appointment is created, moved, or set back to scheduled, using a 30-minute minimum duration when no end time is set, past scheduled Appointments remain unresolved until the Stylist marks an outcome, can have zero or more Services, can be completed with zero Services, outcome states remain editable, can have one appointment-level note, defaults to an in-salon location that shows the Stylist's salon address and follows changes to it while the Appointment is scheduled, can be changed to an at-home location using the primary Client address or a custom address, an at-home Appointment without a custom address takes the address of the new primary Client whenever its primary Client changes so a former primary Client's address never stays on it, can be scheduled, explicitly completed, canceled, or marked as a no-show, its final total defaults to the sum of Service prices and can be overridden by the Stylist, neither the sum of its Service prices nor an overridden total exceeds 9,999,999.99, each Client's subtotal is derived from that Client's Services, the primary Client can be changed to another Client attached to the Appointment and is always one of its Clients, Clients are listed with the primary Client first and then in the order they were added, a Client can be removed from the Appointment only when no Services or Appointment Photos are attached to that Client in the Appointment, and deletion removes its Services, Color Formulas, and Appointment Photos. Services are added for one of the Appointment's Clients at a time, picked as the one they are for; a Client can be added to the Appointment right there, an existing one or a minimal one made from just a name as in Appointment Creation, and is then the one picked. An Appointment with several Clients shows its Services under each Client, with what that Client's Services come to.
_Avoid_: Visit, session, booking

**Map Opening**:
A lightweight action that opens an Appointment address in Google Maps. Full route planning, geocoding, and map views are not v1 concepts.
_Avoid_: Navigation integration, route planning, map view

**Appointment Creation**:
The flow for creating a new Appointment. In v1, Appointment Creation supports creating a minimal Client inline and adding group Clients before saving. Booking a Client again from their Client Profile starts it with that Client as the primary Client, at the place of their last visit: in the salon, at the address the Stylist typed for it, at the address the Client has now when it was at their own, or at the address of the other Client it was at. It copies no Services: Appointment Copying does that from the new Appointment.
_Avoid_: Booking flow, scheduling flow

**Recurring Appointment**:
Not a v1 concept. The app does not manage recurring appointment rules, skipped occurrences, or recurrence exceptions.
_Avoid_: Repeat booking, recurring series

**Appointment Copying**:
A convenience action that copies Services and Color Formulas, but not Appointment Photos, from any completed Appointment into another Appointment. Copying only applies to Clients already attached to both Appointments, so a completed Appointment can be copied from only when one of those Clients has Services in it, and the default is the last completed Appointment that can be copied from. Copied Services and Color Formulas keep the order they have in the source, and copied details can be edited for the new Appointment.
_Avoid_: Recurrence, repeat appointment, template appointment

**Payment Tracking**:
Not a v1 concept. The app records prices and appointment totals, but does not track paid status, deposits, tips, discounts, taxes, payment methods, invoices, or balances.
_Avoid_: Checkout, invoicing, balance tracking

**Stylist Billing**:
Not a v1 concept. The app does not manage subscriptions, trials, paywalls, or stylist payment plans.
_Avoid_: Subscription, monetization, paywall

**Care Instructions**:
Not a v1 concept. The app does not manage a dedicated client-facing aftercare note.
_Avoid_: Aftercare note, care note, instructions

**Service**:
A type of work performed for exactly one Client during an Appointment, such as a haircut, root touch-up, toner, balayage, blowout, or extensions. A Service has a one-line name, records the actual price for that Appointment up to 9,999,999.99, can have one service-level note, its name, price, and note stay editable after it is added and an edit keeps its Color Formulas, and color details belong to the Service they describe when applicable.
_Avoid_: Work item, treatment, procedure

**Service Menu Item**:
A reusable service option configured by a Stylist with a default one-line name, a default price up to 9,999,999.99, and an optional one-line group, such as "Покраска в один цвет", whose items are its variants (by hair length or volume). Two groups are the same group when their names differ only in letter case, "ё" and "е", or repeated spaces. The Service Menu lists its items in the order they were created, oldest first, in their groups, each group where its oldest item is, and the items without a group after the groups; editing an item does not move it. Inside its group an item goes by its name without the group's name at its start. A Service can start from a Service Menu Item or be added ad hoc, several Services can be started from Service Menu Items at once for one Client, each at its default price and in the order picked, appointment history stores the Service as it actually happened, and deleting a Service Menu Item does not change existing Services. The items a Stylist uses most are the ones the most Services were started from.
_Avoid_: Template service, catalog item, category

**Travel Fee**:
The one Service Menu Item a Stylist may mark as the fee for going to a Client, such as "Выезд на дом к клиенту"; marking one unmarks any other. When a Scheduled Appointment is created at home or changes from in salon to at home, and it has no Service started from the Travel Fee, a Service from it is added at its default price for the primary Client, in the same save, unless that would take the Services total past the largest amount; when a Scheduled Appointment changes from at home to in salon, the Services started from the Travel Fee are removed. Only these changes of place add or remove it, so a Travel Fee the Stylist removed from an at-home Appointment stays removed until the Appointment changes to at home again, and a change of primary Client adds no second one. The app says what was added or removed and offers to undo it. Completed, canceled, and no-show Appointments are never changed by it, a Travel Fee Service is a Service like any other once it is added, and unmarking or deleting the Travel Fee changes no existing Service. The Travel Fee is not one of the items a Stylist uses most.
_Avoid_: Travel charge, house-call fee, mileage

**Color Formula**:
The formula text and optional placement used for a Service. A Service can have multiple Color Formulas. The formula text can have several lines; Profile Share pages and Share Images keep its line breaks.
_Avoid_: Dye, recipe, mix

**Color Formula Library**:
Not a v1 concept. Color Formulas live on Services, and reuse happens through Appointment Copying rather than a reusable formula catalog.
_Avoid_: Formula template, formula catalog, saved formula

**Appointment Photo**:
A photo stored by the app in its own photo storage and nowhere else, associated with an Appointment, and categorized as before, after, or other. Appointment Photos can be captured with the camera or selected from the gallery, can be attached to a specific Client in the Appointment, show upload failure state with retry or remove options, an Appointment can have multiple Appointment Photos in each category, an Appointment Photo category can be changed, and an Appointment Photo can be deleted independently. Deleting an Appointment Photo, or the Appointment or Client it belongs to, also deletes its file from photo storage. Photo storage takes JPEG files only, which is what the app makes of every picture, and a failed upload is a state without a stored reason: the app words it in the Stylist's language. A server without photo storage says so, and the app then offers no upload and says why. A picture that is not uploaded yet waits on the Stylist's device; signing out deletes it together with every other copy of a Client's pictures the app kept on that device, after asking the Stylist when photos are still waiting.
_Avoid_: Image, picture, reference photo

**Photo Editing**:
Not a v1 concept. The app does not provide cropping, filters, markup, or retouching tools for Appointment Photos.
_Avoid_: Image editing, crop tool, filters

**Client Note**:
A single persistent note about a Client that remains useful beyond a single Appointment, such as preferences, sensitivities, allergies, or relationship context.
_Avoid_: General note, profile note, memo

**Client Profile**:
The complete record for one Client, including contact details, address, Client Notes, Appointments, Services, Color Formulas, Appointment Photos, and shareable history. A Client Profile belongs to exactly one Stylist, is searchable by Client name and phone number in v1, warns on duplicate phone numbers without blocking them, shows only that Client's Services and relevant Appointment Photos from group Appointments, and deletion removes that Client's data from group Appointments while keeping the Appointment when other Clients remain and promoting another remaining Client to primary when needed. A Client Profile opens for reading, with its editing one step away; it says how many visits the Client has had, when the last one was, how often the Client usually comes once there are two visit days, and the next Scheduled Appointment, where a visit is a completed Appointment and canceled, no-show and Scheduled Appointments are not visits. From it the Stylist can call, text, or open the Client's address in a map app, and book the Client again.
_Avoid_: Client file, customer profile, record

**Client Account**:
Not a v1 concept. Clients are represented by Client Profiles and do not authenticate, edit records, or manage appointments themselves.
_Avoid_: Client login, customer account, portal user

**Client Self-Booking**:
Not a v1 concept. Clients cannot request, create, reschedule, or cancel Appointments through Profile Shares.
_Avoid_: Online booking, booking request, reschedule request

**Birthday Tracking**:
Not a v1 concept. Birthday details can be written in the Client Note when needed, but the Client Profile does not have a dedicated birthday field.
_Avoid_: Birthday reminders, birthday field

**Client Archiving**:
Not a v1 concept. Client Profiles can be deleted rather than archived.
_Avoid_: Hide client, inactive client

**Contact Import**:
A one-time action that copies details from a selected device contact into a new or existing Client Profile with Stylist confirmation. In v1, Contact Import does not continuously sync with device contacts.
_Avoid_: Contact sync, address book sync, bulk import

**Client Relationship**:
Not a v1 concept. Children, dependents, family members, and group members are separate Clients without modeled relationships to each other.
_Avoid_: Household, dependent, family relationship

**Calendar Integration**:
An optional connection that lets a Stylist mirror Appointments from the app to an external calendar. Appointments remain part of the app even when no Calendar Integration is connected.
_Avoid_: Calendar source, imported calendar, external schedule

**Calendar View**:
An in-app way for the Stylist to browse Appointments by date using simple calendar navigation and agenda lists. Its week strip marks each day that has an Appointment that is not canceled. Drag-and-drop calendar editing is not a v1 concept.
_Avoid_: Scheduler, calendar editor, booking calendar

**Stylist Reminder**:
A notification automatically shown to the Stylist before a scheduled Appointment. In v1, Stylist Reminders are off by default, use the Stylist's selected default offsets when enabled, support 15 minutes, 1 hour, 2 hours, 1 day, and 2 days before the Appointment, skip offsets that are already in the past, are canceled when an Appointment leaves scheduled state, and include client, time, and location details without private notes or formulas.
_Avoid_: Push reminder, internal reminder, alert

**Client Reminder**:
A Stylist-initiated SMS reminder message before an Appointment, offered for a scheduled Appointment until its start time. In v1, the app composes a fixed default message in the recipient Client's language with Appointment time and location details, sends group Appointment reminders to the primary Client, requires the reminder recipient to have a phone number, and does not track reminder status.
_Avoid_: Client notification, appointment text, confirmation message

**Profile Share**:
A revocable Stylist-selected subset of a Client Profile intended for the Client to view. In v1, each Client has at most one active Profile Share, Profile Shares default to the target Client's language and can be toggled between supported languages on the share page, do not require Client contact details, revoked Profile Share links are not reused and stop showing the page together with its photos (the Client is told, in the Client's language, that the link is no longer available), Profile Shares do not expire by default, and Profile Shares expose all future Appointments and the last completed Appointment by default through unguessable links or share images; group Appointments are scoped to the target Client and do not show other Clients by default; future Appointments show scheduling and location details only, while completed Appointment details can include Color Formulas and successfully stored after photos by default with the option to exclude them, can include successfully stored before or other photos when selected, show every date and time in the Stylist's timezone, show the Stylist name and optional business address, exclude Client contact details except appointment location when relevant, exclude Stylist personal contact details, prices, canceled and no-show Appointments, and internal notes, and do not require a Client Account or passcode. The Stylist makes the three choices (Color Formulas, after photos, before and other photos) when creating a Profile Share and can change them later without changing its link; a Service is still listed by name when its Color Formulas are excluded.
_Avoid_: Shared profile, export, client portal

**Profile Share Analytics**:
Not a v1 concept. The app does not track whether or when a Client views a Profile Share.
_Avoid_: View tracking, read receipt, share analytics

**Consent Tracking**:
Not a v1 concept. The app does not record consent events, signatures, or permissions beyond the Stylist deliberately creating or revoking Profile Shares.
_Avoid_: Release form, consent record, signed waiver

**Share Image**:
A static image generated on demand from selected Client Profile information for sharing outside the app. A Share Image language is chosen at generation time, defaults to the Client's language, and is not revocable after it leaves the app. It shows what the Client's Profile Share page shows, with the same choices of the Stylist, and carries its photos inside itself; where a list or the photos are too many for one picture, or long texts have made it tall, it says how many it leaves out. Text is shown as it was typed and stays inside the picture: a text too long for one line continues on the next, between words where it can, and a Client name with a long word is drawn in smaller letters before the word is cut.
_Avoid_: Profile image, export image, card

**Share Branding**:
Not a v1 concept. Profile Shares and Share Images use the app's standard presentation rather than custom logos, themes, or stylist branding.
_Avoid_: Custom branding, salon logo, branded theme

**Offline Editing**:
Not a v1 concept. The app is online-first and does not support saving or reconciling Client Profile or Appointment changes while offline.
_Avoid_: Offline mode, sync queue, local-first editing

**Audit History**:
Not a v1 concept. The app does not keep a user-facing history of edits or previous values.
_Avoid_: Change log, edit history, version history

**Data Export**:
Not a v1 concept. The app does not export all Stylist or Client Profile data as files or reports.
_Avoid_: CSV export, full export, reports

**Backup and Restore**:
Not a v1 concept. The app does not provide user-facing backup or restore flows.
_Avoid_: Backup, restore, data recovery

**Reporting**:
Not a v1 concept. The app does not provide dashboards, revenue summaries, service trends, or analytics reports.
_Avoid_: Metrics, dashboard, analytics

**Admin Tooling**:
Not a v1 concept. The app does not include a dedicated internal admin or support UI.
_Avoid_: Admin dashboard, support console, back office

**Language Support**:
The app supports Russian as the primary language and English as the secondary language in v1. Stylist language defaults from the device language and can be changed by the Stylist; Client language drives Client Reminders and Profile Shares.
_Avoid_: English-only, localization later

**Inventory Tracking**:
Not a v1 concept. Color Formulas record what was used, but the app does not manage product stock or decrement inventory.
_Avoid_: Stock tracking, product inventory, supplies
