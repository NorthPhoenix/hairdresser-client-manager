import { useClerk, useSignUp } from "@clerk/expo";
import { Link, useRouter } from "expo-router";
import { useState } from "react";
import {
  Alert,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput
} from "react-native";
import { defaultLocale, t } from "@hcm/shared";

export default function SignUpScreen() {
  const router = useRouter();
  const { setActive } = useClerk();
  const { signUp, fetchStatus } = useSignUp();
  const [emailAddress, setEmailAddress] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [pendingVerification, setPendingVerification] = useState(false);

  async function submit() {
    if (fetchStatus === "fetching") {
      return;
    }

    try {
      const result = await signUp.create({
        emailAddress,
        password
      });

      if (result.error) {
        Alert.alert("Clerk", result.error.longMessage ?? result.error.message);
        return;
      }

      const verification = await signUp.verifications.sendEmailCode();

      if (verification.error) {
        Alert.alert("Clerk", verification.error.longMessage ?? verification.error.message);
        return;
      }

      setPendingVerification(true);
    } catch (error) {
      Alert.alert("Clerk", error instanceof Error ? error.message : "Sign up failed.");
    }
  }

  async function verify() {
    if (fetchStatus === "fetching") {
      return;
    }

    try {
      const result = await signUp.verifications.verifyEmailCode({ code });

      if (result.error) {
        Alert.alert("Clerk", result.error.longMessage ?? result.error.message);
        return;
      }

      if (signUp.status === "complete") {
        if (signUp.createdSessionId) {
          await setActive({ session: signUp.createdSessionId });
        } else {
          await signUp.finalize();
        }
        router.replace("/home");
      }
    } catch (error) {
      Alert.alert("Clerk", error instanceof Error ? error.message : "Verification failed.");
    }
  }

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === "ios" ? "padding" : undefined}
      style={styles.shell}
    >
      <Text style={styles.kicker}>Hairdresser Client Manager</Text>
      <Text style={styles.title}>
        {pendingVerification ? t(defaultLocale, "verifyEmailTitle") : t(defaultLocale, "signUpTitle")}
      </Text>
      {pendingVerification ? (
        <>
          <TextInput
            autoCapitalize="none"
            inputMode="numeric"
            onChangeText={setCode}
            placeholder="Code"
            style={styles.input}
            value={code}
          />
          <Pressable onPress={verify} style={styles.button}>
            <Text style={styles.buttonText}>{t(defaultLocale, "verifyEmailTitle")}</Text>
          </Pressable>
        </>
      ) : (
        <>
          <TextInput
            autoCapitalize="none"
            autoComplete="email"
            inputMode="email"
            onChangeText={setEmailAddress}
            placeholder="email@example.com"
            style={styles.input}
            value={emailAddress}
          />
          <TextInput
            autoCapitalize="none"
            onChangeText={setPassword}
            placeholder="Password"
            secureTextEntry
            style={styles.input}
            value={password}
          />
          <Pressable onPress={submit} style={styles.button}>
            <Text style={styles.buttonText}>{t(defaultLocale, "signUpTitle")}</Text>
          </Pressable>
        </>
      )}
      <Link href="/sign-in" style={styles.link}>
        {t(defaultLocale, "signInTitle")}
      </Link>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  shell: {
    backgroundColor: "#f7f1e8",
    flex: 1,
    gap: 14,
    justifyContent: "center",
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
    fontWeight: "800",
    marginBottom: 8
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
    fontSize: 15,
    fontWeight: "700",
    marginTop: 4
  }
});
