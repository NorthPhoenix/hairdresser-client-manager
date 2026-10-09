import { buildShareImageSvg } from "@hcm/api";
import { db } from "@hcm/db";
import { normalizeLocale } from "@hcm/shared";

export async function GET(request: Request, { params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  const url = new URL(request.url);
  const share = await db.profileShare.findFirst({
    where: {
      token,
      revokedAt: null
    },
    include: {
      client: true
    }
  });

  if (!share) {
    return new Response("Profile Share is unavailable.", {
      status: 404
    });
  }

  const locale = url.searchParams.get("lang") ? normalizeLocale(url.searchParams.get("lang") ?? undefined) : share.client.language;
  const nextAppointment = await db.appointment.findFirst({
    where: {
      stylistId: share.client.stylistId,
      status: "scheduled",
      startsAt: {
        gte: new Date()
      },
      participants: {
        some: {
          clientId: share.clientId
        }
      }
    },
    orderBy: {
      startsAt: "asc"
    }
  });
  const lastCompletedAppointment = await db.appointment.findFirst({
    where: {
      stylistId: share.client.stylistId,
      status: "completed",
      participants: {
        some: {
          clientId: share.clientId
        }
      }
    },
    include: {
      services: {
        where: {
          clientId: share.clientId
        },
        include: {
          colorFormulas: {
            orderBy: {
              createdAt: "asc"
            }
          }
        },
        orderBy: {
          createdAt: "asc"
        }
      }
    },
    orderBy: {
      startsAt: "desc"
    }
  });

  const svg = buildShareImageSvg({
    clientName: share.client.name,
    locale,
    nextAppointment,
    lastCompletedAppointment,
    services: lastCompletedAppointment?.services ?? []
  });

  return new Response(svg, {
    headers: {
      "Cache-Control": "private, no-store",
      "Content-Type": "image/svg+xml; charset=utf-8"
    }
  });
}
