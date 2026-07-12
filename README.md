# kotoba-lang/org-aomedia-av1

Zero-dep portable `.cljc` AV1 (AOMedia Video 1, AV1 Bitstream & Decoding
Process Specification) bitstream **framing + symbol decoder primitives**.
Named `org-aomedia-av1` (not `org-iso-*`/`org-ietf-*`) because AV1 is
published by the Alliance for Open Media, not ISO/IEC or the IETF -- see
`kotoba-lang/org-iso-h264`'s README for the sibling naming rationale.

## Scope (Phase 0 + Phase 1, now with real pixel reconstruction)

This is **Phase 0/1** of AV1 support per `com-junkawasaki/root` ADR-2607122000
(`90-docs/adr/2607122000-utsushi-pixel-codec-r05-cljc-datomic.md`) Migration
step 9. Phase 0/1 started as the foundation layer *before* pixel
reconstruction (OBU/header/bool-decoder/partition-tree framing), mirroring
how `org-iso-h264` started with NAL/SPS/PPS framing before intra pixel
decode was added -- **this phase's continuation now adds that pixel
reconstruction**, at `org-iso-h264`'s equivalent first-pixel-decode
milestone: real bit-exact luma reconstruction against real encoded data,
deliberately scoped narrow (see `av1.decode-block`'s namespace docstring for
the exact boundary: BLOCK_32X32 leaves (single- or, for the V_PRED/H_PRED
mode-coverage extension below, real multi-leaf) / TX_32X32 / DCT_DCT /
DC_PRED+V_PRED+H_PRED / luma-only). AV1's spec is far larger than H.264's, so
per the ADR this repo does **not** attempt broad common-code sharing with
H.264 -- only `codec-primitives`'s narrow generic shapes
(`BlockTransform`/`QuantScale` protocols, `scan`/`unscan`) are candidates
for reuse; this phase still doesn't lean on them (av1.transform/av1.tables
implement AV1's own dequant/DCT/scan tables directly, since AV1's are
structurally different enough from H.264's that a shared abstraction would
have been leakier than just implementing both natively -- see
`codec-primitives`'s README for the same conclusion reached from the H.264
side).

Implemented, transcribed field-for-field from the spec (not reconstructed
from memory -- see each namespace's docstring for the exact spec section
and source snapshot used: `AOMediaCodec/av1-spec` master,
`06.bitstream.syntax.md`/`07.bitstream.semantics.md`/`08.decoding.process.md`/
`09.parsing.process.md`/`10.additional.tables.md`/`04.conventions.md`,
fetched 2026-07-12 for the framing namespaces, 2026-07-13 for the pixel-
reconstruction namespaces added below):

