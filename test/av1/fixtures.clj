(ns av1.fixtures
  "Shared test fixture loading. `keyframe-64x48.obu` is a REAL AV1 low
   overhead bitstream produced by a real encoder -- not hand-crafted or
   synthetic:

     ffmpeg -y -f lavfi -i \"testsrc=size=64x48:rate=1\" -frames:v 1 \\
       -pix_fmt yuv420p -c:v libsvtav1 -f obu keyframe-64x48.obu

   (ffmpeg 8.1.1 / libsvtav1, generated 2026-07-12 on the machine this repo
   was developed on -- `ffmpeg -codecs | grep av1` shows both the
   libsvtav1 encoder and libdav1d/av1 decoders are available locally, so
   this is a real encoder's real output, matching the H.264 sibling repo's
   `org-iso-h264` validation methodology of parsing genuine libx264
   output). The `-f obu` muxer emits the spec's \"low overhead bitstream
   format\" (5.2) directly with no container -- every OBU is
   self-delimiting via obu_has_size_field/leb128 obu_size, so no IVF/length
   stripping is needed."
  (:require [clojure.java.io :as io]))

(defn- load-resource [path]
  (with-open [in (io/input-stream (io/resource path))]
    (let [baos (java.io.ByteArrayOutputStream.)]
      (io/copy in baos)
      (.toByteArray baos))))

(defn keyframe-bytes []
  (load-resource "av1/fixtures/keyframe-64x48.obu"))

(defn keyframe-256x192-bytes
  "A REAL SVT-AV1-encoded 256x192 keyframe (4x3 grid of 64x64 superblocks,
   testsrc2 content -- enough detail that the encoder actually exercises a
   variety of partition splits, not just PARTITION_NONE everywhere):

     ffmpeg -y -f lavfi -i \"testsrc2=size=256x192:rate=1\" -frames:v 1 \\
       -pix_fmt yuv420p -c:v libsvtav1 -f obu keyframe-256x192.obu

   (ffmpeg 8.1.1 / libsvtav1, generated 2026-07-12). Used to validate
   av1.tile-group/decode-partition against a real multi-superblock tile --
   see test/av1/tile_group_test.clj."
  []
  (load-resource "av1/fixtures/keyframe-256x192.obu"))

