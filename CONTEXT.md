# Hairdresser Client Manager

This context describes the client-management domain for independent hair and beauty professionals managing client records, appointments, service history, photos, reminders, and shared client information.

## Language

**Stylist**:
The solo professional who uses the app to manage clients, appointments, visit history, photos, reminders, and shared client records. A Stylist can have one optional salon or business address and has a configurable timezone that defaults from the device timezone.
_Avoid_: Hair dresser, hairdresser, provider, user

**Stylist Account Deletion**:
Not a v1 concept. Removing a Stylist account and all stylist-owned data is deferred beyond v1.
_Avoid_: Account closure, data purge

**Stylist Display Name**:
Not a v1 concept. Client-facing Stylist identity uses the Stylist account identity rather than a separate app-specific display name.
_Avoid_: Business name, public name

**Stylist Availability**:
Not a v1 concept. The app does not manage business hours, availability rules, or booking constraints.
_Avoid_: Business hours, schedule rules, availability calendar

**Stylist Onboarding**:
A minimal first-use setup where the Stylist confirms timezone and can optionally add a salon or business address. Google Calendar, Stylist Reminders, and Service Menu Items are optional after onboarding.
_Avoid_: Setup wizard, account setup, profile setup

**Home View**:
The Stylist's default working view, focused on unresolved past Appointments and today's Appointments, with quick access to create Clients and Appointments.
_Avoid_: Dashboard, landing page, overview

**Clients View**:
The Stylist's list and search view for finding, creating, importing, and opening Client Profiles.
_Avoid_: Contacts view, customer list, address book

**Client**:
A person whose relationship, contact details, appointments, visit history, notes, photos, and shared records are managed by exactly one Stylist. In v1, a Client has one display name, has an editable language that defaults from the Stylist's current language at creation, and may have one primary phone number, one email address, and one address.
_Avoid_: Customer, guest, contact

**Appointment**:
A scheduled or completed session between a Stylist and one or more Clients, with one primary Client and optional additional Clients. An Appointment has a start time in the Stylist's timezone, can optionally have an end time, scheduled Appointments produce conflict warnings using a 30-minute minimum duration when no end time is set, past scheduled Appointments remain unresolved until the Stylist marks an outcome, can have zero or more Services, can be completed with zero Services, outcome states remain editable, can have one appointment-level note, defaults to an in-salon location, can be changed to an at-home location using the primary Client address or a custom address, can be scheduled, explicitly completed, canceled, or marked as a no-show, its final total defaults to the sum of Service prices and can be overridden by the Stylist, each Client's subtotal is derived from that Client's Services, the primary Client can be changed to another Client attached to the Appointment, a Client can be removed from the Appointment only when no Services or Appointment Photos are attached to that Client in the Appointment, and deletion removes its Services, Color Formulas, and Appointment Photos.
_Avoid_: Visit, session, booking

**Map Opening**:
A lightweight action that opens an Appointment address in Google Maps. Full route planning, geocoding, and map views are not v1 concepts.
_Avoid_: Navigation integration, route planning, map view

**Appointment Creation**:
The flow for creating a new Appointment. In v1, Appointment Creation supports creating a minimal Client inline and adding group Clients before saving.
_Avoid_: Booking flow, scheduling flow

**Recurring Appointment**:
Not a v1 concept. The app does not manage recurring appointment rules, skipped occurrences, or recurrence exceptions.
_Avoid_: Repeat booking, recurring series

**Appointment Copying**:
A convenience action that copies Services and Color Formulas, but not Appointment Photos, from any completed Appointment into another Appointment, defaulting to the last completed Appointment. For group Appointments, copying only applies to Clients already attached to both Appointments, and copied details can be edited for the new Appointment.
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
A type of work performed for exactly one Client during an Appointment, such as a haircut, root touch-up, toner, balayage, blowout, or extensions. A Service records the actual price for that Appointment, can have one service-level note, and color details belong to the Service they describe when applicable.
_Avoid_: Work item, treatment, procedure

**Service Menu Item**:
A reusable service option configured by a Stylist with a default name and price. A Service can start from a Service Menu Item or be added ad hoc, appointment history stores the Service as it actually happened, and deleting a Service Menu Item does not change existing Services.
_Avoid_: Template service, catalog item