| ns | role |
|---|---|
| `av1.bitreader` | MSB-first bit reader + descriptor primitives: `f(n)`, `uvlc()`, `le(n)`, `leb128()`, `su(n)`, `ns(n)`, `byte_alignment()`, `skip-bits` (position-only advance, for `exit_symbol()`) |
| `av1.obu` | `obu_header()` / `obu_extension_header()` / the top-level OBU loop -- every OBU is self-delimiting via `leb128 obu_size`, so unparsed payload can always be skipped byte-exactly to the next OBU |
| `av1.sequence-header` | full `sequence_header_obu()`: profile/still-picture/timing-info/decoder-model-info/operating-points/frame dimensions/`color_config()` |
| `av1.frame-header` | **the full `uncompressed_header()`**, intra frames only (`KEY_FRAME`/`INTRA_ONLY_FRAME`): frame_type/show_frame/showable_frame, frame size (`frame_size()`/`superres_params()`/`render_size()`), `tile_info()`, `quantization_params()`, `segmentation_params()`, `delta_q_params()`/`delta_lf_params()`, the `CodedLossless`/`AllLossless` computation, `loop_filter_params()`, `cdef_params()`, `lr_params()`, `read_tx_mode()`, `frame_reference_mode()`/`skip_mode_params()`/`global_motion_params()` (all zero-bit passthroughs since FrameIsIntra is always 1 in this phase's scope), `reduced_tx_set`, and `film_grain_params()`. Also now carries `use-128x128-superblock`/`mono-chrome`/`num-planes`/`subsampling-x`/`subsampling-y` through to the top-level returned map (bugfix, see below) |
| `av1.bool-decoder` | the AV1 "Symbol decoder" (daala-derived non-binary arithmetic coder, spec 8.2): `init_symbol`/`read_bool`/`read_literal`/`read_symbol`/`exit_symbol` with CDF adaptation. Wholly separate implementation from H.264's CAVLC/CABAC (`h264.cavlc`) -- different coding scheme entirely |
| `av1.tile-group` | `tile_group_obu()`'s entry (tile start/end, per-tile `init_symbol`/`exit_symbol`) + `decode_partition()` (spec 5.11.4) fully implemented: real default CDF tables (`Default_Partition_W8/W16/W32/W64/W128_Cdf`), real `partition`/`split_or_horz`/`split_or_vert` context derivation (AvailU/AvailL via a MiSizes grid this namespace maintains), real bool-decoder symbol reads -- down to every leaf partition. `decode_block()` is now called at every leaf **when the caller supplies** an injectable `:decode-block-fn` (see `av1.decode-block` below) -- existing callers that don't supply one keep the original "leaf recorded, no further bits consumed" behavior this namespace has always documented |
| `av1.tables` | AV1 constant tables for coefficient decode/dequant/inverse transform/intra prediction: `Dc-Qlookup-8bit`/`Ac-Qlookup-8bit` (8-bit dequant lookup), `Cos128-Lookup`, `Default-Scan-32x32`, `Intra-Mode-Context-Dc-V-H`/`MAX_ANGLE_DELTA`/`Default-Angle-Delta-Cdf` (mode-coverage extension, see below), and the `Default_*_Cdf` tables (`Skip`/`Intra-Frame-Y-Mode`/`Txb-Skip`/`Eob-Pt-1024`/`Eob-Extra`/`Dc-Sign`/`Coeff-Base-Eob`/`Coeff-Base`/`Coeff-Br`) sliced to this phase's supported scope (TX_32X32 only, luma only -- see namespace docstring) |
| `av1.transform` | dequantization (`dequantize`) + the AV1 inverse DCT (`inverse-dct!`, spec 7.13.2.3's generic butterfly-network algorithm, transcribed in full for n=2..6/TX_4X4..TX_64X64 even though only n=5/TX_32X32 is exercised against real data) + the 2D inverse transform process (`inverse-transform-2d`, row transform -> clip -> column transform) |
| `av1.intra-pred` | DC/V/H intra prediction (`dc-predict`/`v-predict`/`h-predict`, spec 7.11.2.4/7.11.2.5) -- DC's all four haveLeft/haveAbove cases implemented (though the original single-block fixtures only exercise the "neither available" case); V_PRED/H_PRED added in the mode-coverage extension below, both exercised against real multi-leaf data with a genuine avail-above/avail-left neighbor (not just the degenerate no-neighbor fallback) |
| `av1.decode-block` | **decode_block()** (spec 5.11.5) for this phase's narrow validated scope: `intra_frame_mode_info()` (`read_skip`/`read_cdef`/`intra_frame_y_mode`/`intra_angle_info_y`/`filter_intra_mode_info`'s conditional guard), `read_block_tx_size()`, `residual()`/`transform_block()`/`coeffs()` (all_zero/transform_type/eob_pt_1024/eob_extra/coeff_base/coeff_base_eob/coeff_br/dc_sign/sign_bit/golomb, with real per-context CDF persistence+adaptation, including cross-block dc_sign context via AboveDcContext/LeftDcContext), and reconstruction (predict_intra + dequantize + inverse_transform_2d + clip). See its namespace docstring for the exact scope boundary and why each boundary was chosen (frame-level `guard-frame-scope!` throws for out-of-scope streams) |

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
require it). `decode_partition()`'s recursive partition-tree structure IS
bit-exact all the way through `decode_block()` now (when `av1.decode-block`
is wired in as the `:decode-block-fn`), including real MULTI-leaf frames
(mode-coverage extension below), but **only** for the narrow shape
`av1.decode-block`'s namespace docstring specifies -- ADST/FLIPADST/IDTX
transform types, any intra mode other than DC_PRED/V_PRED/H_PRED, any
transform size other than TX_32X32, chroma planes, and segmentation/
delta-Q/delta-LF/screen-content-tools/intra-BC are all explicitly out of
scope and throw `ex-info` rather than silently mis-decoding. Inter
prediction, loop-filter/CDEF/loop-restoration in-loop filtering, and film
grain synthesis remain fully unimplemented (this phase's fixtures disable
all in-loop filtering at the encoder, so the raw reconstruction this phase
produces already matches the reference decoder's output without needing
to implement any of them -- see Validation below).

