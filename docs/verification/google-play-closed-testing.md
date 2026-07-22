# Google Play closed-testing release

Status: signed candidate prepared; Play developer identity verification and upload pending.

## Secure signing inputs

Release signing is injected only through these environment variables:

- `CATEGORIZER_UPLOAD_KEYSTORE`
- `CATEGORIZER_UPLOAD_KEY_ALIAS`
- `CATEGORIZER_UPLOAD_STORE_PASSWORD`
- `CATEGORIZER_UPLOAD_KEY_PASSWORD`

Keystores and passwords must remain outside Git. Build and record the candidate with:

```bash
./gradlew :apps:androidApp:verifyClosedTestingSigning \
  :apps:androidApp:bundleRelease --console=plain

python3 verification/release/record_closed_testing_candidate.py \
  --aab apps/androidApp/build/outputs/bundle/release/androidApp-release.aab \
  --output verification/release/results/closed-testing-candidate.json
```

## Store declarations

- App name: `Categorizer`
- Application ID: `com.infopek.categorizer`
- Category: education or tools
- Ads: none
- Accounts: none
- Purchases/subscriptions: none
- Data collection or sharing: none
- Recognition: entirely on-device; no remote inference
- Photos: selected or captured only at the user's request, copied into app-private storage, and not uploaded
- Location: not requested or stored
- Telemetry/crash upload: none
- Backup: Android platform backup is disabled; users can explicitly export/import a local archive

Camera capture delegates to the system camera through a scoped content URI. Gallery selection uses
the system picker. The application manifest requests no camera, location, broad media, internet, or
advertising permission.

## Closed-track release notes

> First closed-testing build of Categorizer. Identify supported Austrian butterflies and moths
> entirely offline, review or correct the result, and save sightings in a private local album.
> Includes camera/gallery import, manual subject cropping, search, favorites, notes, and manual
> archive export/import. Automatic subject detection is not included in this build.

## Known qualification limitation

The publisher reports strong classifier accuracy, and model identity, runtime equivalence, device
latency, memory, size, offline behavior, and archive/privacy behavior have been verified. Independent
held-out accuracy remains blocked because the publisher did not release its test-split indices. The
closed test must not be described as having passed that independent gate. Tester feedback should
specifically record incorrect, uncertain, and unsupported-species results.

## Upload and tester evidence

After Google completes developer verification:

1. Finish the Play Console app-content and store-listing forms using the declarations above.
2. Open **Test and release > Testing > Closed testing** and create a track.
3. Upload the exact signed AAB recorded in `closed-testing-candidate.json`.
4. Confirm that Play reports application ID `com.infopek.categorizer`, version code `1`, and the
   expected download/installed-size estimates.
5. Add testers, publish the track, and retain the opt-in link and Play release identifier.
6. Have at least one authorized tester install through Play and record capture/import, offline
   recognition, correction, save/restart, search/edit, export, and import results.
7. For a new personal developer account, retain at least 12 opted-in testers continuously for 14
   days before applying for production access. Production publication is outside the MVP.
