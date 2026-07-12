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

(defn keyframe-bytes []
  (with-open [in (io/input-stream (io/resource "av1/fixtures/keyframe-64x48.obu"))]
    (let [baos (java.io.ByteArrayOutputStream.)]
      (io/copy in baos)
      (.toByteArray baos))))
