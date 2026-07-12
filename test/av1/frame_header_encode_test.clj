(ns av1.frame-header-encode-test
  "Verifies av1.frame-header/write (2026-07 AV1 encode task, ADR-2607122000
   Migration step 9 continuation) by round-tripping its output back through
   av1.frame-header/parse, paired with a real av1.sequence-header/write
   sequence header (parse's own dependency) -- the same validation
   strategy av1.sequence-header-encode-test uses, and for the same reason
   (no independent 'known good' encoder test vector exists for this
   narrow scope)."
  (:require [clojure.test :refer [deftest testing is]]
            [av1.bitwriter :as bw]
            [av1.bitreader :as br]
            [av1.sequence-header :as sh]
            [av1.frame-header :as fh]
            [av1.decode-block :as db]))

(defn- seq-hdr-32x32 []
  (let [bytes (bw/to-bytes (bw/trailing-bits (sh/write (bw/make-writer) {:max-frame-width 32 :max-frame-height 32})))]
    (sh/parse (br/make-reader bytes))))

(deftest write-parse-roundtrip-test
  (testing "write -> parse reproduces every field av1.decode-block/
            guard-frame-scope! requires, for this repo's narrow encode
            scope (monochrome, single tile, TX_MODE_LARGEST, no
            segmentation/delta-q/delta-lf)"
    (let [seq-hdr (seq-hdr-32x32)
          bytes (bw/to-bytes (fh/write (bw/make-writer) {:base-q-idx 100}))
          parsed (fh/parse (br/make-reader bytes) seq-hdr)]
      (is (= 0 (:frame-type parsed)) "KEY_FRAME")
      (is (true? (:frame-is-intra parsed)))
      (is (= 1 (:show-frame parsed)))
      (is (= 100 (:base-q-idx parsed)))
      (is (= 32 (:frame-width parsed)))
      (is (= 32 (:frame-height parsed)))
      (is (= 8 (:mi-cols parsed)))
      (is (= 8 (:mi-rows parsed)))
      (is (= 1 (get-in parsed [:tile-info :tile-cols])))
      (is (= 1 (get-in parsed [:tile-info :tile-rows])))
      (is (= :tx-mode-largest (:tx-mode parsed)))
      (is (= 0 (:coded-lossless parsed)))
      (is (= 0 (get-in parsed [:segmentation-params :segmentation-enabled])))
      (is (= 0 (get-in parsed [:delta-q-params :delta-q-present])))
      (is (= 0 (:allow-screen-content-tools parsed)))
      (is (= 0 (:allow-intrabc parsed)))
      ;; This exact combination is what av1.decode-block/guard-frame-scope!
      ;; requires -- confirm it doesn't throw for a real parsed result.
      (is (nil? (db/guard-frame-scope! parsed seq-hdr))))))

(deftest base-q-idx-range-test
  (testing "base-q-idx of 0 is rejected (would force CodedLossless=1, out
            of av1.decode-block's supported scope)"
    (is (thrown? Exception
                  (fh/write (bw/make-writer) {:base-q-idx 0}))))
  (testing "base-q-idx of 255 (max) and 1 (min nonzero) both round-trip"
    (let [seq-hdr (seq-hdr-32x32)]
      (doseq [q [1 100 255]]
        (let [bytes (bw/to-bytes (fh/write (bw/make-writer) {:base-q-idx q}))
              parsed (fh/parse (br/make-reader bytes) seq-hdr)]
          (is (= q (:base-q-idx parsed))))))))
