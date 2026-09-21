---
trigger: model_decision
---

# Soltini App — Gemini Live API Constraints (DO NOT CHANGE)

The following design choices in `GeminiLiveManager.kt` are INTENTIONAL and must NOT be modified:

## 1. Model String
`MODEL_STRING = "gemini-3.1-flash-live-preview"` is the correct, intentional model for this app's BidiGenerateContent live voice session.
- Do NOT replace with `gemini-2.0-flash-exp` or any other model.
- Always search current Google AI documentation before concluding a model name is invalid.

## 2. Audio Payload Format (`sendToWs`)
The correct format for this app is:
```json
{
  "realtimeInput": {
    "audio": {
      "data": "<base64_pcm>",
      "mimeType": "audio/pcm;rate=16000"
    }
  }
}
```
- Do NOT change to `mediaChunks` array format.
- The `audio` object with `data` and `mimeType` is the correct schema for `gemini-3.1-flash-live-preview`.

## 3. Tool Response Key (`sendToolResponse`)
The correct response structure is:
```json
{
  "toolResponse": {
    "functionResponses": [
      {
        "id": "...",
        "name": "...",
        "response": {
          "output": { ... }
        }
      }
    ]
  }
}
```
- Do NOT change `output` to `result`. `output` is intentional for this app.

## General Rule
When working on `GeminiLiveManager.kt`, ALWAYS search the web for the current Gemini Live API documentation rather than relying on internal training data, which may be outdated.