# Trash and manual deletion

The first implementation (2026-10-21, issue #11) deliberately supports only ordinary,
directly writable local shared storage. TrashStore discovers internal/removable roots
through Environment and StorageVolumes. It never uses root, Shizuku, SAF, or a
copy-and-delete fallback. A plan is computed on IO before confirmation; unsupported
items use their existing direct deletion path. If a planned move later becomes
impossible, report failure and retain the source rather than changing that decision.

Each volume owns .TwigTrash with a version marker and .nomedia. UUID entry directories
contain info.json and data. Write and sync metadata before moving the source. Discover
trash only on currently available storage roots, without persisting volume paths in
application preferences or retaining notices for unplugged media. Recovery metadata
is self-contained on the volume; reattachment or an app reinstall can rediscover it.
A crash before a move or after restore may leave metadata without a payload; that is
not a deleted file. A payload with damaged metadata remains visible with its physical
path and cannot be restored using an untrusted destination. Do not adopt existing
unmarked directories or follow symlink buckets/entries.

Restores validate the original parent against the same volume, refuse an existing
name (including dangling symlinks), and require confirmation before recreating missing
parents. Both move directions use renameat2(RENAME_NOREPLACE), avoiding the race between
a Java existence check and renameTo. The small JNI helper shares the existing twigpty
library; unsupported kernels/filesystems fail without an overwrite/copy fallback. Pass
actual UTF-8 bytes across JNI, since GetStringUTFChars breaks supplementary filenames. Keep metadata until a purge has removed the payload successfully. Recursive
purge uses lstat, never following symlink targets. Empty only the confirmed entry
snapshot, so concurrent new trash entries survive. All TrashStore mutations share a
process lock. External filesystem changes can still make an operation fail; revalidate
at execution and do not delete on failure.

DeleteUi is shared by the pane, space map, image viewer, directory compare, and mirror
sync's target-only deletions. Move/compress source cleanup remains part of those
operations, not a second deleted-file copy. Read-only backends and failed compare
listings keep their existing protections. TrashFileSystem is a read-only virtual source
that expands directly in the pane, behind the main app unlock gate. The tree preserves
deleted folder hierarchies and lists each directory only when expanded. There is no
flat display mode. Listings do not follow symlinks. Hidden deleted files remain visible
regardless of the ordinary hidden-file preference. TrashDialogs provides restore/purge
and root-level empty actions, reads on IO, and dismisses dialogs when the pane stops.
Deleted entries also offer read-only inspection/export (properties, copy, compression,
clipboard, file opening/sharing and directory search/refresh/space map). Keep rename,
move, new-file and ordinary delete out of that menu. Properties use the entry metadata
for the original location and deletion date, the payload for its current location and
actual modification time, and retain directory counts/media details. XFile.lastModified
also uses the payload mtime so copying or packing retains the original timestamp.
Tree rows display only the deletion date, read from TrashFileSystem's listing cache
without filesystem IO during binding; do not repurpose XFile.lastModified for display. Damaged records
still expose the payload location and permit copying without trusting a restore path.
Symlinks expose properties/restore/purge only: never follow them for content or hashes.
Nested restore/purge keeps siblings and the entry recovery record; validate original
ancestors before creating missing parents, too. Disabling trash hides its tree root
and clears its expansion/current selection, without deleting any recovery data.
Re-enabling shows the root again and permits recovery. Include trashEnabled in
Prefs.uiSignature so returning from settings updates both panes. DeleteUi shows
a checked-by-default Use trash checkbox when enabled and eligible. Deletion dialogs
use a red delete icon/title and only a filename or selected count; toggling the checkbox
does not expand the copy. The same compact dialog applies to purge/empty actions. Settings explain that recycling
is limited to directly writable local shared storage, excluding network and
privileged/SAF-only paths. A resumed pane refreshes expanded trash directories.

TrashStoreTest exercises real filesystem bytes and inode identity, interrupted moves,
name conflicts, missing parents, symlinks, damaged records and unplugged volumes on
API 24 and 34. Robolectric's default lstat invents stat results for missing paths and
follows symlinks; TrashOsShadow uses host Unix attributes for these tests instead.
TrashUiTest exercises cancellation, compact single/mixed confirmation, checkbox routing and restore.
Device UI verification is still user-operated, as required by AGENTS.local.md.

Run `python3 tools/test_trash_moves.py` for the production JNI source on Linux.
The source was compiled and exercised on the host with two concurrent moves to one
destination: only one wins and the losing source survives. A native probe on the test
phone also verified no-overwrite, emoji filenames and directory moves on shared storage.
This verifies filesystem behavior, not a user walkthrough of the Android UI.

Review regressions (2026-10-21): comparison scans exclude local .TwigTrash directories
with a Twig format marker, on either side. Otherwise a later mirror sync sees its own
recovery store as a target-only extra and permanently deletes it, even with Use trash
checked. An unmarked same-named ordinary directory keeps normal compare/sync behavior.
TrashFileSystem.list returns only real payloads; empty/damaged notices are nonselectable
pane action rows, not XFile placeholders that corrupt counts and copy plans. Carry
notices through Listing and include changes in quiet-refresh equality checks.
Date sorting in the trash uses cached deletion dates for files and directories via
SortSpec's date accessor, while XFile.lastModified remains the actual payload mtime.
