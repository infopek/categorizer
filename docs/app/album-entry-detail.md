# Album entry detail and editing

Selecting an album card opens its saved photo, confirmed identity, album date, favorite state, and personal notes. Internal entry IDs and storage paths are never displayed.

Editing uses a local draft. Make and model are required; generation, approximate years, and notes are optional. Canceling discards the draft without calling the repository. A successful save marks the identity as user-confirmed, updates observable album queries, and persists across repository and application restarts. Save failures remain visible without discarding the draft.

Favorite changes use the same persistent update path. Consequently, edited identity text, notes, and favorite status immediately affect album search and filters.

Deletion requires a separate confirmation explaining that both the album entry and its managed photo copy are removed. The database entry is deleted first; the Android layer removes the returned managed-image reference only after that succeeds. Concurrent deletion and missing entries produce a recoverable unavailable state. A missing managed image displays the existing `No photo` fallback while keeping the entry maintainable or deletable.

## Device evidence

- [Entry detail and missing-photo fallback](screenshots/ui-004/detail.png)
- [Identity and notes editing](screenshots/ui-004/editing.png)
- [Deletion confirmation](screenshots/ui-004/delete-confirmation.png)

Run the focused device suite with:

```bash
./gradlew :apps:androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=categorizer.app.AlbumEntryDetailScreenTest \
  --offline
```
