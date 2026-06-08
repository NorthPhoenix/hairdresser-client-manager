import { useUser } from "@clerk/expo";
import * as SecureStore from "expo-secure-store";
import { Link } from "expo-router";
import { useEffect, useState } from "react";
import {
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View
} from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { defaultLocale, normalizeLocale, type SupportedLocale, t } from "@hcm/shared";

type StylistSettings = {
  language: SupportedLocale;
  timezone: string;
  salonAddress: string;
  onboardingCompletedAt: string | null;
};

const settingsKey = (userId: string) => `stylist-settings:${userId}`;

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

export default function HomeScreen() {
  const { isSignedIn, user } = useUser();
  const [settings, setSettings] = useState<StylistSettings>(() => createInitialSettings());
  const [loadingSettings, setLoadingSettings] = useState(false);

  useEffect(() => {
    let isMounted = true;

    async function loadSettings() {
      if (!user?.id) {
        setSettings(createInitialSettings());
        return;
      }

      setLoadingSettings(true);

      try {
        const storedSettings = await SecureStore.getItemAsync(settingsKey(user.id));

        if (!isMounted) {
          return;
        }

        setSettings(storedSettings ? JSON.parse(storedSettings) : createInitialSettings());
      } catch (error) {
        Alert.alert("Settings", error instanceof Error ? error.message : "Settings failed to load.");
      } finally {
        if (isMounted) {
          setLoadingSettings(false);
        }
      }
    }

    loadSettings();

    return () => {
      isMounted = false;
    };
  }, [user?.id]);

  const locale = settings.language;
  const onboardingComplete = Boolean(settings.onboardingCompletedAt);

  function updateSettings(nextSettings: Partial<StylistSettings>) {
    setSettings((currentSettings) => ({
      ...currentSettings,
      ...nextSettings
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

    await SecureStore.setItemAsync(settingsKey(user.id), JSON.stringify(nextSettings));
    setSettings(nextSettings);
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
  button: {
    alignItems: "center",
    backgroundColor: "#111111",
    borderRadius: 6,
    minHeight: 48,
    justifyContent: "center"
  },
  buttonText: {
    color: "#fffaf3",
    fontSize: 16,
    fontWeight: "800"
  },
  link: {
    color: "#7d2f1b",
    fontSize: 16,
    fontWeight: "800"
  }
});
