# Google Play Store Launch Checklist for Soltini

This guide outlines every step required to successfully publish Soltini on the Google Play Store without policy rejections.

---

## 1. Dual-Flavor Architecture

Soltini is configured with two build flavors in pp/build.gradle.kts:

| Flavor | Target Audience | Permissions / Capabilities | Output Artifact |
| :--- | :--- | :--- | :--- |
| **playstore** | Google Play Store | 100% Policy-compliant. Strips SMS, Call Logs, Termux, and broad package visibility. Calls/SMS delegate to system apps via Intent. | Android App Bundle (.aab) |
| **standalone** | GitHub / Direct APK | Full-power autonomous assistant with Termux, direct calling/SMS permissions, and complete device automation. | APK (.apk) |

---

## 2. Generating Your Upload Keystore

Google Play requires your release .aab to be signed with an upload keystore. Run the following command in your terminal (JDK keytool must be in PATH):

```bash
keytool -genkey -v -keystore soltini-upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

Save `soltini-upload-key.jks` in a secure location.

To build the signed release bundle, provide your keystore details via environment variables or Gradle properties:
```powershell
$env:KEYSTORE_PATH = "path/to/soltini-upload-key.jks"
$env:STORE_PASSWORD = "your_keystore_password"
$env:KEY_PASSWORD = "your_key_password"
```

---

## 3. Building the Play Store Bundle (`.aab`)

Run the Gradle command to produce the release bundle:

```bash
./gradlew bundlePlaystoreRelease
```

The output bundle will be generated at:
```
app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab
```
This is the file you upload to Google Play Console.

---

## 4. Google Play Console Setup & Declarations

### A. Hosting the Privacy Policy
Google Play mandates a live, public HTTPS URL for your privacy policy.
* Upload docs/PRIVACY_POLICY.md to GitHub Pages, your web server, or Notion/GitBook.
* Copy the live link into **Play Console > App Content > Privacy Policy**.

### B. Generative AI Policy Declaration
Under **App Content > Generative AI**:
* Declare that Soltini uses generative AI (Gemini Live API) to answer user questions and perform tasks.
* Confirm that:
  1. The app includes an in-app reporting mechanism for users to report offensive AI output (implemented via the warning/report icon on chat bubbles).
  2. Offensive content generation is restricted.

### C. Data Safety Questionnaire Answers
When filling out the **Data Safety** section in Google Play Console:
1. **Audio recording:**
   * Is collected: **Yes**
   * Is shared: **Yes** (streamed to Google Gemini API for voice interaction)
   * Ephemeral processing: **Yes** (real-time voice streaming)
   * Encrypted in transit: **Yes** (TLS/HTTPS/WSS)
   * User can request deletion: **Yes** (via Clear Memory in settings)
2. **Personal Info (Contacts, if used):**
   * Processed on-device to match names to phone numbers for voice calls.
3. **Location (Coarse/Fine, if used):**
   * Used for local voice queries (weather, directions). Not tracked in the background.

### D. Accessibility Service Declaration & Video Submission
Google Play strictly requires a video demonstration for any app using the Accessibility Service:
1. Create a 1-to-2 minute screen recording showing:
   * The user opening Soltini and tapping on Accessibility Service.
   * The prominent disclosure dialog appearing on screen explaining why the permission is needed.
   * The user enabling the service in Android Settings.
   * A quick demonstration of a voice command utilizing accessibility (e.g. \Open YouTube and search music\).
2. Upload this video to YouTube as **Unlisted** and paste the link into the Play Console declaration form.

---

## 5. The 20-Tester / 14-Day Closed Testing Requirement

If your Google Play Developer account was created after **November 13, 2023**:
1. Google mandates running a **Closed Test** with at least **20 testers**.
2. Testers must opt into your closed test track via Google Play.
3. The closed test must remain active continuously for at least **14 days**.
4. After 14 days, the button to **Apply for Production Access** will unlock in Play Console.
5. Google will review your production request (takes 2–7 business days).

---

## 6. Sideload / GitHub Releases

Whenever you want to release the unrestricted version of Soltini for yourself or GitHub:
`ash
./gradlew assembleStandaloneRelease
`
The APK will be at:
`
app/build/outputs/apk/standalone/release/app-standalone-release.apk
`
