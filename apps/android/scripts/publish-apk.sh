#!/usr/bin/env bash
# Publishes a signed release APK to the folder the download server serves (docs/releasing.md):
#
#   client-manager-<versionName>.apk   this release's own file, which never changes afterwards
#   client-manager.apk                 the same file, for links that name no version
#   latest.json                        which release is published, written last
#
# The API offers an update only up to the release latest.json names, and links to its own file
# (docs/adr/0018), so the order of the deploy and this step does not matter any more.
#
# Usage: apps/android/scripts/publish-apk.sh <app-release.apk> [download folder, default ~/hcm-downloads]
set -euo pipefail

apk=${1:?"Usage: publish-apk.sh <app-release.apk> [download folder]"}
folder=${2:-"$HOME/hcm-downloads"}
sdk=${ANDROID_HOME:-"$HOME/Android/Sdk"}
tools=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)

fail() {
  echo "publish-apk: $*" >&2
  exit 1
}

[[ -f $apk ]] || fail "no APK at $apk"
[[ -d $folder ]] || fail "no download folder at $folder"

badging=$("$tools/aapt2" dump badging "$apk" | sed -n 1p)
code=$(sed -n "s/.* versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")
name=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")
[[ $code =~ ^[1-9][0-9]{0,9}$ ]] || fail "the APK names no valid versionCode"
[[ $name =~ ^[0-9A-Za-z.+-]{1,50}$ ]] || fail "the APK names no valid versionName"

# Signed, and with the key of the APK that is published now: Android installs no update signed otherwise.
certificate() { "$tools/apksigner" verify --print-certs "$1" | sed -n "s/.*certificate SHA-256 digest: //p" | sed -n 1p; }
signed=$(certificate "$apk") || fail "the APK is not signed"
[[ -n $signed ]] || fail "the APK is not signed"
if [[ -f $folder/client-manager.apk ]]; then
  [[ $(certificate "$folder/client-manager.apk") == "$signed" ]] || fail "the APK is signed with another key than $folder/client-manager.apk"
fi

# Never an older release than the published one: Android does not install a lower versionCode over a higher one.
if [[ -f $folder/latest.json ]]; then
  current=$(sed -n 's/.*"versionCode": *\([0-9]*\).*/\1/p' "$folder/latest.json" | sed -n 1p)
  if [[ -n $current ]] && ((code < current)); then
    fail "versionCode $code is older than the published $current"
  fi
fi

file="client-manager-$name.apk"
sha256=$(sha256sum "$apk" | cut -d' ' -f1)

# Each file lands whole (copied beside it, then renamed), and latest.json only once the APK it names is there.
publish() {
  cp "$1" "$folder/.$2.tmp"
  chmod 644 "$folder/.$2.tmp"
  mv -f "$folder/.$2.tmp" "$folder/$2"
}
publish "$apk" "$file"
publish "$apk" client-manager.apk
manifest=$(mktemp)
printf '{\n  "versionCode": %s,\n  "versionName": "%s",\n  "file": "%s",\n  "sha256": "%s",\n  "publishedAt": "%s"\n}\n' \
  "$code" "$name" "$file" "$sha256" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$manifest"
publish "$manifest" latest.json
rm -f "$manifest"

echo "Published $name (versionCode $code) as $folder/$file, client-manager.apk and latest.json."