### Mode-coverage extension: V_PRED / H_PRED (ADR-2607122000 Migration step 9, continued)

The narrow single-leaf/DC_PRED-only milestone above has been extended to
also support `V_PRED` and `H_PRED` (`av1.intra-pred/v-predict`/
`h-predict`, spec 7.11.2.4's directional intra prediction process,
pAngle==90/180 cases), validated against two real **multi-leaf** aomenc
frames (a 64x64-pixel, single-superblock frame forced via
`--min-partition-size=32 --max-partition-size=32` into a real 2x2 grid of
BLOCK_32X32 leaves -- see `test/av1/fixtures.clj` for the exact content
design and `test/av1/decode_block_test.clj` for the bit-exact assertions).
This required two real extensions beyond just adding the prediction math:

- **Real cross-block context**, not the single-leaf shortcuts the
  original milestone could get away with: `intra_frame_y_mode`'s ctx now
  comes from an actual `YModes` grid + `Intra_Mode_Context` mapping
  (`av1.decode-block/read-y-mode`, throwing if the mapped ctx is ever
  something other than `(0,0)`, since only that one
  `Default_Intra_Frame_Y_Mode_Cdf[0][0]` table entry is transcribed), and
  `dc_sign`'s ctx now comes from real `AboveDcContext`/`LeftDcContext`
  tracking (`av1.decode-block/get-dc-sign-ctx`, `record-above-dc!`/
  `record-left-dc!`) instead of the hardcoded 0 that was only exactly
  correct (not an approximation) when a frame could only ever have one
  leaf. Both grids are written across each leaf's whole mi footprint
  (`record-footprint!`), fixing a latent bug where the pre-existing
  `Skips` tracking only wrote a single origin point -- invisible before
  because no earlier fixture had a second leaf to query it.
- **`intra_angle_info_y()`** (`av1.decode-block/read-angle-delta-y`): a
  real `angle_delta_y` symbol read that a first implementation attempt
  incorrectly assumed could be skipped because the fixtures are encoded
  with `--enable-angle-delta=0` -- that flag only biases the *encoder's*
  search, it does not remove the syntax element, which is read
  unconditionally whenever `is_directional_mode(YMode)` is true. Missing
  it desynced every bit after a V_PRED/H_PRED block's mode read, producing
  a smoothly-wrong-but-plausible-looking reconstruction that only a real
  bit-exact comparison against dav1d caught (visually it looked like a
  minor rounding difference, not a bug) -- this is exactly the kind of
  error class this repo's "bit-exact against a real independent decoder,
  no tolerance" validation stance exists to catch. The decoded
  `AngleDeltaY` is asserted (not just assumed) to be `0`, since
  `av1.intra-pred`'s V_PRED/H_PRED implementation only handles pAngle
  exactly 90/180.

Transform size (TX_16X16/TX_8X8 and the ADST/IDTX transform types
`get_tx_set()` can select for them) was considered but not pursued in this
pass, per ADR-2607122000 Migration step 9's guidance to land one narrow,
fully-validated extension rather than spread across both axes.

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

