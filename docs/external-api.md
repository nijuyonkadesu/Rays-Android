# External library API

Rays exposes read-only search through `com.skyd.rays.api.IApiService`. Clients use
the installed app's service and granted content URIs; they do not need its database,
models or image-processing libraries. Debug builds use package `com.skyd.rays.debug`;
release builds use `com.skyd.rays`.

## User authorization

In Rays, open **Settings → API (Experimental) → API grant**, enable the API, add the
client's application ID and enable that package's switch. Both settings are checked
for every request. The service verifies that the claimed package belongs to the
actual Binder caller UID before switching threads. Unauthorized requests return
`null`. Package grants use exact, case-sensitive matching.

There is no additional manifest permission. The previously referenced but undeclared
`rays.permission.api` blocked binding; authorization is enforced by the service's
existing user-controlled package allowlist and caller check.

## Connecting and searching

Copy the AIDL contract, retaining its package, method order and license. If using
Android package visibility checks, declare the Rays package in `<queries>`.
Bind explicitly to `ComponentName(raysPackage, "com.skyd.rays.api.ApiService")`.
Call the synchronous Binder methods on a worker, handle service disconnection and
unbind when finished. Avoid automatic retry loops after denial or disconnection.

```kotlin
val response = withContext(Dispatchers.IO) {
    service.searchStickersPage(context.packageName, "cat", 0, 60)
}
```

`keyword` follows Rays' existing title/tag search preferences, including whitespace
and regex settings. Unicode and emoji are passed through unchanged. Results are
ordered by creation time descending, then UUID descending for equal timestamps.
Each database query reads at most the requested page plus one lookahead item.

The response is JSON with `startIndex`, returned `size`, `data` and optional
`nextIndex`. Each data item has `uri`, `sticker`, `tags` and `mimeType`; sticker/tag
fields follow the existing serialized beans. Unknown fields should be ignored.
Request `nextIndex` for the next page and stop when it is absent or `null`.
Do not infer the next offset from the requested size: a response can be shortened
to fit the Binder payload budget.

The maximum requested size is 100, with a 256 KiB UTF-16 JSON budget. Negative
offsets and nonpositive sizes return an empty page. A single oversized record or
unavailable file fails explicitly. Concurrent library edits can shift offset-based
pages; begin a new search to refresh the ordering.

Only returned items receive read URI grants. Open them with `ContentResolver`;
never interpret them as filesystem paths. Turning API access off prevents further
requests but does not revoke content already delivered to a client. No write,
delete or annotation API is added.

The original `searchStickers` transaction and JSON shape (`startIndex`, `size`,
`data` containing `uri`, `sticker`, `tags`) remain available. The paged method is
appended to preserve existing transaction IDs. Older Rays builds do not implement
it; clients should explain that an updated build is required.

## Video library support

MP4/WebM originals follow the existing import, title/tag editing, share, clipboard,
export and backup paths. Coil extracts thumbnails from UUID-named files, and the
full-screen viewer uses platform playback, pausing in the background and releasing
on dismissal. The API returns the actual video MIME type. Image editing pickers
and image-only ML labeling/similarity remain unchanged; videos use manual titles
and tags. No schema migration, scanner or additional service is introduced.

## Validation

`ApiServiceTest` covers caller identity, user grants, exact package matching,
legacy responses, Unicode, paging, response limits and unavailable files.
`VideoLibraryTest` covers MP4/WebM import, original bytes, MIME, tags, share suffixes,
Coil/SAF thumbnails and skipping image-only ML suggestions. Cross-app tests in SAO
also exercise page-scoped URI grants, playback files and worker-based WebM conversion.

For Rays' existing ML-backed Add screen on an emulator advertising `arm64-v8a`,
install the ARM64 split with `adb install --abi arm64-v8a <apk>` to use the emulator's
available native translation. This integration does not remove or change ML dependencies.
