export type SupportedLocale = "ru" | "en";

export const defaultLocale: SupportedLocale = "ru";
export const supportedLocales: SupportedLocale[] = ["ru", "en"];

export function normalizeLocale(locale: string | undefined): SupportedLocale {
  if (locale?.toLowerCase().startsWith("en")) {
    return "en";
  }

  return defaultLocale;
}

const messages = {
  ru: {
    protectedHomeTitle: "Рабочее место стилиста",
    protectedHomeSubtitle: "Защищенный экран для Клиентов и Записей.",
    onboardingTitle: "Настройте рабочее место",
    onboardingSubtitle: "Подтвердите язык, часовой пояс и адрес салона перед началом работы.",
    settingsTitle: "Настройки стилиста",
    settingsSubtitle: "Измените язык приложения, часовой пояс и адрес салона.",
    languageLabel: "Язык приложения",
    languageRussian: "Русский",
    languageEnglish: "English",
    timezoneLabel: "Часовой пояс",
    addressLabel: "Адрес салона или бизнеса",
    optionalSetupTitle: "Можно настроить позже",
    optionalSetupBody: "Google Calendar, напоминания стилиста и меню услуг необязательны после онбординга.",
    saveOnboarding: "Сохранить и продолжить",
    saveSettings: "Сохранить настройки",
    timezoneRequired: "Подтвердите часовой пояс.",
    signInTitle: "Вход для стилиста",
    signUpTitle: "Регистрация стилиста",
    verifyEmailTitle: "Подтвердите email",
    missingEnvTitle: "Нужна настройка окружения",
    missingEnvBody: "Добавьте ключ Clerk перед запуском приложения.",
    profileShareTitle: "Профиль Клиента",
    profileSharePlaceholder: "Публичная страница Profile Share готова к подключению данных."
  },
  en: {
    protectedHomeTitle: "Stylist workspace",
    protectedHomeSubtitle: "Protected screen for Clients and Appointments.",
    onboardingTitle: "Set up your workspace",
    onboardingSubtitle: "Confirm language, timezone, and salon address before using the app.",
    settingsTitle: "Stylist settings",
    settingsSubtitle: "Edit app language, timezone, and salon address.",
    languageLabel: "App language",
    languageRussian: "Русский",
    languageEnglish: "English",
    timezoneLabel: "Timezone",
    addressLabel: "Salon or business address",
    optionalSetupTitle: "Can be set up later",
    optionalSetupBody: "Google Calendar, Stylist Reminders, and Service Menu Items stay optional after onboarding.",
    saveOnboarding: "Save and continue",
    saveSettings: "Save settings",
    timezoneRequired: "Confirm a timezone.",
    signInTitle: "Stylist sign in",
    signUpTitle: "Stylist sign up",
    verifyEmailTitle: "Verify email",
    missingEnvTitle: "Environment setup required",
    missingEnvBody: "Add the Clerk key before running the app.",
    profileShareTitle: "Client Profile",
    profileSharePlaceholder: "The public Profile Share page is ready for data wiring."
  }
} as const;

export type MessageKey = keyof (typeof messages)["ru"];

export function t(locale: SupportedLocale, key: MessageKey): string {
  return messages[locale][key];
}
