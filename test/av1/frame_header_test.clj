(ns av1.frame-header-test
  "Validates av1.frame-header (uncompressed_header() through
   quantization_params()) against the real SVT-AV1 fixture. Locates the
   sequence header first (frame header parsing needs its fields), then the
   frame_header/frame OBU that follows it, and checks that the decoded
   frame_width/frame_height match the known 64x48 source and that
   frame_type/show_frame are the expected KEY_FRAME/1 for the very first
   (and only) frame of a fresh encode."
  (:require [clojure.test :refer [deftest is testing]]
            [av1.bitreader :as br]
            [av1.obu :as obu]
            [av1.sequence-header :as sh]
            [av1.frame-header :as fh]
            [av1.fixtures :as fixtures]))

(defn- find-obu [bytes type-kw]
  (loop [r (br/make-reader bytes)]
    (let [o (obu/parse-obu r)]
      (cond
        (= type-kw (get-in o [:header :obu-type-kw])) o
        (>= (:payload-end o) (* 8 (count bytes))) nil
        :else (recur (obu/seek r (:payload-end o)))))))

(deftest frame-header-real-stream-test
  (let [bytes (fixtures/keyframe-bytes)
        seq-obu (find-obu bytes :obu-sequence-header)
        seq-hdr (sh/parse (:reader-at-payload seq-obu))
        frame-obu (or (find-obu bytes :obu-frame)
                      (find-obu bytes :obu-frame-header))
        frame-hdr (fh/parse (:reader-at-payload frame-obu) seq-hdr)]
    (testing "frame_type is KEY_FRAME and show_frame is 1 for the first frame
              of a fresh single-frame encode"
      (is (= fh/KEY_FRAME (:frame-type frame-hdr)))
      (is (true? (:frame-is-intra frame-hdr)))
      (is (= 1 (:show-frame frame-hdr))))
    (testing "decoded frame_width/frame_height match the 64x48 source --
              independent confirmation (via a completely different syntax
              path -- frame_size()/superres_params()/compute_image_size(),
              not sequence_header_obu()'s max_frame_width/height) that
              bit-exact parsing held all the way from show_existing_frame
              through tile_info() to here"
      (is (= 64 (:frame-width frame-hdr)))
      (is (= 48 (:frame-height frame-hdr)))
      (is (= 64 (:upscaled-width frame-hdr))))
    (testing "base_q_idx is a valid 8-bit quantizer index"
      (is (<= 0 (:base-q-idx frame-hdr) 255)))
    (testing "tile_info: a 64x48 single-superblock-ish frame should use a
              single tile (TileCols == TileRows == 1) since it's far under
              MAX_TILE_WIDTH/MAX_TILE_AREA"
      (is (= 1 (get-in frame-hdr [:tile-info :tile-cols])))
      (is (= 1 (get-in frame-hdr [:tile-info :tile-rows]))))))
