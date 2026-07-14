# Album browsing

UI-001 makes the album the Android app's default screen. `AlbumBrowserController` owns an
explicit `AlbumQuery` and maintains separate unfiltered and visible observations so the UI can
distinguish a new collection from a populated collection with no filter matches.

The screen supports free-text search across identity and notes, favorites, identity filters, and
newest/oldest/name sorting. Album cards use stable entry IDs. Repository changes are reflected by
observation without mutating stored entries. Capture/import and entry-detail actions are exposed as
callbacks for the later navigation tasks.

Android thumbnails are decoded off the main thread with power-of-two sampling capped at 512 pixels.
Missing files render a recoverable placeholder. Both entry cards and identity filters use lazy
containers so large collections do not eagerly compose every row or filter.

## Device proof

The UI Automator suite renders deterministic states on the Samsung SM-G780G (Android 13):

- [Empty album](screenshots/ui-001/empty.png)
- [Populated album](screenshots/ui-001/populated.png)
- [No filter matches](screenshots/ui-001/filtered.png)

Run presentation and device verification with:

```sh
./gradlew :shared:application:testDebugUnitTest
./gradlew :apps:androidApp:connectedDebugAndroidTest
```
