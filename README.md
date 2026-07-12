# kotoba-lang/org-aomedia-av1

Zero-dep portable `.cljc` AV1 (AOMedia Video 1, AV1 Bitstream & Decoding
Process Specification) bitstream **framing + symbol decoder primitives**.
Named `org-aomedia-av1` (not `org-iso-*`/`org-ietf-*`) because AV1 is
published by the Alliance for Open Media, not ISO/IEC or the IETF -- see
`kotoba-lang/org-iso-h264`'s README for the sibling naming rationale.

## Scope (Phase 0 + Phase 1)

This is **Phase 0/1** of AV1 support per `com-junkawasaki/root` ADR-2607122000
(`90-docs/adr/2607122000-utsushi-pixel-codec-r05-cljc-datomic.md`) Migration
step 9: the foundation layer *before* pixel reconstruction, mirroring how
`org-iso-h264` started with NAL/SPS/PPS framing before intra pixel decode
was added. AV1's spec is far larger than H.264's, so per the ADR this repo
does **not** attempt broad common-code sharing with H.264 -- only
`codec-primitives`'s narrow generic shapes (`BlockTransform`/`QuantScale`
protocols, `scan`/`unscan`) are candidates for reuse, and this phase doesn't
need any of them yet (no transform/quantization-table work has started).

Implemented, transcribed field-for-field from the spec (not reconstructed
from memory -- see each namespace's docstring for the exact spec section
and source snapshot used: `AOMediaCodec/av1-spec` master,
`06.bitstream.syntax.md`/`07.bitstream.semantics.md`/`08.decoding.process.md`/
`09.parsing.process.md`/`10.additional.tables.md`/`04.conventions.md`,
fetched 2026-07-12):

