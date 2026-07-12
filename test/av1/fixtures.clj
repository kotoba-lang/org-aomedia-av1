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
