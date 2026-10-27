# Blob metadata and media preview

Status: future plan; not implemented. Description only.

## Goal

Make `#/blobs` a useful file browser rather than a list of opaque IDs. Show each blob's format, size and storage date, and let users play supported audio without manually downloading and opening it elsewhere.

The existing styled rows, Download buttons and transfer/error feedback remain the starting point.

## Metadata

Store metadata alongside every completed blob:

- **ID:** retain the existing `BlobId`; metadata does not change blob identity.
- **Format:** a normalized MIME type, with a friendly display label such as WAV, MP3 or PDF. Distinguish uploader-provided types from server-verified types; do not treat a filename extension as proof of format. Unknown content remains `application/octet-stream`.
- **Size:** the exact stored byte count, verified by storage rather than trusted from an upload declaration.
- **Stored at:** a server-assigned UTC timestamp for the first successful persistence of the blob. This is not the original file's creation date or a filesystem modification timestamp.
- **Original filename (optional):** a display/download hint, never a storage path or HTML. Keep the ID as the fallback name.

Metadata and bytes become visible together only after successful completion. Aborted, expired or partial uploads must not leave listed metadata. All blob-writing paths, including dictation and non-browser producers, should follow the same rules.

Existing blobs remain readable and downloadable. Their size can be recovered from storage; unknown format, filename or storage date must be shown honestly rather than invented. A migration or lazy backfill must not load every blob's bytes just to list files.

## Storage and API

1. Define shared metadata and listing-summary models without putting blob bytes into listing responses.
2. Persist metadata consistently in the supported storage implementations, including a documented policy for legacy blobs and incomplete metadata records.
3. Allow uploads to supply optional filename and MIME hints; validate and bound those values server-side, while assigning size and storage time authoritatively.
4. Expose metadata summaries through JSON-RPC. Preserve compatibility with ID-only `blobs.list` callers through a new or explicitly versioned response rather than silently changing the existing result shape.
5. Keep chunked blob reads and downloads working for both new and legacy records. Use the existing access/capability checks and transfer limits; do not introduce a separate unrestricted HTTP media endpoint.

## Blobs page UI and UX

- Show a filename or friendly format label as the primary title, with the full, selectable blob ID as secondary information.
- Group format, human-readable size and storage date into a compact metadata line. Make exact bytes and the full timestamp available in accessible details; display dates in the user's local timezone with an explicit timezone in the full value.
- Use clear fallback labels such as “Unknown format” and “Date unavailable”. Do not imply that unknown dates are recent.
- Keep Download as an explicit action. Add search by name/ID and format filters, plus sorting by date or size; handle missing metadata consistently.
- Retain loading, empty, disconnected and request-failure states. Distinguish an empty library from no matching filter results.
- Keep errors local to the affected item and show them only when an operation fails. Long names and IDs must wrap without horizontal scrolling.
- Use a compact desktop layout and stacked mobile rows, with visible focus states, labeled actions and readable contrast.

## Audio playback

- For audio MIME types, offer an explicit **Load audio** action. Do not download audio automatically when the list appears, and never autoplay.
- On request, load the bytes through the existing bounded JSON-RPC read path and create a local object URL with the validated MIME type. This first version buffers the complete file; it is not streaming playback.
- Once ready, show native browser audio controls for play/pause, seeking, elapsed time, duration and volume where supported by the browser. MIME classification alone does not guarantee codec support.
- Show loading feedback and prevent duplicate loads. Unsupported codecs, oversized files and transfer failures get a clear inline explanation, while Download remains available under its existing limits.
- Limit retained previews, pause other audio when playback starts, and stop playback and release object URLs/buffers on replacement, navigation or unmount. Ignore late transfer results after a preview is discarded.
- Treat playback as separate from message text-to-speech and microphone recording. Define coordination so starting recording stops blob playback; never request microphone permission to play a blob.
- Do not embed arbitrary blob content as HTML or active document previews. Other media preview types are outside this initial plan.

## Delivery and acceptance

Implement storage/models and legacy compatibility first, then protocol support, metadata presentation, and finally audio previews.

Validate accurate size/date values, metadata persistence across restarts, upload completion/abort behavior, legacy fallbacks and protocol compatibility. Browser checks should cover desktop/mobile layouts, keyboard navigation, filtering/sorting, successful downloads, supported and unsupported audio, transfer limits, retry behavior, no autoplay, and playback/resource cleanup when leaving the page.
