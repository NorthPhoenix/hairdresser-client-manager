# Repeated and out-of-date saves

A save can reach the server more than once, and it can come from a form that no longer shows what is stored. The API handles both without a sync protocol, because Offline Editing is not a v1 concept: the app is online, but a reply can be lost and a Stylist can be signed in on two devices.

## A creating request that is sent again

When the reply to a creating mutation is lost, the app cannot tell whether the record was created, and the Stylist sends the request again. Every mutation that creates a record therefore takes an optional `requestKey`, a random string chosen by the caller once per form or sheet (a UUID does). The key is stored on the records the call creates, in a nullable `requestKey` column. A call whose key is already on a record of the same Stylist and the same parent creates nothing and answers with the current state of what the first call created. This covers creating a Client Profile, a Service Menu Item, an Appointment with its inline Client, a Service, a Color Formula and an Appointment Photo, and Appointment Copying, where every copied Service carries the key.

A key that comes again with other values is refused with `CONFLICT`, and nothing is written. That is the form the Stylist corrected after a lost reply: the first call was carried out with the earlier values, and answering it like the first call would report the correction as saved while dropping it. The app reloads and shows the saved record, and the correction becomes an edit of it. A new key for the corrected form would be the duplicate again, so the server has to be the one that notices. It compares what the request states with the record the key is on, as it is now. A fingerprint of the first request stored next to the key would compare with what was sent instead; it needs a second column on six tables, and comparing with the record needs none. The cost is that an unchanged request sent again after the record was edited elsewhere is refused too, which errs on the side of showing the Stylist what is stored. Appointment Copying is not compared, because the copies do not record their source; its reply lists the copied Services. For an Appointment Photo the stored file is not compared, because a picture that is uploaded again for the same key is a new file each time.

Two repeats are recognised without a key. Adding an Appointment Photo for a stored file that the Appointment already has a photo of adds nothing. That covers sending the add step again, not uploading the picture again, which makes a new file. Creating a Profile Share answers with the active one, because a Client has at most one.

One stored file is one Appointment Photo, across all Stylists: a file that a photo of another Appointment has is refused. Deleting a photo may therefore delete its file. The check is a search under the Stylist lock and not a unique constraint, for the reason given below, so it does not hold against two Stylists adding the same file at the same moment; that takes a file key which only its uploader has been sent.

These mutations run under the Stylist lock of ADR 0014, so the second of two identical requests that arrive together waits and then finds what the first created. That is why the columns carry no unique constraint: the lock already makes the check and the write one step, and a unique constraint on existing tables cannot be added with a plain schema push (ADR 0007).

We chose a key on the created records over a table of handled requests with their stored replies. Stored replies would be stale copies of Client data that outlive the records, would need a clean-up job, and would answer with the past instead of the present. A key on the record disappears with the record: when the Stylist deletes what the first call created and sends the request again, it is created again, which is what was asked for. We chose a key over caller-generated record ids because Appointment Copying creates several records at once.

A key names one intended creation. The caller keeps it for as long as it retries that creation and takes a new one for the next, also when the next one has the same content. A caller that forgets to take a new one is told so by the refusal above instead of losing the second creation.

## A save from an out-of-date form

Saving a Client Profile or the Stylist's settings writes every field of the form. A form that was loaded before another device saved would silently put the old values back. Both saves therefore take an optional `expectedUpdatedAt`: the `updatedAt` of the record the form was loaded from. When the stored record has another `updatedAt` and the save would change it, the save is refused with `CONFLICT` and nothing is written; the app reloads and the Stylist decides. A save that would change nothing passes whatever version it names, so the same save sent again after a lost reply is not reported as a conflict with itself.

`updatedAt` serves as the version because every write of these two records goes through one mutation each, under the Stylist lock, and the lazy Stylist upsert of ADR 0011 writes nothing for an existing Stylist. A separate version counter would say the same thing.

Both parameters are optional. A caller that sends neither gets the behaviour from before: every creating call creates, and the last save wins.
