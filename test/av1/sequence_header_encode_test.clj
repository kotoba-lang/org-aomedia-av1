(ns av1.sequence-header-encode-test
  "Verifies av1.sequence-header/write (2026-07 AV1 encode task,
   ADR-2607122000 Migration step 9 continuation) by round-tripping its
   output back through av1.sequence-header/parse -- the SAME
   spec-transcribed reader this repo's decode side already validates
   against real aomenc/SVT-AV1 streams, the strongest available check for
   an encode-side fn (there is no independent 'known good' encoder test
   vector for a `reduced_still_picture_header=1` sequence header to check
   against)."
  (:require [clojure.test :refer [deftest testing is]]
            [av1.bitwriter :as bw]
            [av1.bitreader :as br]
            [av1.sequence-header :as sh]))

(deftest write-parse-roundtrip-test
  (testing "write -> parse reproduces every field this repo's decode side
            (av1.decode-block/guard-frame-scope!) requires"
    (let [bytes (bw/to-bytes (bw/trailing-bits (sh/write (bw/make-writer) {:max-frame-width 32 :max-frame-height 32})))
          parsed (sh/parse (br/make-reader bytes))]
      (is (= 0 (:seq-profile parsed)))
      (is (= 1 (:still-picture parsed)))
      (is (= 1 (:reduced-still-picture-header parsed)))
      (is (= 32 (:max-frame-width parsed)))
      (is (= 32 (:max-frame-height parsed)))
      (is (= 0 (:use-128x128-superblock parsed)))
      (is (= 1 (:mono-chrome parsed)))
      (is (= 1 (:num-planes parsed)))
      (is (= 1 (:subsampling-x parsed)))
      (is (= 1 (:subsampling-y parsed)))
      (is (= 0 (:enable-cdef parsed)))
      (is (= 0 (:enable-restoration parsed)))
      (is (= 0 (:enable-superres parsed)))
      (is (= 0 (:film-grain-params-present parsed))))))

(deftest write-parse-roundtrip-various-sizes-test
  (testing "write -> parse reproduces the requested frame dimensions for a
            few different sizes"
    (doseq [[w h] [[8 8] [16 16] [32 32] [64 48] [255 255]]]
      (let [bytes (bw/to-bytes (bw/trailing-bits (sh/write (bw/make-writer) {:max-frame-width w :max-frame-height h})))
            parsed (sh/parse (br/make-reader bytes))]
        (is (= w (:max-frame-width parsed)) (str "width mismatch for " w "x" h))
        (is (= h (:max-frame-height parsed)) (str "height mismatch for " w "x" h))))))

(deftest trailing-bits-required-for-real-decoder-interop-test
  (testing "the sequence header payload is not already byte-aligned after
            its own real syntax (39 bits for 32x32) -- confirming
            trailing-bits (not plain byte-alignment) is genuinely needed
            here, not a defensive no-op"
    (let [w (sh/write (bw/make-writer) {:max-frame-width 32 :max-frame-height 32})]
      (is (not (bw/byte-aligned? w)) "expected a non-byte-aligned bit count before trailing-bits"))))