### Pixel reconstruction (`av1.decode-block`, `av1.tables`, `av1.transform`, `av1.intra-pred`)

`av1.decode-block-test` validates the full decode_block() pipeline --
mode_info, read_block_tx_size, coeffs() (CDF-adapted coefficient decode),
dequantization, inverse DCT, DC prediction, reconstruction -- against TWO
REAL **aomenc** (libaom 3.14.1)-encoded 32x32 monochrome keyframes,
comparing this repo's reconstructed luma plane against **`dav1d`'s
independent decode of the same bitstream, bit-exactly (no tolerance)**:

- `keyframe-32x32-gradient.obu`: a smooth two-axis brightness ramp (base
  128 +/- 20 across x, +/- 4 across y, authored as known raw pixel bytes --
  not a lavfi filter -- so the exact input is reproducible). eob=7 (few
  nonzero coefficients).
- `keyframe-32x32-busy.obu`: a busier two-axis sinusoidal-plus-ramp
  pattern, same encoder scope restrictions, eob=67 -- exercises far more of
  the coefficient-context derivation and per-context CDF adaptation within
  the same single TX_32X32 transform block.

Both were encoded with every non-DC_PRED intra mode, every non-NONE/SPLIT
partition type, CDEF/loop-filter/loop-restoration, and TX_MODE_SELECT
disabled at the encoder, so DC_PRED and TX_32X32/PARTITION_NONE are the
**only options the bitstream could contain** -- not merely the likely
outcome for smooth content (see `av1.decode-block`'s namespace docstring
for why TX_32X32 is also the one size for which `transform_type()` is
*structurally* forced to DCT_DCT, zero bits, by `get_tx_set()`):

```sh
aomenc --codec=av1 --limit=1 --passes=1 --end-usage=q --cq-level=32 \
  --monochrome --enable-cdef=0 --enable-restoration=0 --loopfilter-control=0 \
  --enable-filter-intra=0 --enable-smooth-intra=0 --enable-paeth-intra=0 \
  --enable-directional-intra=0 --enable-angle-delta=0 --enable-intrabc=0 \
  --enable-palette=0 --enable-qm=0 --enable-tx64=0 --enable-rect-tx=0 \
  --enable-rect-partitions=0 --enable-ab-partitions=0 --enable-1to4-partitions=0 \
  --enable-tx-size-search=0 --tile-columns=0 --tile-rows=0 \
  --kf-min-dist=1 --kf-max-dist=1 \
  --obu -o keyframe-32x32-gradient.obu keyframe-32x32-gradient.y4m

dav1d -i keyframe-32x32-gradient.obu -o keyframe-32x32-gradient.dav1d.yuv
```

(aomenc/libaom 3.14.1, dav1d 1.5.3, both from Homebrew, generated
2026-07-13; both `.obu` and the corresponding `.dav1d.yuv` golden output
are checked in under `resources/av1/fixtures/` -- see
`test/av1/fixtures.clj` docstrings for exactly how each was produced).
`av1.decode-block-test` additionally checks that the frame really did
decode to the expected single BLOCK_32X32/PARTITION_NONE/TX_32X32 leaf
shape (not merely that it happened not to throw), and a third test
(`guard-frame-scope-throws-test`) confirms out-of-scope frame headers
(lossless, non-monochrome, non-TX_MODE_LARGEST) are rejected with
`ex-info` rather than silently mis-decoded.

Not yet exercised against real data (see the "Explicitly NOT implemented"
list above): any transform size other than TX_32X32, ADST/IDTX/FLIPADST
transform types, chroma planes, and inter prediction.

### V_PRED / H_PRED (`av1.decode-block-test`, `keyframe-64x64-vpred`/`keyframe-64x64-hpred`)

Validates the mode-coverage extension against TWO REAL aomenc-encoded
64x64 monochrome keyframes -- unlike the 32x32 fixtures above, each is a
real **multi-leaf** frame (one 64x64 superblock forced into a 2x2 grid of
BLOCK_32X32 leaves):

