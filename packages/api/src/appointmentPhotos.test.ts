import { afterEach, describe, expect, it, vi } from "vitest";
import { appRouter } from "./root";
import { withTransaction } from "./testSupport";

const stylist = {
  id: "stylist_1",
  clerkId: "clerk_1",
  language: "en",
  timezone: "America/Chicago",
  salonAddress: "500 Salon Ave",
  onboardingCompletedAt: new Date(),
  createdAt: new Date(),
  updatedAt: new Date()
};

type PhotoRow = {
  id: string;
  appointmentId: string;
  clientId: string;
  fileKey: string | null;
  status?: "pendingUpload" | "stored" | "failed";
  uploadError?: string | null;
};

type PhotoWhere = {
  id?: string;
  clientId?: string;
  fileKey?: string | { in: string[] };
  appointment?: { id?: string };
};

/**
 * Appointment Photos kept in memory, with the calls the photo mutations make. A deleted
 * Appointment or Client takes its photos along, as the database does. `deleteStoredFiles` stands
 * in for photo storage and notes whether the transaction had committed when it was called.
 */
function createPhotoCaller(photos: PhotoRow[], deleteStoredFiles = vi.fn().mockResolvedValue(undefined)) {
  let rows = [...photos];
  let committed = false;
  const committedAtStorageCall: boolean[] = [];
  const matches = (where: PhotoWhere) => (photo: PhotoRow) =>
    (where.id === undefined || photo.id === where.id) &&
    (where.clientId === undefined || photo.clientId === where.clientId) &&
    (where.appointment?.id === undefined || photo.appointmentId === where.appointment.id) &&
    (where.fileKey === undefined ||
      (typeof where.fileKey === "string"
        ? photo.fileKey === where.fileKey
        : photo.fileKey !== null && where.fileKey.in.includes(photo.fileKey)));
  const toOutputRow = (appointmentId: string) => ({
    id: appointmentId,
    stylistId: "stylist_1",
    primaryClientId: "client_1",
    startsAt: new Date("2026-06-13T15:00:00.000Z"),
    endsAt: null,
    status: "scheduled",
    locationType: "inSalon",
    locationAddress: "500 Salon Ave",
    note: "",
    finalTotalCentsOverride: null,
    primaryClient: {
      id: "client_1",
      name: "Anna",
      address: ""
    },
    participants: [
      {
        clientId: "client_1",
        client: {
          id: "client_1",
          name: "Anna",
          address: ""
        }
      }
    ],
    services: [],
    photos: rows.filter(matches({ appointment: { id: appointmentId } })).map((photo) => ({
      category: "after",
      status: "stored",
      provider: "uploadthing",
      url: null,
      thumbnailUrl: null,
      width: null,
      height: null,
      createdAt: new Date("2026-06-13T12:00:00.000Z"),
      ...photo
    }))
  });
  const photoCreate = vi.fn(async ({ data }: { data: PhotoRow }) => {
    rows.push({ ...data, id: `photo_${rows.length + 1}` });
  });
  const photoUpdate = vi.fn();
  const clientDeleteMany = vi.fn(async ({ where }: { where: { id: string } }) => {
    rows = rows.filter((photo) => photo.clientId !== where.id);
  });
  const db = withTransaction({
    stylist: {
      upsert: vi.fn().mockResolvedValue(stylist)
    },
    appointment: {
      findFirst: vi.fn(async ({ where }: { where: { id: string } }) => ({
        id: where.id,
        participants: [
          {
            clientId: "client_1"
          }
        ],
        photos: []
      })),
      findMany: vi.fn().mockResolvedValue([]),
      findUniqueOrThrow: vi.fn(async ({ where }: { where: { id: string } }) => toOutputRow(where.id)),
      deleteMany: vi.fn(async ({ where }: { where: { id: string } }) => {
        rows = rows.filter((photo) => photo.appointmentId !== where.id);
      })
    },
    appointmentPhoto: {
      create: photoCreate,
      update: photoUpdate,
      findFirst: vi.fn(async ({ where }: { where: PhotoWhere }) => rows.find(matches(where)) ?? null),
      findMany: vi.fn(async ({ where }: { where: PhotoWhere }) => rows.filter(matches(where))),
      delete: vi.fn(async ({ where }: { where: { id: string } }) => {
        rows = rows.filter((photo) => photo.id !== where.id);
      })
    },
    client: {
      deleteMany: clientDeleteMany
    }
  });
  const runTransaction = db.$transaction.getMockImplementation() as (...args: unknown[]) => Promise<unknown>;

  db.$transaction.mockImplementation(async (...args: unknown[]) => {
    const result = await runTransaction(...args);

    committed = true;

    return result;
  });

  return {
    photoCreate,
    photoUpdate,
    clientDeleteMany,
    deleteStoredFiles,
    committedAtStorageCall,
    photoIds: () => rows.map((photo) => photo.id),
    caller: appRouter.createCaller({
      clerkAuth: {
        userId: "clerk_1"
      },
      db,
      photoOrigins: [],
      deleteStoredFiles: (fileKeys: string[]) => {
        committedAtStorageCall.push(committed);

        return deleteStoredFiles(fileKeys);
      }
    })
  };
}

