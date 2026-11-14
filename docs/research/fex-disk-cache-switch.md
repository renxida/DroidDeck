# FEX disk cache emptied by switching FEX builds (Nov 14, 2026)

The Slay the Spire 2 prefix's FEX disk cache (`droiddeck-fex-cache/DiskCache/`) held 301.5 MB at 15:33Z and
0.0 MB at the next measured launch, so combat started with every block translated cold.

## Cause

FEX files its cache under `DiskCache/<MachineBucketHash>/`, where the hash covers the cache format version
(`DiskCache.h`, `FormatVersion`) and the host CPU features. At start, `PruneStaleEntries` deletes every other
folder in `DiskCache/`. The format version moves often: 30 in FEX-2610, 42 in FEX-2611, 44 in the upstream tip
Kestrel's FEX is built on. So stock FEX and Kestrel's FEX use different folders on the same phone, and each launch
on one deletes the other's. The likely Nov 14 sequence (from the times, not a log of the deletes): the Kestrel `.22` launch at 15:55 deleted stock's 301.5 MB folder and hung
before writing much to its own; when stock FEX came back (`cf59d49`) it found only that near-empty folder, then
deleted it too. Nothing in the cache was wrong; the switch alone threw it away.

Kestrel's own options do not split the cache: `InitialCodeBufferMB`, `L1CacheEntries`, `SampleHz`,
`NameWindowsMemory` and `TrimIdleThreads` are marked `AffectsCodeGen: false`, so turning them on or off reuses the
same file. Only `VolatileMetadataWholeBlocks` (it changes code) gets its own file, in the same folder.

## Fix (experiment `fex-cache-keep`, off by default)

Reusing one build's cache in another is not safe (the format differs), so the fix keeps both. With
`DROIDDECK_EXP_FEX_CACHE_KEEP=1`, `droiddeck-warmup` sets `FEX_DISKCACHEPRUNESTALEENTRIES=0` (an upstream option,
present in FEX-2611 too) so FEX deletes nothing, and does the pruning itself before each launch: it keeps the
folders of the two FEX builds used last (by newest file time), drops older ones, and drops the second one if both
together pass 1.5 GB. The newest folder always stays. The session log line lists what it kept and deleted.

## Code buffer and malloc caps

Not changed. FEX's code buffer is at most 128 MB per buffer (`SharedCodeBufferManager.cpp`, `MAX_CODE_SIZE`);
a larger total is old buffers still held by threads, which `fex-trim` returns after 30 s idle. A 64 MB cap would
flush and retranslate more often, which costs CPU in exactly the scene that was CPU-bound. Worth trying only after
`fex-cache-keep` and `fex-trim` are on, when a refill reads the disk cache instead of translating.