(defn keyframe-32x32-split-bytes
  "A REAL SVT-AV1-encoded 32x32 keyframe, deliberately sized so that
   MiRows == MiCols == 8: with a 64x64 superblock (`Num_4x4_Blocks_Wide
   [BLOCK_64X64]` = 16, half = 8), decode_partition()'s top-level call at
   (0,0) always has `hasRows = (0+8) < 8 = false` and `hasCols = (0+8) < 8 =
   false`, which per spec #Decode partition syntax forces `partition =
   PARTITION_SPLIT` unconditionally with ZERO bits/symbols read -- a
   deterministic assertion about av1.tile-group/decode-partition's control
   flow that holds regardless of the tile's actual bitstream content (see
   test/av1/tile_group_test.clj's `decode-partition-forced-split-test`).
   Generated with:

     ffmpeg -y -f lavfi -i \"testsrc2=size=32x32:rate=1\" -frames:v 1 \\
       -pix_fmt yuv420p -c:v libsvtav1 -f obu keyframe-32x32-split.obu

   (ffmpeg 8.1.1 / libsvtav1, generated 2026-07-12)."
  []
  (load-resource "av1/fixtures/keyframe-32x32-split.obu"))

(defn keyframe-32x32-gradient-bytes
  "A REAL aomenc (libaom 3.14.1)-encoded 32x32 MONOCHROME keyframe, Phase 1
   pixel-reconstruction milestone fixture (ADR-2607122000 Migration step 9;
   see av1.decode-block namespace docstring for the exact narrow scope this
   validates against). Content is a smooth two-axis brightness ramp
   (base 128 +/- 20 across x, +/- 4 across y -- authored directly as raw
   8-bit gray pixel bytes wrapped in a minimal y4m header, not via
   `testsrc`/`testsrc2`, so the exact pixel values are known and
   reproducible independent of any lavfi filter version).

   The aomenc invocation deliberately disables every intra tool other than
   DC_PRED (so DC_PRED is the ONLY mode the bitstream could have chosen,
   not merely the most likely one -- see namespace docstring), CDEF/loop
   filter/restoration (so no in-loop filtering needs to be modeled), and
   every non-NONE/SPLIT partition type (so decode_partition()'s recursion
   stays exactly the shape av1.tile-group/av1.decode-block support):

     aomenc --codec=av1 --limit=1 --passes=1 --end-usage=q --cq-level=32 \\
       --monochrome --enable-cdef=0 --enable-restoration=0 \\
       --loopfilter-control=0 --enable-filter-intra=0 --enable-smooth-intra=0 \\
       --enable-paeth-intra=0 --enable-directional-intra=0 \\
       --enable-angle-delta=0 --enable-intrabc=0 --enable-palette=0 \\
       --enable-qm=0 --enable-tx64=0 --enable-rect-tx=0 \\
       --enable-rect-partitions=0 --enable-ab-partitions=0 \\
       --enable-1to4-partitions=0 --enable-tx-size-search=0 \\
       --tile-columns=0 --tile-rows=0 --kf-min-dist=1 --kf-max-dist=1 \\
       --obu -o keyframe-32x32-gradient.obu keyframe-32x32-gradient.y4m

   (aomenc from Homebrew, libaom 3.14.1, generated 2026-07-13). Real-decode
   cross-check: `dav1d -i keyframe-32x32-gradient.obu -o
   keyframe-32x32-gradient.dav1d.yuv` (dav1d 1.5.3, a completely
   independent AV1 decoder implementation) produced the raw 8-bit gray
   32x32 luma plane checked in as `keyframe-32x32-gradient.dav1d.yuv` --
   see `keyframe-32x32-gradient-golden-yuv` below and
   test/av1/decode_block_test.clj for the bit-exact comparison against
   this repo's decoder."
  []
  (load-resource "av1/fixtures/keyframe-32x32-gradient.obu"))

(defn keyframe-32x32-gradient-golden-yuv
  "dav1d's raw 8-bit gray decode of `keyframe-32x32-gradient-bytes` (1024
   bytes, row-major 32x32) -- the independent ground truth
   test/av1/decode_block_test.clj compares this repo's decoder output
   against."
  []
  (load-resource "av1/fixtures/keyframe-32x32-gradient.dav1d.yuv"))

(defn keyframe-32x32-busy-bytes
  "Same aomenc invocation/scope restrictions as
   `keyframe-32x32-gradient-bytes` (see its docstring), but with a busier
   two-axis sinusoidal-plus-ramp brightness pattern (still authored as
   known raw pixel bytes, not via a lavfi filter) so the encoder actually
   emits many more nonzero coefficients (eob=67, vs. 7 for the plain
   gradient fixture) -- exercises far more of av1.decode-block's
   coeff_base/coeff_base_eob/coeff_br context derivation and per-context
   CDF adaptation within a single transform block than the smoother
   gradient fixture does. Generated 2026-07-13 the same way (aomenc 3.14.1
   / dav1d 1.5.3 cross-check)."
  []
  (load-resource "av1/fixtures/keyframe-32x32-busy.obu"))

(defn keyframe-32x32-busy-golden-yuv
  "dav1d's raw 8-bit gray decode of `keyframe-32x32-busy-bytes` -- see
   `keyframe-32x32-gradient-golden-yuv` docstring for the same pattern."
  []
  (load-resource "av1/fixtures/keyframe-32x32-busy.dav1d.yuv"))

(defn keyframe-64x64-vpred-bytes
  "A REAL aomenc (libaom 3.14.1)-encoded 64x64 MONOCHROME keyframe, Phase 1
   mode-coverage extension fixture (ADR-2607122000 Migration step 9
   continuation -- adds V_PRED/H_PRED to av1.decode-block's originally
   DC_PRED-only scope). Unlike the 32x32 single-leaf fixtures above, this
   is a real MULTI-leaf frame: one 64x64 superblock, forced via
   `--min-partition-size=32 --max-partition-size=32` to split into exactly
   4 BLOCK_32X32 leaves (top-left/top-right/bottom-left/bottom-right, in
   that decode order), each still exactly TX_32X32/DCT_DCT per this
   namespace's per-block scope -- av1.decode-block's decode-block-fn
   requires no changes to handle multiple leaves since av1.tile-group's
   decode-partition/MiSizes-grid tracking was already generic.

   Content is authored directly as known raw pixel bytes (not a lavfi
   filter), designed so a real, non-fabricated encoder RD decision
   reliably lands on this exact shape (verified empirically, see
   `test/av1/decode_block_test.clj`):

     - top-left: FLAT constant 128 (no neighbors available at all --
       forces DC_PRED trivially, AND -- because DC_PRED's have-one-
       neighbor-only average of a uniformly flat region is provably
       IDENTICAL to V_PRED/H_PRED's have-one-neighbor-only fallback value
       for that same flat region -- also forces top-right and bottom-left
       to tie-break to DC_PRED on cheaper signaling cost, regardless of
       their OWN authored content).
     - top-right and bottom-right: both f(x) = 128 + round(40*sin(2*pi*
       (x-32)/32)), invariant along y (x in [32,63]). Since bottom-right
       has a REAL avail-above neighbor (top-right, already reconstructed
       with the identical column profile), V_PRED gives near-zero
       residual there versus DC_PRED's single blended average -- real RD
       incentive, not a coin flip.
     - bottom-left: an independent 2D gradient/busy-style pattern (same
       family as `keyframe-32x32-gradient-bytes`) -- reliably DC_PRED via
       the flat-top-left tie-break above, regardless of its own content.

   aomenc invocation (adds `--enable-directional-intra=1
   --enable-diagonal-intra=0` -- a libaom flag that permits V_PRED/H_PRED
   specifically while keeping every *diagonal* directional mode D45..D203
   disabled -- to the same disabled-everything-else baseline as the
   32x32 fixtures, plus `--min-partition-size=32 --max-partition-size=32`
   to force the 2x2 BLOCK_32X32 split deterministically rather than
   relying on the encoder's partition RDO):

     aomenc --codec=av1 --limit=1 --passes=1 --end-usage=q --cq-level=32 \\
       --monochrome --enable-cdef=0 --enable-restoration=0 \\
       --loopfilter-control=0 --enable-filter-intra=0 --enable-smooth-intra=0 \\
       --enable-paeth-intra=0 --enable-directional-intra=1 \\
       --enable-diagonal-intra=0 --enable-angle-delta=0 --enable-intrabc=0 \\
       --enable-palette=0 --enable-qm=0 --enable-tx64=0 --enable-rect-tx=0 \\
       --enable-rect-partitions=0 --enable-ab-partitions=0 \\
       --enable-1to4-partitions=0 --enable-tx-size-search=0 \\
       --min-partition-size=32 --max-partition-size=32 \\
       --tile-columns=0 --tile-rows=0 --kf-min-dist=1 --kf-max-dist=1 \\
       --obu -o keyframe-64x64-vpred.obu keyframe-64x64-vpred.y4m

   (aomenc from Homebrew, libaom 3.14.1, generated 2026-07-13). Real-decode
   cross-check: `dav1d -i keyframe-64x64-vpred.obu -o
   keyframe-64x64-vpred.dav1d.yuv` (dav1d 1.5.3) produced the raw 8-bit
   gray 64x64 luma plane checked in as `keyframe-64x64-vpred.dav1d.yuv`.
   Empirically confirmed (see test/av1/decode_block_test.clj): all 4
   leaves are BLOCK_32X32/PARTITION_NONE/TX_32X32, top-left/top-right/
   bottom-left decode to y-mode DC_PRED (0), and bottom-right decodes to
   y-mode V_PRED (1) -- i.e. the real encoder genuinely chose V_PRED for
   this block, not merely a value this repo assumed."
  []
  (load-resource "av1/fixtures/keyframe-64x64-vpred.obu"))

(defn keyframe-64x64-vpred-golden-yuv
  "dav1d's raw 8-bit gray decode of `keyframe-64x64-vpred-bytes` -- see its
   docstring."
  []
  (load-resource "av1/fixtures/keyframe-64x64-vpred.dav1d.yuv"))

(defn keyframe-64x64-hpred-bytes
  "Same construction as `keyframe-64x64-vpred-bytes` (see its docstring for
   the full aomenc invocation/scope rationale), transposed to steer
   bottom-right to H_PRED instead of V_PRED:

     - top-left: FLAT constant 128 (same tie-breaking role as the vpred
       fixture -- forces top-right and bottom-left to DC_PRED regardless
       of their own content).
     - top-right: an independent 2D gradient/busy-style pattern (plays the
       role `bottom-left` played in the vpred fixture) -- DC_PRED via the
       flat-top-left tie-break.
     - bottom-left and bottom-right: both g(y) = 128 + round(40*sin(2*pi*
       (y-32)/32)), invariant along x (y in [32,63]). bottom-right has a
       REAL avail-left neighbor (bottom-left, already reconstructed with
       the identical row profile), so H_PRED gives near-zero residual
       there versus DC_PRED's blended average.

   Generated 2026-07-13 the same way (aomenc 3.14.1 / dav1d 1.5.3
   cross-check, identical flags to the vpred fixture except content).
   Empirically confirmed: all 4 leaves are BLOCK_32X32/PARTITION_NONE/
   TX_32X32, top-left/top-right/bottom-left decode to y-mode DC_PRED (0),
   and bottom-right decodes to y-mode H_PRED (2)."
  []
  (load-resource "av1/fixtures/keyframe-64x64-hpred.obu"))

(defn keyframe-64x64-hpred-golden-yuv
  "dav1d's raw 8-bit gray decode of `keyframe-64x64-hpred-bytes` -- see its
   docstring."
  []
  (load-resource "av1/fixtures/keyframe-64x64-hpred.dav1d.yuv"))

(defn keyframe-32x32-color-bytes
  "A REAL aomenc (libaom 3.14.1)-encoded 32x32 4:2:0 COLOR keyframe (Cb/Cr
   both real, non-flat data -- NOT `--monochrome`), the chroma-decode
   extension fixture (ADR-2607122000 Migration step 9 continuation -- adds
   chroma (Cb/Cr) decode to av1.decode-block's originally luma-only scope).
   Same aomenc scope restrictions as `keyframe-32x32-gradient-bytes` (see
   its docstring) PLUS `--enable-cfl-intra=0` (so UV_CFL_PRED can never be
   the encoder's choice -- combined with the pre-existing
   `--enable-smooth-intra=0 --enable-paeth-intra=0
   --enable-directional-intra=0`, which also gate the corresponding UV
   modes, DC_PRED/UV_DC_PRED are structurally the only modes left for the
   encoder to choose for both luma AND chroma, not merely the likely
   outcome for smooth content).

   Content is authored directly as known raw 8-bit I420 pixel bytes (not a
   lavfi filter): luma is the same 32x32 two-axis brightness-ramp family as
   `keyframe-32x32-gradient-bytes` (base 128, +/-20 across x, +/-4 across
   y); Cb is a 16x16 ramp across x (base 100, +/-24); Cr is a 16x16 ramp
   across y (base 160, +/-18) -- three genuinely DIFFERENT profiles per
   plane, so a bug that mixed up plane buffers/deltas/cdf tables would
   produce a wrong (not merely coincidentally-right) reconstruction.

     aomenc --codec=av1 --limit=1 --passes=1 --end-usage=q --cq-level=32 \\
       --enable-cdef=0 --enable-restoration=0 --loopfilter-control=0 \\
       --enable-filter-intra=0 --enable-smooth-intra=0 --enable-paeth-intra=0 \\
       --enable-directional-intra=0 --enable-angle-delta=0 --enable-intrabc=0 \\
       --enable-palette=0 --enable-cfl-intra=0 --enable-qm=0 --enable-tx64=0 \\
       --enable-rect-tx=0 --enable-rect-partitions=0 --enable-ab-partitions=0 \\
       --enable-1to4-partitions=0 --enable-tx-size-search=0 \\
       --tile-columns=0 --tile-rows=0 --kf-min-dist=1 --kf-max-dist=1 \\
       --obu -o keyframe-32x32-color.obu keyframe-32x32-color.y4m

   (aomenc from Homebrew, libaom 3.14.1, generated 2026-07-13). Real-decode
   cross-check: `dav1d -i keyframe-32x32-color.obu -o
   keyframe-32x32-color.dav1d.yuv` (dav1d 1.5.3, a completely independent
   AV1 decoder) produced the raw 8-bit I420 planes (1024 Y + 256 U + 256 V
   bytes) checked in as `keyframe-32x32-color.dav1d.yuv` -- see
   `keyframe-32x32-color-golden-yuv` below and test/av1/decode_block_test.clj
   for the bit-exact comparison against this repo's decoder (all three
   planes). Empirically confirmed (not merely by construction): single
   BLOCK_32X32/PARTITION_NONE leaf, YMode=DC_PRED, UVMode=UV_DC_PRED,
   eob=16 (luma) / 7 (U) / 10 (V) -- real, nontrivial (not all-zero)
   coefficient counts on all three planes."
  []
  (load-resource "av1/fixtures/keyframe-32x32-color.obu"))

(defn keyframe-32x32-color-golden-yuv
  "dav1d's raw 8-bit I420 decode of `keyframe-32x32-color-bytes` (1536
   bytes: 1024 Y + 256 U + 256 V, row-major each plane) -- the independent
   ground truth test/av1/decode_block_test.clj compares this repo's
   decoder output against, split per-plane."
  []
  (load-resource "av1/fixtures/keyframe-32x32-color.dav1d.yuv"))

(defn keyframe-32x32-color-busy-bytes
  "Same aomenc invocation/scope restrictions as `keyframe-32x32-color-bytes`
   (see its docstring), but with busier sinusoidal-plus-ramp content on all
   three planes (still authored as known raw pixel bytes, not a lavfi
   filter, and each plane uses a DIFFERENT frequency/phase so the three
   planes' coefficient patterns are genuinely distinct) -- exercises far
   more of av1.decode-block's coeff_base/coeff_base_eob/coeff_br context
   derivation and per-context CDF adaptation for the chroma planes than the
   smoother `keyframe-32x32-color-bytes` fixture does (eob=190 luma / 66 U
   / 105 V, vs. 16/7/10). Generated 2026-07-13 the same way (aomenc
   3.14.1 / dav1d 1.5.3 cross-check)."
  []
  (load-resource "av1/fixtures/keyframe-32x32-color-busy.obu"))

(defn keyframe-32x32-color-busy-golden-yuv
  "dav1d's raw 8-bit I420 decode of `keyframe-32x32-color-busy-bytes` --
   see `keyframe-32x32-color-golden-yuv` docstring for the same pattern."
  []
  (load-resource "av1/fixtures/keyframe-32x32-color-busy.dav1d.yuv"))

(defn keyframe-64x64-split16-bytes
  "A REAL aomenc (libaom 3.14.1)-encoded 64x64 MONOCHROME keyframe,
   deliberately forced two levels deep into decode_partition()'s recursion
   (ADR-2607122000 Migration step 9 continuation -- the multi-leaf
   scope-boundary regression fixture): `--min-partition-size=16
   --max-partition-size=16` forces every superblock decision down to
   exactly BLOCK_16X16 (real, normally-signaled `partition` symbol reads at
   both the 64x64->32x32 and 32x32->16x16 levels -- neither is a zero-bit
   structural forcing the way `keyframe-32x32-split.obu`'s MiRows==MiCols==8
   trick is; both symbols are genuinely coded because PARTITION_SPLIT is
   still one of several syntactically legal values at each level, merely
   the only one the `--max-partition-size` constraint lets the encoder's RD
   search choose), unlike `keyframe-64x64-vpred/hpred.obu` above which only
   forces ONE level (64x64->32x32, landing exactly on this namespace's
   supported BLOCK_32X32 leaf size). Content is flat 8-bit gray 128
   (authored directly as a raw y4m frame, C420jpeg chroma planes filled
   with 128 and ignored by the `--monochrome` encode) -- this fixture
   exists to validate CONTROL FLOW (av1.tile-group/decode-partition's real
   recursion depth and av1.decode-block's out-of-scope tx-size guard), not
   pixel reconstruction, so trivial flat content is sufficient (no bit-exact
   golden comparison is made against this fixture; see
   test/av1/decode_block_test.clj's `split16-throws-on-block16x16-test`).

     aomenc --codec=av1 --limit=1 --passes=1 --end-usage=q --cq-level=32 \\
       --monochrome --enable-cdef=0 --enable-restoration=0 \\
       --loopfilter-control=0 --enable-filter-intra=0 --enable-smooth-intra=0 \\
       --enable-paeth-intra=0 --enable-directional-intra=0 \\
       --enable-angle-delta=0 --enable-intrabc=0 --enable-palette=0 \\
       --enable-qm=0 --enable-tx64=0 --enable-rect-tx=0 \\
       --enable-rect-partitions=0 --enable-ab-partitions=0 \\
       --enable-1to4-partitions=0 --enable-tx-size-search=0 \\
       --tile-columns=0 --tile-rows=0 --kf-min-dist=1 --kf-max-dist=1 \\
       --min-partition-size=16 --max-partition-size=16 \\
       --obu -o keyframe-64x64-split16.obu keyframe-64x64-split16.y4m

   (aomenc from Homebrew, libaom 3.14.1, generated 2026-07-13). Empirically
   confirmed (see test/av1/decode_block_test.clj): WITHOUT a
   `:decode-block-fn` wired in, av1.tile-group/decode-partition's first
   real (bit-exact-valid -- i.e. before decode_block()'s bit consumption
   would desync anything further, per that namespace's docstring)
   `:superblock-partitions` leaf lands at (r=0,c=0), `:b-size` ==
   BLOCK_16X16, `:partition` == PARTITION_NONE -- i.e. the real encoder's
   forced recursion genuinely reaches a BLOCK_16X16 leaf, not merely a
   value this repo assumed. WITH `av1.decode-block/make-decode-block-fn`
   wired in, decoding this same real bitstream throws `ex-info`
   (`:reason :unsupported-tx-size`, `:mi-size` == BLOCK_16X16, `:tx-size`
   == TX_16X16) at exactly that first leaf, rather than mis-decoding or
   crashing uncontrolled -- av1.decode-block's `tx-size-for` scope guard
   (see its docstring) is exercised against a REAL encoded stream here for
   the first time (previously only reachable via direct unit-level
   `Max-Tx-Size-Rect` table inspection, not an actual decode_partition()
   walk over real bits)."
  []
  (load-resource "av1/fixtures/keyframe-64x64-split16.obu"))