const photo = (id: string, fileKey: string | null, appointmentId = "appointment_1", clientId = "client_1"): PhotoRow => ({
  id,
  appointmentId,
  clientId,
  fileKey
});

describe("the stored files of deleted Appointment Photos", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("deletes the file of a removed photo from storage, once the photo is gone for good", async () => {
    const { caller, deleteStoredFiles, committedAtStorageCall, photoIds } = createPhotoCaller([
      photo("photo_1", "file_1"),
      photo("photo_2", "file_2")
    ]);

    await expect(caller.appointment.deletePhoto({ id: "photo_1" })).resolves.toMatchObject({
      id: "appointment_1",
      photos: [
        {
          id: "photo_2"
        }
      ]
    });

    expect(photoIds()).toEqual(["photo_2"]);
    expect(deleteStoredFiles).toHaveBeenCalledTimes(1);
    expect(deleteStoredFiles).toHaveBeenCalledWith(["file_1"]);
    // Not before the commit: a file has to outlive every record that names it.
    expect(committedAtStorageCall).toEqual([true]);
  });

  it("deletes the files of every photo of a deleted Appointment, and of no other Appointment", async () => {
    const { caller, deleteStoredFiles, committedAtStorageCall, photoIds } = createPhotoCaller([
      photo("photo_1", "file_1"),
      photo("photo_2", "file_2", "appointment_1", "client_2"),
      // A photo whose upload failed has no file.
      photo("photo_3", null),
      photo("photo_4", "file_4", "appointment_2")
    ]);

    await expect(caller.appointment.delete({ id: "appointment_1" })).resolves.toEqual({
      id: "appointment_1"
    });

    expect(photoIds()).toEqual(["photo_4"]);
    expect(deleteStoredFiles).toHaveBeenCalledTimes(1);
    expect(deleteStoredFiles).toHaveBeenCalledWith(["file_1", "file_2"]);
    expect(committedAtStorageCall).toEqual([true]);
  });

  it("deletes the files of a deleted Client's photos, in every Appointment, and of no other Client", async () => {
    const { caller, deleteStoredFiles, committedAtStorageCall, photoIds } = createPhotoCaller([
      photo("photo_1", "file_1"),
      photo("photo_2", "file_2", "appointment_2"),
      photo("photo_3", "file_3", "appointment_2", "client_2")
    ]);

    await expect(caller.clientProfile.delete({ id: "client_1" })).resolves.toEqual({
      id: "client_1"
    });

    expect(photoIds()).toEqual(["photo_3"]);
    expect(deleteStoredFiles).toHaveBeenCalledTimes(1);
    expect(deleteStoredFiles).toHaveBeenCalledWith(["file_1", "file_2"]);
    expect(committedAtStorageCall).toEqual([true]);
  });

  it("does not call storage for a photo that has no file", async () => {
    const { caller, deleteStoredFiles } = createPhotoCaller([photo("photo_1", null)]);

    await caller.appointment.deletePhoto({ id: "photo_1" });

    expect(deleteStoredFiles).not.toHaveBeenCalled();
  });

  it("keeps a file that another Appointment Photo still names", async () => {
    // Possible only for photos recorded before one stored file was one Appointment Photo.
    const { caller, deleteStoredFiles } = createPhotoCaller([
      photo("photo_1", "shared_file"),
      photo("photo_2", "shared_file", "appointment_2")
    ]);

    await caller.appointment.deletePhoto({ id: "photo_1" });

    expect(deleteStoredFiles).not.toHaveBeenCalled();

    await caller.appointment.deletePhoto({ id: "photo_2" });

    expect(deleteStoredFiles).toHaveBeenCalledWith(["shared_file"]);
  });

  it("deletes no file when the deletion is refused or fails", async () => {
    const { caller, clientDeleteMany, deleteStoredFiles, photoIds } = createPhotoCaller([photo("photo_1", "file_1")]);

    await expect(caller.appointment.deletePhoto({ id: "photo_of_another_stylist" })).rejects.toMatchObject({
      code: "NOT_FOUND",
      message: "Appointment Photo not found."
    });

    // The transaction is rolled back: the Client and the photos are still there.
    clientDeleteMany.mockRejectedValueOnce(new Error("The database is gone."));
    await expect(caller.clientProfile.delete({ id: "client_1" })).rejects.toMatchObject({
      code: "INTERNAL_SERVER_ERROR"
    });

    expect(photoIds()).toEqual(["photo_1"]);
    expect(deleteStoredFiles).not.toHaveBeenCalled();
  });

  it("carries out the Stylist's deletion when storage fails, and logs the files left behind", async () => {
    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const failure = new Error("UploadThing is down.");
    const { caller, photoIds } = createPhotoCaller(
      [photo("photo_1", "file_1"), photo("photo_2", "file_2")],
      vi.fn().mockRejectedValue(failure)
    );

    await expect(caller.appointment.deletePhoto({ id: "photo_1" })).resolves.toMatchObject({
      id: "appointment_1"
    });
    await expect(caller.appointment.delete({ id: "appointment_1" })).resolves.toEqual({
      id: "appointment_1"
    });

    expect(photoIds()).toEqual([]);
    expect(logged).toHaveBeenCalledTimes(2);
    expect(logged).toHaveBeenNthCalledWith(1, expect.stringContaining("file_1"), failure);
    expect(logged).toHaveBeenNthCalledWith(2, expect.stringContaining("file_2"), failure);
  });
});

