import { useAuth } from "@clerk/expo";
import { AuthView } from "@clerk/expo/native";
import { useRouter } from "expo-router";
import { useEffect } from "react";
import { StyleSheet, View } from "react-native";

export default function SignUpScreen() {
  const router = useRouter();
  const { isSignedIn } = useAuth();

  useEffect(() => {
    if (isSignedIn) {
      router.replace("/home");
    }
  }, [isSignedIn, router]);

  return (
    <View style={styles.shell}>
      <AuthView mode="signUp" />
    </View>
  );
}

const styles = StyleSheet.create({
  shell: {
    backgroundColor: "#f7f1e8",
    flex: 1
  }
});
