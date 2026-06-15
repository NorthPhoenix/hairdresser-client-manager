import { useUser } from "@clerk/expo";
import { Link } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Linking,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { defaultLocale, normalizeLocale, type MessageKey, type SupportedLocale, t } from "@hcm/shared";
import { trpc } from "../../src/trpc/client";

type StylistSettings = {
  language: SupportedLocale;
  timezone: string;
  salonAddress: string;
  onboardingCompletedAt: string | null;
};

type ClientProfile = {
  id: string;
  stylistId: string;
  name: string;
  language: SupportedLocale;
  phone: string;
  email: string;
  address: string;
  note: string;
  createdAt: string;
  updatedAt: string;
};

type ClientForm = {
  id: string | null;
  name: string;
  language: SupportedLocale;
  phone: string;
  email: string;
  address: string;
  note: string;
};

type Appointment = {
  id: string;
  primaryClientId: string;
  primaryClientName: string;
  participants: {
    clientId: string;
    name: string;
    address: string;
    isPrimary: boolean;
  }[];
  startsAt: string;
  endsAt: string | null;
  status: "scheduled" | "completed" | "canceled" | "noShow";
  locationType: "inSalon" | "atHome";
  locationAddress: string;
  note: string;
  mapUrl: string | null;
};

type AppointmentForm = {
  primaryClientId: string;
  additionalClientIds: string[];
  inlineClientName: string;
  startsAt: string;
  endsAt: string;
  locationType: "inSalon" | "atHome";
  customLocationAddress: string;
};

function getDeviceLocale(): SupportedLocale {
  return normalizeLocale(Intl.DateTimeFormat().resolvedOptions().locale);
}

function getDeviceTimezone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone ?? "";
}

function createInitialSettings(): StylistSettings {
  return {
    language: getDeviceLocale(),
    timezone: getDeviceTimezone(),
    salonAddress: "",
    onboardingCompletedAt: null
  };
}

function createClientForm(language: SupportedLocale): ClientForm {
  return {
    id: null,
    name: "",
    language,
    phone: "",
    email: "",
    address: "",
    note: ""
  };
}

function toSearchablePhone(phone: string): string {
  return phone.replace(/\D/g, "");
}

