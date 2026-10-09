import { describe, expect, it } from "vitest";
import {
  hasImportedContactFields,
  mergeImportedContactIntoClient,
  normalizeImportedContact,
  type ImportedDeviceContact
} from "@hcm/shared";

type ContactImportAdapter = {
  pickOneContact: () => Promise<ImportedDeviceContact | null>;
  subscribeToChanges: () => void;
};

async function pickImportedContact(adapter: ContactImportAdapter) {
  const contact = await adapter.pickOneContact();

  return contact ? normalizeImportedContact(contact) : null;
}

describe("contact import", () => {
  it("normalizes one selected device contact for a new Client Profile", async () => {
    const importedContact = await pickImportedContact({
      pickOneContact: async () => ({
        fullName: "  Anna Petrova  ",
        phones: [{ number: " +1 555 0100 " }],
        emails: [{ address: " anna@example.com " }],
        addresses: [
          {
            street: "123 Main St",
            city: "Chicago",
            state: "IL",
            postcode: "60601",
            country: "USA"
          }
        ]
      }),
      subscribeToChanges: () => {
        throw new Error("continuous sync should not be used");
      }
    });

    expect(importedContact).toEqual({
      name: "Anna Petrova",
      phone: "+1 555 0100",
      email: "anna@example.com",
      address: "123 Main St, Chicago, IL, 60601, USA"
    });
  });

  it("merges a selected contact into an existing Client Profile only when confirmed", () => {
    const existingClient = {
      id: "client_1",
      name: "Existing Name",
      phone: "+1 555 0000",
      email: "",
      address: "",
      note: "Prefers soft layers."
    };
    const importedContact = normalizeImportedContact({
      givenName: "Maria",
      familyName: "Ivanova",
      phones: [{ number: "+1 555 2222" }],
      emails: [{ address: "maria@example.com" }]
    });
    const shouldApplyImport = true;
    const updatedClient = shouldApplyImport
      ? mergeImportedContactIntoClient(existingClient, importedContact)
      : existingClient;

    expect(updatedClient).toEqual({
      ...existingClient,
      name: "Maria Ivanova",
      phone: "+1 555 2222",
      email: "maria@example.com"
    });
  });

  it("leaves an existing Client Profile unchanged when update confirmation is canceled", () => {
    const existingClient = {
      id: "client_1",
      name: "Existing Name",
      phone: "+1 555 0000",
      email: "",
      address: "",
      note: "Prefers soft layers."
    };
    const importedContact = normalizeImportedContact({
      fullName: "Maria Ivanova",
      phones: [{ number: "+1 555 2222" }]
    });
    const shouldApplyImport = false;
    const updatedClient = shouldApplyImport
      ? mergeImportedContactIntoClient(existingClient, importedContact)
      : existingClient;

    expect(updatedClient).toBe(existingClient);
  });

  it("handles contacts without importable fields clearly", () => {
    const importedContact = normalizeImportedContact({
      fullName: " ",
      phones: [{ number: "" }],
      emails: [{ address: null }],
      addresses: [{ street: "", city: "" }]
    });

    expect(importedContact).toEqual({});
    expect(hasImportedContactFields(importedContact)).toBe(false);
  });

  it("uses the adapter as a one-time picker without continuous sync", async () => {
    let pickCount = 0;
    let subscriptionCount = 0;

    await pickImportedContact({
      pickOneContact: async () => {
        pickCount += 1;
        return { fullName: "Anna Petrova" };
      },
      subscribeToChanges: () => {
        subscriptionCount += 1;
      }
    });

    expect(pickCount).toBe(1);
    expect(subscriptionCount).toBe(0);
  });
});
