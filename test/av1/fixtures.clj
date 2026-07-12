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