function toDateInputValue(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function getDayRange(dateValue: string) {
  const start = new Date(`${dateValue}T00:00:00`);
  const end = new Date(start);
  end.setDate(start.getDate() + 1);

  return {
    start: start.toISOString(),
    end: end.toISOString()
  };
}

function createAppointmentForm(): AppointmentForm {
  const startsAt = new Date();
  startsAt.setMinutes(0, 0, 0);
  startsAt.setHours(startsAt.getHours() + 1);

  return {
    primaryClientId: "",
    additionalClientIds: [],
    inlineClientName: "",
    startsAt: startsAt.toISOString(),
    endsAt: "",
    locationType: "inSalon",
    customLocationAddress: ""
  };
}

function formatAppointmentTime(appointment: Appointment): string {
  const start = new Date(appointment.startsAt).toLocaleString();
  const end = appointment.endsAt ? new Date(appointment.endsAt).toLocaleTimeString() : "";

  return [start, end].filter(Boolean).join(" - ");
}

const appointmentStatusMessageKey: Record<Appointment["status"], MessageKey> = {
  scheduled: "appointmentStatus_scheduled",
  completed: "appointmentStatus_completed",
  canceled: "appointmentStatus_canceled",
  noShow: "appointmentStatus_noShow"
};

export default function HomeScreen() {
  const { isSignedIn, user } = useUser();
  const [settings, setSettings] = useState<StylistSettings>(() => createInitialSettings());
  const [clientForm, setClientForm] = useState<ClientForm>(() => createClientForm(defaultLocale));
  const [clientSearch, setClientSearch] = useState("");
  const [appointmentForm, setAppointmentForm] = useState<AppointmentForm>(() => createAppointmentForm());
  const [appointmentNotes, setAppointmentNotes] = useState<Record<string, string>>({});
  const [calendarDate, setCalendarDate] = useState(() => toDateInputValue(new Date()));
  const utils = trpc.useUtils();
  const deviceBootstrapDefaults = useMemo(
    () => ({
      deviceLanguage: getDeviceLocale(),
      deviceTimezone: getDeviceTimezone()
    }),
    []
  );
  const bootstrapQuery = trpc.stylist.bootstrap.useQuery(deviceBootstrapDefaults, {
    enabled: Boolean(isSignedIn)
  });
  const saveSettingsMutation = trpc.stylist.saveSettings.useMutation({
    onSuccess(nextSettings) {
      setSettings({
        language: nextSettings.language,
        timezone: nextSettings.timezone,
        salonAddress: nextSettings.salonAddress,
        onboardingCompletedAt: nextSettings.onboardingCompletedAt
      });
      void utils.stylist.bootstrap.invalidate();
    }
  });
  const loadingSettings = bootstrapQuery.isLoading || saveSettingsMutation.isPending;
  const locale = settings.language;
  const onboardingComplete = Boolean(settings.onboardingCompletedAt);
  const clientListQuery = trpc.clientProfile.list.useQuery(
    {},
    {
      enabled: Boolean(isSignedIn && onboardingComplete)
    }
  );
  const saveClientMutation = trpc.clientProfile.save.useMutation({
    onSuccess(nextClient) {
      setClientForm({
        id: nextClient.id,
        name: nextClient.name,
        language: nextClient.language,
        phone: nextClient.phone,
        email: nextClient.email,
        address: nextClient.address,
        note: nextClient.note
      });
      void utils.clientProfile.list.invalidate();
    }
  });
  const deleteClientMutation = trpc.clientProfile.delete.useMutation({
    onSuccess() {
      clearClientForm();
      void utils.clientProfile.list.invalidate();
      void utils.appointment.home.invalidate();
      void utils.appointment.list.invalidate();
    }
  });
  const loadingClients = clientListQuery.isLoading || saveClientMutation.isPending || deleteClientMutation.isPending;
  const todayRange = useMemo(() => getDayRange(toDateInputValue(new Date())), []);
  const calendarRange = useMemo(() => getDayRange(calendarDate), [calendarDate]);
  const homeAppointmentsQuery = trpc.appointment.home.useQuery(todayRange, {
    enabled: Boolean(isSignedIn && onboardingComplete)
  });
  const calendarAppointmentsQuery = trpc.appointment.list.useQuery(calendarRange, {
    enabled: Boolean(isSignedIn && onboardingComplete)
  });
  const createAppointmentMutation = trpc.appointment.create.useMutation({
    onSuccess(result) {
      setAppointmentForm(createAppointmentForm());
      void utils.appointment.home.invalidate();
      void utils.appointment.list.invalidate();

      if (result.conflicts.length > 0) {
        Alert.alert("Appointments", t(locale, "appointmentConflictWarning"));
      } else {
        Alert.alert("Appointments", t(locale, "appointmentSaved"));
      }
    },
    onError(error) {
      Alert.alert("Appointments", error.message);
    }
  });
  const updateAppointmentMutation = trpc.appointment.update.useMutation({
    onSuccess() {
      void utils.appointment.home.invalidate();
      void utils.appointment.list.invalidate();
    },
    onError(error) {
      Alert.alert("Appointments", error.message);
    }
  });
  const deleteAppointmentMutation = trpc.appointment.delete.useMutation({
    onSuccess() {
      void utils.appointment.home.invalidate();
      void utils.appointment.list.invalidate();
    },
    onError(error) {
      Alert.alert("Appointments", error.message);
    }
  });
  const addParticipantMutation = trpc.appointment.addParticipant.useMutation({
    onSuccess() {
      void utils.appointment.home.invalidate();
      void utils.appointment.list.invalidate();
    },
    onError(error) {
      Alert.alert("Appointments", error.message);
    }
  });
  const removeParticipantMutation = trpc.appointment.removeParticipant.useMutation({
    onSuccess() {
      void utils.appointment.home.invalidate();
      void utils.appointment.list.invalidate();
    },
    onError(error) {
      Alert.alert("Appointments", error.message);
    }
  });

  useEffect(() => {
    if (!user?.id) {
      const initialSettings = createInitialSettings();
      setSettings(initialSettings);
      setClientForm(createClientForm(initialSettings.language));
    }
  }, [user?.id]);

  useEffect(() => {
    if (!bootstrapQuery.data) {
      return;
    }

    const nextSettings = {
      language: bootstrapQuery.data.language,
      timezone: bootstrapQuery.data.timezone,
      salonAddress: bootstrapQuery.data.salonAddress,
      onboardingCompletedAt: bootstrapQuery.data.onboardingCompletedAt
    };

    setSettings(nextSettings);
    setClientForm((currentForm) =>
      currentForm.id
        ? currentForm
        : {
            ...currentForm,
            language: nextSettings.language
          }
    );
  }, [bootstrapQuery.data]);

  const clients = clientListQuery.data ?? [];
  const searchQuery = clientSearch.trim().toLowerCase();
  const searchablePhone = toSearchablePhone(searchQuery);
  const filteredClients = useMemo(
    () =>
      clients.filter((client) => {
        if (!searchQuery) {
          return true;
        }

        return (
          client.name.toLowerCase().includes(searchQuery) ||
          (searchablePhone ? toSearchablePhone(client.phone).includes(searchablePhone) : false) ||
          client.phone.toLowerCase().includes(searchQuery)
        );
      }),
    [clients, searchQuery, searchablePhone]
  );
  const editingClient = clients.find((client) => client.id === clientForm.id);

  function updateSettings(nextSettings: Partial<StylistSettings>) {
    setSettings((currentSettings) => ({
      ...currentSettings,
      ...nextSettings
    }));

    if (nextSettings.language && !clientForm.id) {
      setClientForm((currentForm) => ({
        ...currentForm,
        language: nextSettings.language ?? currentForm.language
      }));
    }
  }

  function updateClientForm(nextForm: Partial<ClientForm>) {
    setClientForm((currentForm) => ({
      ...currentForm,
      ...nextForm
    }));
  }

  async function saveSettings() {
    if (!user?.id || loadingSettings) {
      return;
    }

    const timezone = settings.timezone.trim();

    if (!timezone) {
      Alert.alert("Settings", t(locale, "timezoneRequired"));
      return;
    }

    const nextSettings: StylistSettings = {
      ...settings,
      timezone,
      salonAddress: settings.salonAddress.trim(),
      onboardingCompletedAt: settings.onboardingCompletedAt ?? new Date().toISOString()
    };

    saveSettingsMutation.mutate(nextSettings, {
      onError(error) {
        Alert.alert("Settings", error.message);
      }
    });
  }

  function selectClient(client: ClientProfile) {
    setClientForm({
      id: client.id,
      name: client.name,
      language: client.language,
      phone: client.phone,
      email: client.email,
      address: client.address,
      note: client.note
    });
  }

  function clearClientForm() {
    setClientForm(createClientForm(settings.language));
  }

  function updateAppointmentForm(nextForm: Partial<AppointmentForm>) {
    setAppointmentForm((currentForm) => ({
      ...currentForm,
      ...nextForm
    }));
  }

  function moveCalendarDate(days: number) {
    const nextDate = new Date(`${calendarDate}T00:00:00`);
    nextDate.setDate(nextDate.getDate() + days);
    setCalendarDate(toDateInputValue(nextDate));
  }

  async function saveClient(confirmedDuplicate = false) {
    if (!user?.id || loadingClients) {
      return;
    }

    const name = clientForm.name.trim();
    const phone = clientForm.phone.trim();
    const email = clientForm.email.trim();
    const address = clientForm.address.trim();
    const note = clientForm.note.trim();

    if (!name) {
      Alert.alert("Clients", t(locale, "clientNameRequired"));
      return;
    }

    const phoneMatchesExistingClient = Boolean(
      phone &&
        clients.some(
          (client) => client.id !== clientForm.id && toSearchablePhone(client.phone) === toSearchablePhone(phone)
        )
    );

    if (phoneMatchesExistingClient && !confirmedDuplicate) {
      Alert.alert(t(locale, "duplicatePhoneTitle"), t(locale, "duplicatePhoneBody"), [
        { text: t(locale, "cancel"), style: "cancel" },
        { text: t(locale, "duplicatePhoneContinue"), onPress: () => saveClient(true) }
      ]);
      return;
    }

    saveClientMutation.mutate(
      {
        id: clientForm.id ?? undefined,
        name,
        language: clientForm.language,
        phone,
        email,
        address,
        note
      },
      {
        onSuccess() {
          Alert.alert("Clients", t(locale, "clientSaved"));
        },
        onError(error) {
          Alert.alert("Clients", error.message);
        }
      }
    );
  }

  async function deleteClient() {
    if (!editingClient) {
      return;
    }

    deleteClientMutation.mutate(
      {
        id: editingClient.id
      },
      {
        onSuccess() {
          Alert.alert("Clients", t(locale, "clientDeleted"));
        },
        onError(error) {
          Alert.alert("Clients", error.message);
        }
      }
    );
  }

  function createAppointment() {
    if (createAppointmentMutation.isPending) {
      return;
    }

    if (!appointmentForm.primaryClientId && !appointmentForm.inlineClientName.trim()) {
      Alert.alert("Appointments", t(locale, "appointmentClientRequired"));
      return;
    }

    if (!appointmentForm.startsAt.trim()) {
      Alert.alert("Appointments", t(locale, "appointmentStartRequired"));
      return;
    }

    createAppointmentMutation.mutate({
      primaryClientId: appointmentForm.primaryClientId || undefined,
      additionalClientIds: appointmentForm.additionalClientIds,
      inlineClientName: appointmentForm.inlineClientName.trim() || undefined,
      startsAt: new Date(appointmentForm.startsAt).toISOString(),
      endsAt: appointmentForm.endsAt.trim() ? new Date(appointmentForm.endsAt).toISOString() : undefined,
      locationType: appointmentForm.locationType,
      customLocationAddress: appointmentForm.customLocationAddress.trim() || undefined
    });
  }

  async function openAppointmentMap(appointment: Appointment) {
    if (!appointment.mapUrl) {
      Alert.alert("Appointments", t(locale, "appointmentMapUnavailable"));
      return;
    }

    await Linking.openURL(appointment.mapUrl);
  }

  function updateAppointmentStatus(appointment: Appointment, status: Appointment["status"]) {
    updateAppointmentMutation.mutate({
      id: appointment.id,
      status
    });
  }

  function updateAppointmentPrimary(appointment: Appointment, clientId: string) {
    updateAppointmentMutation.mutate({
      id: appointment.id,
      primaryClientId: clientId
    });
  }

  function saveAppointmentNote(appointment: Appointment) {
    updateAppointmentMutation.mutate({
      id: appointment.id,
      note: appointmentNotes[appointment.id] ?? appointment.note
    });
  }

  function deleteAppointment(appointment: Appointment) {
    deleteAppointmentMutation.mutate({
      id: appointment.id
    });
  }

  function addClientToAppointment(appointment: Appointment, clientId: string) {
    if (!clientId) {
      return;
    }

    addParticipantMutation.mutate({
      appointmentId: appointment.id,
      clientId
    });
  }

  function removeClientFromAppointment(appointment: Appointment, clientId: string) {
    removeParticipantMutation.mutate({
      appointmentId: appointment.id,
      clientId
    });
  }

  function toggleAdditionalClient(clientId: string) {
    setAppointmentForm((currentForm) => ({
      ...currentForm,
      additionalClientIds: currentForm.additionalClientIds.includes(clientId)
        ? currentForm.additionalClientIds.filter((selectedClientId) => selectedClientId !== clientId)
        : [...currentForm.additionalClientIds, clientId]
    }));
  }

  function renderAppointmentList(appointments: Appointment[]) {
    if (appointments.length === 0) {
      return (
        <View style={styles.emptyState}>
          <Text style={styles.optionalTitle}>{t(locale, "appointmentsEmptyTitle")}</Text>
          <Text style={styles.body}>{t(locale, "appointmentsEmptyBody")}</Text>
        </View>
      );
    }

    return appointments.map((appointment) => (
      <View key={appointment.id} style={styles.clientRow}>
        <View style={styles.clientRowText}>
          <Text style={styles.clientName}>{appointment.primaryClientName}</Text>
          <Text style={styles.clientMeta}>{formatAppointmentTime(appointment)}</Text>
          <Text style={styles.clientMeta}>
            {appointment.participants.map((participant) => (participant.isPrimary ? `${participant.name} *` : participant.name)).join(" · ")}
          </Text>
          <Text style={styles.clientMeta}>
            {appointment.locationType === "atHome" ? t(locale, "appointmentAtHome") : t(locale, "appointmentInSalon")}
            {appointment.locationAddress ? ` · ${appointment.locationAddress}` : ""}
          </Text>
          <Text style={styles.clientMeta}>{t(locale, appointmentStatusMessageKey[appointment.status])}</Text>
        </View>
        <TextInput
          multiline
          onChangeText={(note) =>
            setAppointmentNotes((currentNotes) => ({
              ...currentNotes,
              [appointment.id]: note
            }))
          }
          placeholder={t(locale, "appointmentNotePlaceholder")}
          style={[styles.input, styles.noteInput]}
          value={appointmentNotes[appointment.id] ?? appointment.note}
        />
        <View style={styles.buttonRow}>
          <Pressable onPress={() => updateAppointmentStatus(appointment, "completed")} style={styles.statusButton}>
            <Text style={styles.statusButtonText}>{t(locale, "markCompleted")}</Text>
          </Pressable>
          <Pressable onPress={() => updateAppointmentStatus(appointment, "canceled")} style={styles.statusButton}>
            <Text style={styles.statusButtonText}>{t(locale, "markCanceled")}</Text>
          </Pressable>
          <Pressable onPress={() => updateAppointmentStatus(appointment, "noShow")} style={styles.statusButton}>
            <Text style={styles.statusButtonText}>{t(locale, "markNoShow")}</Text>
          </Pressable>
          <Pressable onPress={() => updateAppointmentStatus(appointment, "scheduled")} style={styles.statusButton}>
            <Text style={styles.statusButtonText}>{t(locale, "markScheduled")}</Text>
          </Pressable>
        </View>
        <View style={styles.clientList}>
          {appointment.participants.map((participant) => (
            <View key={participant.clientId} style={styles.participantRow}>
              <Text style={styles.clientMeta}>{participant.isPrimary ? `${participant.name} *` : participant.name}</Text>
              <View style={styles.buttonRow}>
                {!participant.isPrimary ? (
                  <Pressable onPress={() => updateAppointmentPrimary(appointment, participant.clientId)} style={styles.statusButton}>
                    <Text style={styles.statusButtonText}>{t(locale, "makePrimary")}</Text>
                  </Pressable>
                ) : null}
                <Pressable onPress={() => removeClientFromAppointment(appointment, participant.clientId)} style={styles.statusButton}>
                  <Text style={styles.statusButtonText}>{t(locale, "removeClientFromAppointment")}</Text>
                </Pressable>
              </View>
            </View>
          ))}
          {clients
            .filter((client) => !appointment.participants.some((participant) => participant.clientId === client.id))
            .map((client) => (
              <Pressable key={client.id} onPress={() => addClientToAppointment(appointment, client.id)} style={styles.statusButton}>
                <Text style={styles.statusButtonText}>
                  {t(locale, "addClientToAppointment")}: {client.name}
                </Text>
              </Pressable>
            ))}
        </View>
        <View style={styles.buttonRow}>
          <Pressable onPress={() => saveAppointmentNote(appointment)} style={[styles.secondaryButton, styles.flexButton]}>
            <Text style={styles.secondaryButtonText}>{t(locale, "saveAppointmentNote")}</Text>
          </Pressable>
          <Pressable onPress={() => deleteAppointment(appointment)} style={[styles.secondaryButton, styles.flexButton]}>
            <Text style={styles.secondaryButtonText}>{t(locale, "deleteAppointment")}</Text>
          </Pressable>
        </View>
        {appointment.mapUrl ? (
          <Pressable onPress={() => openAppointmentMap(appointment)}>
            <Text style={styles.inlineAction}>{t(locale, "openInMaps")}</Text>
          </Pressable>
        ) : null}
      </View>
    ));
  }

  return (
    <SafeAreaView style={styles.shell}>
      {isSignedIn ? (
        <ScrollView contentContainerStyle={styles.content}>
          <View style={styles.panel}>
            <Text style={styles.kicker}>Hairdresser Client Manager</Text>
            <Text style={styles.title}>
              {t(locale, onboardingComplete ? "protectedHomeTitle" : "onboardingTitle")}
            </Text>
            <Text style={styles.body}>
              {t(locale, onboardingComplete ? "protectedHomeSubtitle" : "onboardingSubtitle")}
            </Text>
            <Text style={styles.identity}>{user?.primaryEmailAddress?.emailAddress}</Text>
            {onboardingComplete ? (
              <>
                <Text style={styles.sectionTitle}>{t(locale, "settingsTitle")}</Text>
                <Text style={styles.body}>{t(locale, "settingsSubtitle")}</Text>
              </>
            ) : null}

            <View style={styles.field}>
              <Text style={styles.label}>{t(locale, "languageLabel")}</Text>
              <View style={styles.segmentedControl}>
                <Pressable
                  onPress={() => updateSettings({ language: "ru" })}
                  style={[
                    styles.segment,
                    settings.language === "ru" ? styles.selectedSegment : null
                  ]}
                >
                  <Text
                    style={[
                      styles.segmentText,
                      settings.language === "ru" ? styles.selectedSegmentText : null
                    ]}
                  >
                    {t(locale, "languageRussian")}
                  </Text>
                </Pressable>
                <Pressable
                  onPress={() => updateSettings({ language: "en" })}
                  style={[
                    styles.segment,
                    settings.language === "en" ? styles.selectedSegment : null
                  ]}
                >
                  <Text
                    style={[
                      styles.segmentText,
                      settings.language === "en" ? styles.selectedSegmentText : null
                    ]}
                  >
                    {t(locale, "languageEnglish")}
                  </Text>
                </Pressable>
              </View>
            </View>

            <View style={styles.field}>
              <Text style={styles.label}>{t(locale, "timezoneLabel")}</Text>
              <TextInput
                autoCapitalize="none"
                onChangeText={(timezone) => updateSettings({ timezone })}
                placeholder="America/Chicago"
                style={styles.input}
                value={settings.timezone}
              />
            </View>

            <View style={styles.field}>
              <Text style={styles.label}>{t(locale, "addressLabel")}</Text>
              <TextInput
                multiline
                onChangeText={(salonAddress) => updateSettings({ salonAddress })}
                placeholder="123 Main St"
                style={[styles.input, styles.addressInput]}
                value={settings.salonAddress}
              />
            </View>

            <View style={styles.optionalSetup}>
              <Text style={styles.optionalTitle}>{t(locale, "optionalSetupTitle")}</Text>
              <Text style={styles.body}>{t(locale, "optionalSetupBody")}</Text>
            </View>

            <Pressable onPress={saveSettings} style={styles.button}>
              <Text style={styles.buttonText}>
                {t(locale, onboardingComplete ? "saveSettings" : "saveOnboarding")}
              </Text>
            </Pressable>
          </View>

          {onboardingComplete ? (
            <View style={styles.panel}>
              <View style={styles.formHeader}>
                <View>
                  <Text style={styles.sectionTitle}>{t(locale, "homeAppointmentsTitle")}</Text>
                  <Text style={styles.body}>{t(locale, "homeAppointmentsSubtitle")}</Text>
                </View>
                <Pressable onPress={() => setAppointmentForm(createAppointmentForm())}>
                  <Text style={styles.inlineAction}>{t(locale, "newAppointmentTitle")}</Text>
                </Pressable>
              </View>
              <View style={styles.clientList}>{renderAppointmentList(homeAppointmentsQuery.data ?? [])}</View>
            </View>
          ) : null}

          {onboardingComplete ? (
            <View style={styles.panel}>
              <Text style={styles.sectionTitle}>{t(locale, "calendarTitle")}</Text>
              <Text style={styles.body}>{t(locale, "calendarSubtitle")}</Text>
              <View style={styles.buttonRow}>
                <Pressable onPress={() => moveCalendarDate(-1)} style={[styles.secondaryButton, styles.flexButton]}>
                  <Text style={styles.secondaryButtonText}>{t(locale, "previousDay")}</Text>
                </Pressable>
                <TextInput
                  autoCapitalize="none"
                  onChangeText={setCalendarDate}
                  placeholder="2026-06-13"
                  style={[styles.input, styles.flexButton]}
                  value={calendarDate}
                />
                <Pressable onPress={() => moveCalendarDate(1)} style={[styles.secondaryButton, styles.flexButton]}>
                  <Text style={styles.secondaryButtonText}>{t(locale, "nextDay")}</Text>
                </Pressable>
              </View>
              <View style={styles.clientList}>{renderAppointmentList(calendarAppointmentsQuery.data ?? [])}</View>
            </View>
          ) : null}

          {onboardingComplete ? (
            <View style={styles.panel}>
              <Text style={styles.sectionTitle}>{t(locale, "newAppointmentTitle")}</Text>
              <Text style={styles.body}>{t(locale, "newAppointmentSubtitle")}</Text>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "primaryClientLabel")}</Text>
                <View style={styles.clientList}>
                  {clients.map((client) => (
                    <Pressable
                      key={client.id}
                      onPress={() =>
                        updateAppointmentForm({
                          primaryClientId: client.id,
                          additionalClientIds: appointmentForm.additionalClientIds.filter((clientId) => clientId !== client.id),
                          inlineClientName: "",
                          customLocationAddress:
                            appointmentForm.locationType === "atHome" && !appointmentForm.customLocationAddress
                              ? client.address
                              : appointmentForm.customLocationAddress
                        })
                      }
                      style={[
                        styles.clientRow,
                        client.id === appointmentForm.primaryClientId ? styles.selectedClientRow : null
                      ]}
                    >
                      <Text style={styles.clientName}>{client.name}</Text>
                      {client.address ? <Text style={styles.clientMeta}>{client.address}</Text> : null}
                    </Pressable>
                  ))}
                </View>
              </View>

              {appointmentForm.primaryClientId ? (
                <View style={styles.field}>
                  <Text style={styles.label}>{t(locale, "additionalClientsLabel")}</Text>
                  <View style={styles.clientList}>
                    {clients
                      .filter((client) => client.id !== appointmentForm.primaryClientId)
                      .map((client) => (
                        <Pressable
                          key={client.id}
                          onPress={() => toggleAdditionalClient(client.id)}
                          style={[
                            styles.clientRow,
                            appointmentForm.additionalClientIds.includes(client.id) ? styles.selectedClientRow : null
                          ]}
                        >
                          <Text style={styles.clientName}>{client.name}</Text>
                          {client.address ? <Text style={styles.clientMeta}>{client.address}</Text> : null}
                        </Pressable>
                      ))}
                  </View>
                </View>
              ) : null}

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "inlineClientLabel")}</Text>
                <TextInput
                  onChangeText={(inlineClientName) =>
                    updateAppointmentForm({
                      inlineClientName,
                      primaryClientId: inlineClientName ? "" : appointmentForm.primaryClientId,
                      additionalClientIds: inlineClientName ? [] : appointmentForm.additionalClientIds
                    })
                  }
                  placeholder="Walk-in Client"
                  style={styles.input}
                  value={appointmentForm.inlineClientName}
                />
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "appointmentStartLabel")}</Text>
                <TextInput
                  autoCapitalize="none"
                  onChangeText={(startsAt) => updateAppointmentForm({ startsAt })}
                  placeholder="2026-06-13T14:00:00.000Z"
                  style={styles.input}
                  value={appointmentForm.startsAt}
                />
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "appointmentEndLabel")}</Text>
                <TextInput
                  autoCapitalize="none"
                  onChangeText={(endsAt) => updateAppointmentForm({ endsAt })}
                  placeholder="Optional"
                  style={styles.input}
                  value={appointmentForm.endsAt}
                />
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "appointmentLocationLabel")}</Text>
                <View style={styles.segmentedControl}>
                  <Pressable
                    onPress={() => updateAppointmentForm({ locationType: "inSalon" })}
                    style={[styles.segment, appointmentForm.locationType === "inSalon" ? styles.selectedSegment : null]}
                  >
                    <Text
                      style={[
                        styles.segmentText,
                        appointmentForm.locationType === "inSalon" ? styles.selectedSegmentText : null
                      ]}
                    >
                      {t(locale, "appointmentInSalon")}
                    </Text>
                  </Pressable>
                  <Pressable
                    onPress={() => {
                      const selectedClient = clients.find((client) => client.id === appointmentForm.primaryClientId);
                      updateAppointmentForm({
                        locationType: "atHome",
                        customLocationAddress: appointmentForm.customLocationAddress || selectedClient?.address || ""
                      });
                    }}
                    style={[styles.segment, appointmentForm.locationType === "atHome" ? styles.selectedSegment : null]}
                  >
                    <Text
                      style={[
                        styles.segmentText,
                        appointmentForm.locationType === "atHome" ? styles.selectedSegmentText : null
                      ]}
                    >
                      {t(locale, "appointmentAtHome")}
                    </Text>
                  </Pressable>
                </View>
              </View>

              {appointmentForm.locationType === "atHome" ? (
                <View style={styles.field}>
                  <Text style={styles.label}>{t(locale, "appointmentAddressLabel")}</Text>
                  <TextInput
                    multiline
                    onChangeText={(customLocationAddress) => updateAppointmentForm({ customLocationAddress })}
                    placeholder="Client address"
                    style={[styles.input, styles.addressInput]}
                    value={appointmentForm.customLocationAddress}
                  />
                </View>
              ) : null}

              <Pressable onPress={createAppointment} style={styles.button}>
                <Text style={styles.buttonText}>{t(locale, "createAppointment")}</Text>
              </Pressable>
            </View>
          ) : null}

          {onboardingComplete ? (
            <View style={styles.panel}>
              <Text style={styles.sectionTitle}>{t(locale, "clientsTitle")}</Text>
              <Text style={styles.body}>{t(locale, "clientsSubtitle")}</Text>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "clientSearchLabel")}</Text>
                <TextInput
                  autoCapitalize="none"
                  onChangeText={setClientSearch}
                  placeholder={t(locale, "clientSearchPlaceholder")}
                  style={styles.input}
                  value={clientSearch}
                />
              </View>

              <View style={styles.clientList}>
                {filteredClients.length > 0 ? (
                  filteredClients.map((client) => (
                    <Pressable
                      key={client.id}
                      onPress={() => selectClient(client)}
                      style={[
                        styles.clientRow,
                        client.id === clientForm.id ? styles.selectedClientRow : null
                      ]}
                    >
                      <View style={styles.clientRowText}>
                        <Text style={styles.clientName}>{client.name}</Text>
                        <Text style={styles.clientMeta}>
                          {[client.phone, t(locale, client.language === "ru" ? "languageRussian" : "languageEnglish")]
                            .filter(Boolean)
                            .join(" · ")}
                        </Text>
                      </View>
                    </Pressable>
                  ))
                ) : (
                  <View style={styles.emptyState}>
                    <Text style={styles.optionalTitle}>
                      {clients.length === 0 ? t(locale, "noClientsTitle") : t(locale, "noClientResults")}
                    </Text>
                    {clients.length === 0 ? (
                      <Text style={styles.body}>{t(locale, "noClientsBody")}</Text>
                    ) : null}
                  </View>
                )}
              </View>

              <View style={styles.formHeader}>
                <Text style={styles.sectionTitle}>
                  {editingClient ? t(locale, "editClientTitle") : t(locale, "newClientTitle")}
                </Text>
                <Pressable onPress={clearClientForm}>
                  <Text style={styles.inlineAction}>{t(locale, "clearClientForm")}</Text>
                </Pressable>
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "clientNameLabel")}</Text>
                <TextInput
                  onChangeText={(name) => updateClientForm({ name })}
                  placeholder="Anna Petrova"
                  style={styles.input}
                  value={clientForm.name}
                />
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "languageLabel")}</Text>
                <View style={styles.segmentedControl}>
                  <Pressable
                    onPress={() => updateClientForm({ language: "ru" })}
                    style={[
                      styles.segment,
                      clientForm.language === "ru" ? styles.selectedSegment : null
                    ]}
                  >
                    <Text
                      style={[
                        styles.segmentText,
                        clientForm.language === "ru" ? styles.selectedSegmentText : null
                      ]}
                    >
                      {t(locale, "languageRussian")}
                    </Text>
                  </Pressable>
                  <Pressable
                    onPress={() => updateClientForm({ language: "en" })}
                    style={[
                      styles.segment,
                      clientForm.language === "en" ? styles.selectedSegment : null
                    ]}
                  >
                    <Text
                      style={[
                        styles.segmentText,
                        clientForm.language === "en" ? styles.selectedSegmentText : null
                      ]}
                    >
                      {t(locale, "languageEnglish")}
                    </Text>
                  </Pressable>
                </View>
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "clientPhoneLabel")}</Text>
                <TextInput
                  autoComplete="tel"
                  inputMode="tel"
                  onChangeText={(phone) => updateClientForm({ phone })}
                  placeholder="+1 555 0100"
                  style={styles.input}
                  value={clientForm.phone}
                />
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "clientEmailLabel")}</Text>
                <TextInput
                  autoCapitalize="none"
                  autoComplete="email"
                  inputMode="email"
                  onChangeText={(email) => updateClientForm({ email })}
                  placeholder="client@example.com"
                  style={styles.input}
                  value={clientForm.email}
                />
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "clientAddressLabel")}</Text>
                <TextInput
                  multiline
                  onChangeText={(address) => updateClientForm({ address })}
                  placeholder="123 Main St"
                  style={[styles.input, styles.addressInput]}
                  value={clientForm.address}
                />
              </View>

              <View style={styles.field}>
                <Text style={styles.label}>{t(locale, "clientNoteLabel")}</Text>
                <TextInput
                  multiline
                  onChangeText={(note) => updateClientForm({ note })}
                  placeholder="Prefers soft layers."
                  style={[styles.input, styles.noteInput]}
                  value={clientForm.note}
                />
              </View>

              <View style={styles.buttonRow}>
                <Pressable onPress={() => saveClient()} style={[styles.button, styles.flexButton]}>
                  <Text style={styles.buttonText}>
                    {editingClient ? t(locale, "saveClient") : t(locale, "createClient")}
                  </Text>
                </Pressable>
                {editingClient ? (
                  <Pressable onPress={deleteClient} style={[styles.secondaryButton, styles.flexButton]}>
                    <Text style={styles.secondaryButtonText}>{t(locale, "deleteClient")}</Text>
                  </Pressable>
                ) : null}
              </View>
            </View>
          ) : null}
        </ScrollView>
      ) : (
        <View style={styles.panel}>
          <Text style={styles.title}>{t(defaultLocale, "signInTitle")}</Text>
          <Link href="/sign-in" style={styles.link}>
            Open email/password sign in
          </Link>
          <Link href="/sign-up" style={styles.link}>
            Create Stylist account
          </Link>
        </View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  shell: {
    backgroundColor: "#f7f1e8",
    flex: 1
  },
  content: {
    gap: 18,
    justifyContent: "center",
    minHeight: "100%",
    padding: 24
  },
  panel: {
    backgroundColor: "#fffaf3",
    borderColor: "#111111",
    borderRadius: 8,
    borderWidth: 1,
    gap: 12,
    padding: 24
  },
  kicker: {
    color: "#6d6259",
    fontSize: 13,
    fontWeight: "700",
    textTransform: "uppercase"
  },
  title: {
    color: "#111111",
    fontSize: 30,
    fontWeight: "800"
  },
  body: {
    color: "#312c27",
    fontSize: 16,
    lineHeight: 22
  },
  identity: {
    color: "#7d2f1b",
    fontSize: 15,
    fontWeight: "700"
  },
  sectionTitle: {
    color: "#111111",
    fontSize: 20,
    fontWeight: "800",
    marginTop: 8
  },
  field: {
    gap: 8
  },
  label: {
    color: "#312c27",
    fontSize: 14,
    fontWeight: "800"
  },
  segmentedControl: {
    borderColor: "#111111",
    borderRadius: 6,
    borderWidth: 1,
    flexDirection: "row",
    overflow: "hidden"
  },
  segment: {
    alignItems: "center",
    backgroundColor: "#fffaf3",
    flex: 1,
    minHeight: 44,
    justifyContent: "center",
    paddingHorizontal: 12
  },
  selectedSegment: {
    backgroundColor: "#111111"
  },
  segmentText: {
    color: "#111111",
    fontSize: 15,
    fontWeight: "800"
  },
  selectedSegmentText: {
    color: "#fffaf3"
  },
  input: {
    backgroundColor: "#fffaf3",
    borderColor: "#111111",
    borderRadius: 6,
    borderWidth: 1,
    color: "#111111",
    fontSize: 16,
    paddingHorizontal: 14,
    paddingVertical: 12
  },
  addressInput: {
    minHeight: 86,
    textAlignVertical: "top"
  },
  noteInput: {
    minHeight: 112,
    textAlignVertical: "top"
  },
  optionalSetup: {
    borderColor: "#d8c5ad",
    borderRadius: 6,
    borderWidth: 1,
    gap: 6,
    padding: 12
  },
  optionalTitle: {
    color: "#111111",
    fontSize: 15,
    fontWeight: "800"
  },
  clientList: {
    gap: 8
  },
  clientRow: {
    backgroundColor: "#f6ead9",
    borderColor: "#d8c5ad",
    borderRadius: 6,
    borderWidth: 1,
    minHeight: 58,
    justifyContent: "center",
    padding: 12
  },
  selectedClientRow: {
    borderColor: "#7d2f1b",
    borderWidth: 2
  },
  statusButton: {
    alignItems: "center",
    backgroundColor: "#f6ead9",
    borderColor: "#d8c5ad",
    borderRadius: 6,
    borderWidth: 1,
    minHeight: 40,
    justifyContent: "center",
    paddingHorizontal: 10
  },
  statusButtonText: {
    color: "#111111",
    fontSize: 13,
    fontWeight: "800"
  },
  clientRowText: {
    gap: 4
  },
  participantRow: {
    borderColor: "#d8c5ad",
    borderRadius: 6,
    borderWidth: 1,
    gap: 8,
    padding: 10
  },
  clientName: {
    color: "#111111",
    fontSize: 17,
    fontWeight: "800"
  },
  clientMeta: {
    color: "#6d6259",
    fontSize: 14,
    fontWeight: "700"
  },
  emptyState: {
    borderColor: "#d8c5ad",
    borderRadius: 6,
    borderStyle: "dashed",
    borderWidth: 1,
    gap: 6,
    padding: 14
  },
  formHeader: {
    alignItems: "center",
    flexDirection: "row",
    gap: 12,
    justifyContent: "space-between"
  },
  inlineAction: {
    color: "#7d2f1b",
    fontSize: 14,
    fontWeight: "800"
  },
  buttonRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 10
  },
  flexButton: {
    flex: 1,
    minWidth: 150
  },
  button: {
    alignItems: "center",
    backgroundColor: "#111111",
    borderRadius: 6,
    minHeight: 48,
    justifyContent: "center",
    paddingHorizontal: 12
  },
  buttonText: {
    color: "#fffaf3",
    fontSize: 16,
    fontWeight: "800"
  },
  secondaryButton: {
    alignItems: "center",
    backgroundColor: "#fffaf3",
    borderColor: "#7d2f1b",
    borderRadius: 6,
    borderWidth: 1,
    minHeight: 48,
    justifyContent: "center",
    paddingHorizontal: 12
  },
  secondaryButtonText: {
    color: "#7d2f1b",
    fontSize: 16,
    fontWeight: "800"
  },
  link: {
    color: "#7d2f1b",
    fontSize: 16,
    fontWeight: "800"
  }
});
