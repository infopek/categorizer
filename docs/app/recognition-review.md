# Recognition review

The recognition review keeps the user in control of the identity saved to the album. Recognition runs locally and presents ordered suggestions as candidates, not guaranteed facts. The interface intentionally does not show raw model scores as confidence percentages because those values are not necessarily calibrated or meaningful to users.

## States and actions

- **Running:** explains that recognition is happening locally on the device.
- **Ranked candidates:** selects the first suggestion by default and lets the user choose another before confirming.
- **Uncertain:** clearly states that the result is uncertain while preserving the ranked choices.
- **Unsupported:** explains that the species is not supported yet and opens manual entry directly.
- **Error:** shows the engine's user-safe message and offers retry only for recoverable errors.
- **Manual correction:** requires a user-facing name; scientific name, alternate names, and identity notes are optional.
- **Cancel:** leaves review without saving and removes the unconfirmed managed image.

Candidate confirmation and valid manual entry both produce an album draft. `RecognitionEntrySaver` serializes save attempts and remembers completed result IDs, so repeated or concurrent confirmation creates exactly one album entry.

The Android application runs the bundled recognition model locally and reports model or inference failures honestly instead of simulating recognition.

## Device evidence

The fixture-driven Android UI tests cover ranked, uncertain, unsupported, manual correction, and recoverable-error behavior. Screenshots captured on the target Samsung S20 FE are stored alongside this document:

- [Ranked suggestions](screenshots/ui-003/ranked.png)
- [Uncertain result](screenshots/ui-003/uncertain.png)
- [Unsupported result](screenshots/ui-003/unsupported.png)
- [Manual correction](screenshots/ui-003/manual-correction.png)

Run the focused device suite with:

```bash
./gradlew :apps:androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=categorizer.app.RecognitionReviewScreenTest \
  --offline
```
