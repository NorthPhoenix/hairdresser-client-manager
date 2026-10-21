import { addedOrder } from "./addedOrder";
import type { Db } from "./context";
import { maxAmountCents } from "./input";

// The Travel Fee is the one Service Menu Item a Stylist marked as the fee for going to a Client.
// A Scheduled Appointment gets a Service from it when it is created at home or moved to at home,
// and loses the Services from it when it is moved back to the salon. Only those two moments
// change anything: a fee the Stylist removed from an at-home Appointment stays removed until
// the Appointment is moved to at home again. Everything here runs inside the mutation's
// transaction, under the Stylist lock (ADR 0014).

type ServiceRow = {
  id: string;
  appointmentId: string;
  clientId: string;
  menuItemId: string | null;
  name: string;
  priceCents: number;
  note: string | null;
  createdAt: Date;
};

/** What a mutation that moved an Appointment tells the app about the Travel Fee, to show and to undo. */
export type TravelFeeChange = {
  added: ReturnType<typeof toTravelFeeService>[];
  removed: ReturnType<typeof toTravelFeeService>[];
};

export const noTravelFeeChange: TravelFeeChange = { added: [], removed: [] };

export function toTravelFeeService(service: ServiceRow) {
  return {
    id: service.id,
    appointmentId: service.appointmentId,
    clientId: service.clientId,
    menuItemId: service.menuItemId,
    name: service.name,
    priceCents: service.priceCents,
    note: service.note ?? "",
    createdAt: service.createdAt.toISOString()
  };
}

/** The Stylist's Travel Fee item, if one is marked. Should there ever be two, the oldest counts. */
export function findTravelFeeItem(db: Db, stylistId: string) {
  return db.serviceMenuItem.findFirst({
    where: {
      stylistId,
      travelFee: true
    },
    orderBy: addedOrder
  });
}

/** Makes [itemId] the Stylist's only Travel Fee item: any other loses the mark. */
export async function unmarkOtherTravelFees(db: Db, stylistId: string, itemId: string) {
  await db.serviceMenuItem.updateMany({
    where: {
      stylistId,
      travelFee: true,
      id: {
        not: itemId
      }
    },
    data: {
      travelFee: false
    }
  });
}

/**
 * The Service a Scheduled Appointment that has just come to be at home gets from the Travel Fee
 * item, for [clientId] (its primary Client) at the item's default price. None when there is no
 * Travel Fee item, when the Appointment already has a Service from it (for any of its Clients),
 * or when it would take the Services total past the largest amount: the place is still saved.
 */
export async function addTravelFee(db: Db, stylistId: string, appointmentId: string, clientId: string): Promise<TravelFeeChange> {
  const item = await findTravelFeeItem(db, stylistId);

  if (!item) {
    return noTravelFeeChange;
  }

  const services = await db.appointmentService.findMany({
    where: {
      appointmentId
    },
    select: {
      menuItemId: true,
      priceCents: true
    }
  });
  const totalCents = services.reduce((total, service) => total + service.priceCents, 0);

  if (services.some((service) => service.menuItemId === item.id) || totalCents + item.defaultPriceCents > maxAmountCents) {
    return noTravelFeeChange;
  }

  const service = await db.appointmentService.create({
    data: {
      appointmentId,
      clientId,
      menuItemId: item.id,
      name: item.name,
      priceCents: item.defaultPriceCents,
      note: ""
    }
  });

  return { added: [toTravelFeeService(service)], removed: [] };
}

/**
 * Removes the Services from the Travel Fee item of a Scheduled Appointment that has just moved
 * to the salon, and says which they were, so that the app can put them back.
 */
export async function removeTravelFee(db: Db, stylistId: string, appointmentId: string): Promise<TravelFeeChange> {
  const item = await findTravelFeeItem(db, stylistId);

  if (!item) {
    return noTravelFeeChange;
  }

  const services = await db.appointmentService.findMany({
    where: {
      appointmentId,
      menuItemId: item.id
    },
    orderBy: addedOrder
  });

  if (services.length === 0) {
    return noTravelFeeChange;
  }

  await db.appointmentService.deleteMany({
    where: {
      id: {
        in: services.map((service) => service.id)
      }
    }
  });

  return { added: [], removed: services.map(toTravelFeeService) };
}
