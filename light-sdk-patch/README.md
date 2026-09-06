
# light-sdk-patch

Apply patches to the `light-sdk` submodule at build time.

## Creating/updating a patch

```sh
# Edit the file directly inside the submodule
nvim light-sdk/path/to/File.kt

# Generate diff
git -C light-sdk diff -- <relative/path/to/file> > light-sdk-patch/<name>.patch

# Restore the original submodule state
git -C light-sdk checkout -- <relative/path/to/file>
```

## Patches
- `01-media-session-access`: exposes OS-wide now-playing/playback info (artist, title, position, duration) so the scrobbler has something to read
