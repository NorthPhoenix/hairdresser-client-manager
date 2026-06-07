import { useUser } from "@clerk/expo";
import { Link } from "expo-router";
import { StyleSheet, Text, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { defaultLocale, t } from "@hcm/shared";

export default function HomeScreen() {
  const { isSignedIn, user } = useUser();

  return (
    <SafeAreaView style={styles.shell}>
      {isSignedIn ? (
        <View style={styles.panel}>
          <Text style={styles.kicker}>Hairdresser Client Manager</Text>
          <Text style={styles.title}>{t(defaultLocale, "protectedHomeTitle")}</Text>
          <Text style={styles.body}>{t(defaultLocale, "protectedHomeSubtitle")}</Text>
          <Text style={styles.identity}>{user?.primaryEmailAddress?.emailAddress}</Text>
        </View>
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
    flex: 1,
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
  link: {
    color: "#7d2f1b",
    fontSize: 16,
    fontWeight: "800"
  }
});
