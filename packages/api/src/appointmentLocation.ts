import type { Db } from "./context";

type AppointmentLocation = {
  locationType: "inSalon" | "atHome";
  locationAddress: string | null;
  locationAddressIsCustom?: boolean | null;
  primaryClient: {
    address: string | null;
  };
};

/**
 * Whether an at-home Appointment is at an address the Stylist typed rather than at its primary
 * Client's. Appointments from before the flag existed are told apart by comparing the two.
 */
export function hasCustomAddress(appointment: AppointmentLocation) {
  if (appointment.locationType !== "atHome") {
    return false;
  }

  return (
    appointment.locationAddressIsCustom ??
    (appointment.locationAddress ?? "") !== (appointment.primaryClient.address ?? "")
  );
}

export async function getSalonAddress(db: Db, stylistId: string) {
  const stylist = await db.stylist.findUniqueOrThrow({
    where: {
      id: stylistId
    },
    select: {
      salonAddress: true
    }
  });

  return stylist.salonAddress ?? "";
}

export async function getClientAddress(db: Db, clientId: string) {
  const client = await db.client.findUniqueOrThrow({
    where: {
      id: clientId
    },
    select: {
      address: true
    }
  });

  return client.address ?? "";
}

/**
 * The Appointment fields to write when another Client becomes primary. An at-home Appointment
 * without a custom address is at its primary Client's address, so it moves to the new primary's:
 * a former primary's home address must not stay on the Appointment.
 */
export async function getPrimaryClientChange(db: Db, appointment: AppointmentLocation, clientId: string) {
  if (appointment.locationType !== "atHome" || hasCustomAddress(appointment)) {
    return {
      primaryClientId: clientId
    };
  }

  return {
    primaryClientId: clientId,
    locationAddress: await getClientAddress(db, clientId),
    locationAddressIsCustom: false
  };
}