```sh
aomenc --codec=av1 --limit=1 --passes=1 --end-usage=q --cq-level=32 \
  --monochrome --enable-cdef=0 --enable-restoration=0 --loopfilter-control=0 \
  --enable-filter-intra=0 --enable-smooth-intra=0 --enable-paeth-intra=0 \
  --enable-directional-intra=1 --enable-diagonal-intra=0 \
  --enable-angle-delta=0 --enable-intrabc=0 --enable-palette=0 \
  --enable-qm=0 --enable-tx64=0 --enable-rect-tx=0 \
  --enable-rect-partitions=0 --enable-ab-partitions=0 --enable-1to4-partitions=0 \
  --enable-tx-size-search=0 --min-partition-size=32 --max-partition-size=32 \
  --tile-columns=0 --tile-rows=0 --kf-min-dist=1 --kf-max-dist=1 \
  --obu -o keyframe-64x64-vpred.obu keyframe-64x64-vpred.y4m

dav1d -i keyframe-64x64-vpred.obu -o keyframe-64x64-vpred.dav1d.yuv
```

(same aomenc/dav1d versions, generated 2026-07-13; `keyframe-64x64-hpred`
uses identical flags with transposed content -- see `test/av1/fixtures.clj`
docstrings for exactly how each fixture's content was designed to make a
real encoder RD decision, not a hand-picked bit pattern, land on this
exact shape). `--enable-directional-intra=1 --enable-diagonal-intra=0` is
a libaom flag pair that permits V_PRED/H_PRED specifically while keeping
every *diagonal* directional mode (D45..D203) disabled, so `{DC_PRED,
V_PRED, H_PRED}` are structurally the only modes these bitstreams could
contain. `--min-partition-size=32 --max-partition-size=32` forces the
64x64 superblock's partition decision to be a real, normally-signaled
`PARTITION_SPLIT` (not a probabilistic RDO outcome), producing exactly 4
BLOCK_32X32 leaves.

`av1.decode-block-test` confirms (not merely infers) that the real
encoder chose `DC_PRED` for the first three leaves (top-left/top-right/
bottom-left) and `V_PRED`/`H_PRED` respectively for the fourth
(bottom-right) -- i.e. this repo observed which mode a real encoder
picked rather than assuming it -- and that the reconstructed 64x64 luma
plane is bit-exact against dav1d's independent decode of the same
bitstream. This is also the regression test for the `intra_angle_info_y()`
bug this extension's own development surfaced (see the mode-coverage
section above): the bitstream desync that bug caused was invisible in a
plain diff-count check (many pixels were "close" -- off by 1-7 levels in
a smooth, plausible-looking pattern) and was only caught by requiring
exact equality against the independent reference decode.

Not yet exercised against real data: any intra mode other than DC_PRED/
V_PRED/H_PRED, a nonzero `AngleDeltaY` (this repo's V_PRED/H_PRED only
handle pAngle exactly 90/180, throwing otherwise), any transform size
other than TX_32X32, ADST/IDTX/FLIPADST transform types, chroma planes,
and inter prediction.

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

To also get real reconstructed pixels (this phase's narrow scope --
BLOCK_32X32/PARTITION_NONE/TX_32X32/DCT_DCT leaves, DC_PRED/V_PRED/H_PRED,
luma only, see `av1.decode-block`'s namespace docstring), supply its
`:decode-block-fn`:

```clojure
(require '[av1.decode-block :as db])

(def decode-block-fn (db/make-decode-block-fn frame-hdr seq-hdr))
;; throws ex-info immediately if frame-hdr/seq-hdr are out of scope

(def result (tg/parse-frame-obu frame-obu seq-hdr {:decode-block-fn decode-block-fn}))
(def tile (first (:tiles (:tile-group result))))
(def luma-plane (:luma-plane (:final-tile-state tile)))
;; => flat row-major (MiCols*4) x (MiRows*4) vector of reconstructed 8-bit
;;    luma samples
```

## Test

```sh
clojure -M:test
```

## Lint

```sh
clojure -M:lint
```