**Color Formula**:
The formula text and optional placement used for a Service. A Service can have multiple Color Formulas.
_Avoid_: Dye, recipe, mix

**Color Formula Library**:
Not a v1 concept. Color Formulas live on Services, and reuse happens through Appointment Copying rather than a reusable formula catalog.
_Avoid_: Formula template, formula catalog, saved formula

**Appointment Photo**:
A photo stored by the app, associated with an Appointment, and categorized as before, after, or other. Appointment Photos can be captured with the camera or selected from the gallery, can be attached to a specific Client in the Appointment, show upload failure state with retry or remove options, an Appointment can have multiple Appointment Photos in each category, an Appointment Photo category can be changed, and an Appointment Photo can be deleted independently.
_Avoid_: Image, picture, reference photo

**Photo Editing**:
Not a v1 concept. The app does not provide cropping, filters, markup, or retouching tools for Appointment Photos.
_Avoid_: Image editing, crop tool, filters

**Client Note**:
A single persistent note about a Client that remains useful beyond a single Appointment, such as preferences, sensitivities, allergies, or relationship context.
_Avoid_: General note, profile note, memo

**Client Profile**:
The complete record for one Client, including contact details, address, Client Notes, Appointments, Services, Color Formulas, Appointment Photos, and shareable history. A Client Profile belongs to exactly one Stylist, is searchable by Client name and phone number in v1, warns on duplicate phone numbers without blocking them, shows only that Client's Services and relevant Appointment Photos from group Appointments, and deletion removes that Client's data from group Appointments while keeping the Appointment when other Clients remain and promoting another remaining Client to primary when needed.
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
An in-app way for the Stylist to browse Appointments by date using simple calendar navigation and agenda lists. Drag-and-drop calendar editing is not a v1 concept.
_Avoid_: Scheduler, calendar editor, booking calendar

**Stylist Reminder**:
A notification automatically shown to the Stylist before a scheduled Appointment. In v1, Stylist Reminders are off by default, use the Stylist's selected default offsets when enabled, support 15 minutes, 1 hour, 2 hours, 1 day, and 2 days before the Appointment, skip offsets that are already in the past, are canceled when an Appointment leaves scheduled state, and include client, time, and location details without private notes or formulas.
_Avoid_: Push reminder, internal reminder, alert

**Client Reminder**:
A Stylist-initiated SMS reminder message before an Appointment. In v1, the app composes a fixed default message in the recipient Client's language with Appointment time and location details, sends group Appointment reminders to the primary Client, requires the reminder recipient to have a phone number, and does not track reminder status.
_Avoid_: Client notification, appointment text, confirmation message

**Profile Share**:
A revocable Stylist-selected subset of a Client Profile intended for the Client to view. In v1, each Client has at most one active Profile Share, Profile Shares default to the target Client's language and can be toggled between supported languages on the share page, do not require Client contact details, revoked Profile Share links are not reused, Profile Shares do not expire by default, and Profile Shares expose all future Appointments and the last completed Appointment by default through unguessable links or share images; group Appointments are scoped to the target Client and do not show other Clients by default; future Appointments show scheduling and location details only, while completed Appointment details can include Color Formulas and successfully stored after photos by default with the option to exclude them, can include successfully stored before or other photos when selected, show the Stylist name and optional business address, exclude Client contact details except appointment location when relevant, exclude Stylist personal contact details, prices, canceled and no-show Appointments, and internal notes, and do not require a Client Account or passcode.
_Avoid_: Shared profile, export, client portal

**Profile Share Analytics**:
Not a v1 concept. The app does not track whether or when a Client views a Profile Share.
_Avoid_: View tracking, read receipt, share analytics

**Consent Tracking**:
Not a v1 concept. The app does not record consent events, signatures, or permissions beyond the Stylist deliberately creating or revoking Profile Shares.
_Avoid_: Release form, consent record, signed waiver

**Share Image**:
A static image generated on demand from selected Client Profile information for sharing outside the app. A Share Image language is chosen at generation time, defaults to the Client's language, and is not revocable after it leaves the app.
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
