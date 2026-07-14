# Recognition orchestration

`RecognitionCoordinator` is the shared application boundary between presentation code and a
replaceable `RecognitionEngine`. It owns no Android, image-decoding, database, or ONNX type.
Production can replace the deterministic fixture engine with an ONNX adapter without changing
the coordinator, presentation states, or save-draft contract.

## State transitions

```text
Idle -> Running(source)
Running -> Candidates | Uncertain | Unsupported | Error | Idle(cancelled)
Error -> Running(retry)
Completed -> Running(new source)
Any active state -> Idle(cancel/dispose)
```

Submitting the same source while it is already running is ignored. A different submission or
retry cancels the current job and increments a request token. Completion changes state only when
its token is still current, so even an engine that does not promptly cooperate with cancellation
cannot publish stale output. `dispose()` permanently rejects later submissions and completions.

Candidate order, rank, score, model version, and inference duration pass through unchanged; the
application does not reinterpret scores as confidence. Candidate confirmation and a
`USER_CONFIRMED` manual correction create a `RecognitionSaveDraft`. The coordinator has no album
repository and never persists the draft, sends training feedback, or changes the installed model.

Run the deterministic state and concurrency suite with:

```shell
./gradlew :shared:application:testDebugUnitTest
```
