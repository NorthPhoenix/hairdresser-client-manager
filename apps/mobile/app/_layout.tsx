import { ClerkProvider } from "@clerk/expo";
import { tokenCache } from "@clerk/expo/token-cache";
import { Slot } from "expo-router";
import { LogBox, StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { StatusBar } from "expo-status-bar";
import { defaultLocale, t } from "@hcm/shared";
import { TRPCProvider } from "../src/trpc/client";
import "../global.css";

const clerkPublishableKey = process.env.EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY;

LogBox.ignoreLogs(["Clerk has been loaded with development keys"]);

function MissingEnvironmentScreen() {
  return (
    <SafeAreaView style={styles.shell}>
      <StatusBar style="dark" />
      <View style={styles.panel}>
        <Text style={styles.kicker}>Hairdresser Client Manager</Text>
        <Text style={styles.title}>{t(defaultLocale, "missingEnvTitle")}</Text>
        <Text style={styles.body}>{t(defaultLocale, "missingEnvBody")}</Text>
        <Text style={styles.envName}>EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY</Text>
      </View>
    </SafeAreaView>
  );
}

export default function RootLayout() {
  if (!clerkPublishableKey) {
    return <MissingEnvironmentScreen />;
  }

  return (
    <ClerkProvider publishableKey={clerkPublishableKey} tokenCache={tokenCache}>
      <TRPCProvider>
        <Slot />
      </TRPCProvider>
    </ClerkProvider>
  );
}

const styles = StyleSheet.create({
  shell: {
    flex: 1,
    backgroundColor: "#f7f1e8",
    justifyContent: "center",
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
    fontSize: 28,
    fontWeight: "800"
  },
  body: {
    color: "#312c27",
    fontSize: 16,
    lineHeight: 22
  },
  envName: {
    backgroundColor: "#111111",
    borderRadius: 6,
    color: "#fffaf3",
    fontSize: 13,
    fontWeight: "700",
    paddingHorizontal: 10,
    paddingVertical: 8
  }
});
