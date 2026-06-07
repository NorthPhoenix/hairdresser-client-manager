export type SupportedLocale = "ru" | "en";

export const defaultLocale: SupportedLocale = "ru";

const messages = {
  ru: {
    protectedHomeTitle: "Рабочее место стилиста",
    protectedHomeSubtitle: "Защищенный экран для Клиентов и Записей.",
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
