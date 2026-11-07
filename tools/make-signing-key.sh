#!/usr/bin/env bash
# Makes Eclipse's release signing key (once). Keep the key and its password safe: every update must be signed with
# the same key, or Android won't install it over the previous version.
#
# Writes, in the repository folder (all ignored by git):
#   release.jks            the key
#   keystore.properties    where it is + its password, so your own builds are signed the same way
#   keystore-base64.txt    the key as text, to paste into GitHub (delete it afterwards)
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f release.jks ]; then echo "release.jks already exists: keep using it."; exit 1; fi
command -v keytool >/dev/null || { echo "keytool not found: install a JDK 17 first."; exit 1; }

read -r -p "Your name (shown in the certificate): " NAME
read -r -s -p "Choose a password for the key (6+ characters): " PASS; echo
read -r -s -p "Same password again: " PASS2; echo
[ "$PASS" = "$PASS2" ] || { echo "The passwords don't match."; exit 1; }
[ ${#PASS} -ge 6 ] || { echo "Use at least 6 characters."; exit 1; }

keytool -genkeypair -v -keystore release.jks -alias eclipse -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=$NAME, O=Eclipse"

cat > keystore.properties <<PROPS
storeFile=$(pwd)/release.jks
storePassword=$PASS
keyAlias=eclipse
keyPassword=$PASS
PROPS
chmod 600 keystore.properties release.jks
base64 -w0 release.jks > keystore-base64.txt 2>/dev/null || base64 -i release.jks | tr -d '\n' > keystore-base64.txt

cat <<MSG

Done. Now, on GitHub: your repository → Settings → Secrets and variables → Actions → New repository secret:

  KEYSTORE_BASE64          the whole contents of keystore-base64.txt
  SIGNING_STORE_PASSWORD   the password you just chose
  SIGNING_KEY_PASSWORD     the same password
  SIGNING_KEY_ALIAS        eclipse

Then delete keystore-base64.txt, and back up release.jks and the password somewhere safe (a password manager).
MSG