describe("a failed Appointment Photo", () => {
  it("is recorded with its state and without the error text a caller sends", async () => {
    const { caller, photoCreate, photoUpdate } = createPhotoCaller([]);
    const result = await caller.appointment.addPhoto({
      appointmentId: "appointment_1",
      clientId: "client_1",
      category: "before",
      status: "failed",
      uploadError: "Upload failed (500)."
    } as never);

    expect(photoCreate).toHaveBeenCalledTimes(1);
    expect(photoCreate.mock.calls[0]?.[0].data).toMatchObject({
      status: "failed"
    });
    expect(photoCreate.mock.calls[0]?.[0].data).not.toHaveProperty("uploadError");
    expect(JSON.stringify(result)).not.toContain("Upload failed");

    await caller.appointment.updatePhoto({
      id: "photo_1",
      status: "failed",
      uploadError: "Storage quota exceeded"
    } as never);

    expect(photoUpdate).toHaveBeenCalledWith({
      where: {
        id: "photo_1"
      },
      data: {
        status: "failed"
      }
    });
  });

  it("is returned without an error text that was stored before", async () => {
    const { caller } = createPhotoCaller([
      {
        ...photo("photo_1", null),
        status: "failed",
        uploadError: "Storage quota exceeded"
      }
    ]);
    const result = await caller.appointment.updatePhoto({
      id: "photo_1",
      category: "after"
    });

    expect(result.photos).toMatchObject([
      {
        id: "photo_1",
        status: "failed"
      }
    ]);
    expect(result.photos[0]).not.toHaveProperty("uploadError");
    expect(JSON.stringify(result)).not.toContain("Storage quota exceeded");
  });
});