| ns | role |
|---|---|
| `av1.bitreader` | MSB-first bit reader + descriptor primitives: `f(n)`, `uvlc()`, `le(n)`, `leb128()`, `su(n)`, `ns(n)`, `byte_alignment()`, `skip-bits` (position-only advance, for `exit_symbol()`) |
| `av1.obu` | `obu_header()` / `obu_extension_header()` / the top-level OBU loop -- every OBU is self-delimiting via `leb128 obu_size`, so unparsed payload can always be skipped byte-exactly to the next OBU |
| `av1.sequence-header` | full `sequence_header_obu()`: profile/still-picture/timing-info/decoder-model-info/operating-points/frame dimensions/`color_config()` |
| `av1.frame-header` | **the full `uncompressed_header()`**, intra frames only (`KEY_FRAME`/`INTRA_ONLY_FRAME`): frame_type/show_frame/showable_frame, frame size (`frame_size()`/`superres_params()`/`render_size()`), `tile_info()`, `quantization_params()`, `segmentation_params()`, `delta_q_params()`/`delta_lf_params()`, the `CodedLossless`/`AllLossless` computation, `loop_filter_params()`, `cdef_params()`, `lr_params()`, `read_tx_mode()`, `frame_reference_mode()`/`skip_mode_params()`/`global_motion_params()` (all zero-bit passthroughs since FrameIsIntra is always 1 in this phase's scope), `reduced_tx_set`, and `film_grain_params()` |
| `av1.bool-decoder` | the AV1 "Symbol decoder" (daala-derived non-binary arithmetic coder, spec 8.2): `init_symbol`/`read_bool`/`read_literal`/`read_symbol`/`exit_symbol` with CDF adaptation. Wholly separate implementation from H.264's CAVLC/CABAC (`h264.cavlc`) -- different coding scheme entirely |
| `av1.tile-group` | `tile_group_obu()`'s entry (tile start/end, per-tile `init_symbol`/`exit_symbol`) + `decode_partition()` (spec 5.11.4) fully implemented: real default CDF tables (`Default_Partition_W8/W16/W32/W64/W128_Cdf`), real `partition`/`split_or_horz`/`split_or_vert` context derivation (AvailU/AvailL via a MiSizes grid this namespace maintains), real bool-decoder symbol reads -- down to every leaf partition. `decode_block()` (mode info/residual/coefficient decode) is explicitly NOT called; see the namespace docstring's correctness caveat about what's bit-exact and what isn't once a leaf is reached |

**Explicitly NOT implemented (next phase)**: inter frames (`frame_type ==
INTER_FRAME`) and `show_existing_frame == 1` (both need cross-frame
reference-frame state this phase doesn't track -- `av1.frame-header/parse`
throws `ex-info` rather than silently mis-parsing them), `segmentation_params()`
when `primary_ref_frame != PRIMARY_REF_NONE` (needs cross-frame segmentation-
map state -- structurally unreachable for the intra frames this phase
supports, since `primary-ref-frame` is always forced to `PRIMARY_REF_NONE`
when `frame-is-intra?`, but throws rather than silently mis-parsing if that
invariant is ever violated), `temporal_point_info()` (needs
`frame_presentation_time_length_minus_1` plumbing not carried; throws if a
stream's `decoder_model_info_present_flag && !equal_picture_interval` would
require it), `decode_block()` and everything downstream of it (mode info,
residual, coefficients, DCT/ADST transform kernels, intra prediction, actual
loop-filter/CDEF/loop-restoration pixel processing, film grain synthesis).
`decode_partition()`'s recursive partition-tree structure IS implemented
(see `av1.tile-group` above), but only bit-exact up to the first leaf
partition per superblock, since nothing after that point calls
`decode_block()`. All of the above is real pixel reconstruction, deliberately
out of scope for this phase.

## Validation

`av1.obu-test`/`av1.sequence-header-test`/`av1.frame-header-test` validate
against a **real SVT-AV1-encoded stream** (`resources/av1/fixtures/
keyframe-64x48.obu`, checked in), generated with:

```sh
ffmpeg -y -f lavfi -i "testsrc=size=64x48:rate=1" -frames:v 1 \
  -pix_fmt yuv420p -c:v libsvtav1 -f obu keyframe-64x48.obu
```

(ffmpeg 8.1.1, `libsvtav1` encoder -- `-f obu` emits the spec's "low
overhead bitstream format" directly, no container/length-stripping
needed). The decoded `max_frame_width`/`max_frame_height` (sequence
header) and `frame_width`/`frame_height` (frame header, via a completely
different syntax path: `frame_size()`/`superres_params()`/
`compute_image_size()`) both independently come out to exactly 64x48 --
the same "real encoder output, cross-checked dimensions" validation level
as `org-iso-h264`'s `h264.sps-test` against real libx264 output.

`av1.bool-decoder-test` validates the symbol decoder against **hand-
derived bit vectors**: the spec's `init_symbol`/`read_symbol` arithmetic is
executed by hand (documented step by step in the test namespace's
docstring) for two contrasting inputs (all-zero bits, all-one bits) with
`sz=2` bytes, since a real encoded tile requires the full pixel-
reconstruction pipeline this phase doesn't build. Real-data validation of
the bool decoder (through real CDF tables and real symbol reads, just not
yet through `decode_block()`) now happens via `av1.tile-group-test`, below.

`av1.frame-header-test` additionally validates the `segmentation_params()`
through `film_grain_params()` continuation against `keyframe-256x192.obu`
(see below), including an **independent cross-check against `aomdec`** (the
official AOM reference decoder, libaom 3.14.1): `aomdec
--framestats=out.csv keyframe-256x192.obu` reports `qp,116` for this frame,
exactly matching this repo's decoded `:base-q-idx`.

`av1.tile-group-test` validates `tile_group_obu()`/`decode_partition()`
against two more real SVT-AV1-encoded streams:

- `keyframe-256x192.obu` (4x3 grid of 64x64 superblocks, `testsrc2` content
  so the encoder actually produces varied partition splits):
  ```sh
  ffmpeg -y -f lavfi -i "testsrc2=size=256x192:rate=1" -frames:v 1 \
    -pix_fmt yuv420p -c:v libsvtav1 -f obu keyframe-256x192.obu
  ```
  Cross-checked against `libdav1d` (via `ffmpeg -loglevel debug`, a
  completely independent AV1 decoder): `Frame 0: size 256x192 upscaled 256
  render 256x192 subsample 2x2 bitdepth 8 tiles 1x1` matches this repo's
  decoded frame-width/upscaled-width/render-width/render-height/tile-cols/
  tile-rows exactly. Validates that every one of the 12 superblocks'
  `decode-partition` walk completes without throwing or running off the end
  of the tile buffer, and that a real, content-varied encode exercises a
  genuine structural variety of partition types (not just
  `PARTITION_NONE`) through the CDF-table/context-derivation/bool-decoder
  wiring.
- `keyframe-32x32-split.obu` (deliberately sized so `MiRows == MiCols == 8`):
  ```sh
  ffmpeg -y -f lavfi -i "testsrc2=size=32x32:rate=1" -frames:v 1 \
    -pix_fmt yuv420p -c:v libsvtav1 -f obu keyframe-32x32-split.obu
  ```
  With a 64x64 superblock, `decode_partition()`'s top-level call has
  `hasRows = hasCols = false`, which per spec forces `partition =
  PARTITION_SPLIT` with **zero bits/symbols read** -- a deterministic,
  content-independent assertion (unlike the 256x192 tree shape, which past
  the first leaf partition is not bit-exact against the real stream; see
  `av1.tile-group`'s namespace docstring for why).

## Usage

```clojure
(require '[av1.bitreader :as br] '[av1.obu :as obu]
         '[av1.sequence-header :as sh] '[av1.frame-header :as fh]
         '[av1.bool-decoder :as bd] '[av1.tile-group :as tg])

(def r (br/make-reader av1-bytes))
(def o (obu/parse-obu r))                      ; => {:header {...} :obu-size N ...}
(def seq-hdr (sh/parse (:reader-at-payload seq-obu)))
(def frame-hdr (fh/parse (:reader-at-payload frame-obu) seq-hdr))
;; => {:frame-type ... :show-frame ... :frame-width :frame-height
;;     :tile-info {...} :base-q-idx ... :quantization-params {...}
;;     :segmentation-params {...} :loop-filter-params {...} :cdef-params {...}
;;     :lr-params {...} :tx-mode ... :film-grain-params {...} ...}

(def bd-state (bd/init-symbol reader tile-size-bytes))
(def [bit bd-state'] (bd/read-bool bd-state))

;; For a combined OBU_FRAME (frame_header + tile_group in one OBU), the
;; whole frame_obu(sz) -> tile_group_obu(sz) -> decode_partition() walk:
(def result (tg/parse-frame-obu frame-obu seq-hdr))
;; => {:frame-header {...} :tile-group {:tg-start ... :tg-end ...
;;     :tiles [{:tile-row ... :tile-col ... :superblock-partitions [...]}]}}
```

## Test

```sh
clojure -M:test
```

## Lint

```sh
clojure -M:lint
```
