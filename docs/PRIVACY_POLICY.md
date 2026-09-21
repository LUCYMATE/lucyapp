# Privacy Policy for Soltini

**Effective Date:** September 13, 2026  
**Last Updated:** September 13, 2026  

Soltini (" we,\ \our,\ or \us\) is committed to protecting your privacy. This Privacy Policy explains how Soltini collects, uses, and safeguards information when you use our mobile application.

---

### 1. Information We Collect and How We Use It

Soltini is an agentic voice assistant designed to help you interact with your device through conversational voice AI. To deliver these capabilities, the application requests the following permissions with your explicit consent:

#### A. Microphone & Audio
* **Purpose:** Soltini captures real-time microphone audio only when active (or listening for the configured wake word) to process voice commands and converse with you.
* **Processing:** Voice audio chunks are streamed encrypted (TLS/SSL) to Google's Gemini Live API for speech-to-speech comprehension and response generation. Audio is processed transiently and is never sold to third parties.

#### B. Accessibility Service
* **Purpose:** Soltini uses Android's Accessibility API exclusively to perform user-requested device actions on your command, such as opening applications, navigating screens, or clicking buttons.
* **Privacy Guarantees:** 
 * Soltini does NOT collect, store, or log keystrokes, personal messages, passwords, or authentication credentials.
 * Soltini automatically silences and disables accessibility interactions when financial or banking applications are open.
 * You can enable or disable this service at any time in your device's Accessibility Settings.

#### C. Overlay & Floating Window
* **Purpose:** Soltini displays an interactive floating visualizer (Sound Orb or Virtual Avatar) over other applications so you can trigger voice assistance at any time without leaving your current app.

#### D. Notification Listener (Optional)
* **Purpose:** If granted, Soltini can read notifications aloud hands-free when you receive messages or summarize incoming alerts upon request. Notification contents are never transmitted off-device except when explicitly requested by you to generate a voice reply.

#### E. Contacts & Location (Optional)
* **Purpose:** Used on-device to resolve contact names when placing calls or sending messages via your device's native apps, and to provide localized weather or navigation information.

---

### 2. App Master Pause & Complete User Control

Soltini provides a dedicated **Master Pause** feature accessible directly inside the app or from the persistent system notification:
* When **Paused**, all background microphone capture, audio processing, floating overlays, autostart receivers, and background resurrections are completely stopped.
* The app remains completely dormant until you explicitly tap \Resume.\

---

### 3. Third-Party Services & Data Sharing

* **Google Gemini Live API:** Voice audio is processed by Google Cloud / Google AI infrastructure to provide generative AI dialogue under Google's Cloud Security & Privacy standards.
* **Live2D Inc.:** Soltini includes the Live2D Cubism runtime library for 2D avatar rendering. No personal user data is collected or sent to Live2D Inc.
* We do **not** sell, rent, or trade your personal data with any advertisers or data brokers.

---

### 4. Data Retention and Deletion

Soltini stores conversation history and vector memory locally on your device in private application storage. You can delete all conversation transcripts and memory at any time by navigating to **Settings > Memory Vault > Clear Memory** inside the app.

---

### 5. Children's Privacy

Soltini is not directed to individuals under the age of 13. We do not knowingly collect personal information from children.

---

### 6. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. Any changes will be posted on this page with an updated Effective Date.

---

### 7. Contact Us

If you have any questions or feedback regarding this Privacy Policy, please contact us at: 
**Email:** support@soltini.app (or your developer contact email) 
**Developer Website:** https://satishacharya.com / https://github.com/satishacharya03/soltiniapp
