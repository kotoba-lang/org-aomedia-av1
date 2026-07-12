(ns av1.decode-block-test
  "Validates av1.decode-block's decode_block() (mode_info/read_block_tx_size/
   residual/coeffs/dequantize/inverse-transform-2d/DC intra prediction)
   against TWO REAL aomenc-encoded 32x32 monochrome keyframes, comparing
   this repo's reconstructed luma plane bit-exactly against dav1d's
   independent decode of the same bitstream -- see av1.decode-block's
   namespace docstring for the exact scope this validates (single
   superblock / single BLOCK_32X32 leaf / TX_32X32 / DCT_DCT / DC_PRED /
   luma-only) and test/av1/fixtures.clj's docstrings for exactly how each
   fixture was generated and why it lands in that scope by construction
   (encoder flags disable every non-DC intra mode, every non-NONE/SPLIT
   partition type, CDEF/loop-filter/restoration, and TX_MODE_SELECT) rather
   than merely by observed probability.

   `keyframe-32x32-gradient` (eob=7, few nonzero coefficients) and
   `keyframe-32x32-busy` (eob=67, many more) exercise this namespace's
   coefficient-context derivation (get-coeff-base-ctx/get-coeff-br-ctx) and
   per-context CDF adaptation (av1.bool-decoder/read-symbol) at two very
   different coefficient densities within the same TX_32X32 block."
  (:require [clojure.test :refer [deftest is testing]]
            [av1.bitreader :as br]
            [av1.obu :as obu]
            [av1.sequence-header :as sh]
            [av1.frame-header :as fh]
            [av1.tile-group :as tg]
            [av1.decode-block :as db]
            [av1.fixtures :as fixtures]))

(defn- find-obu [bytes type-kw]
  (loop [r (br/make-reader bytes)]
    (let [o (obu/parse-obu r)]
      (cond
        (= type-kw (get-in o [:header :obu-type-kw])) o
        (>= (:payload-end o) (* 8 (count bytes))) nil
        :else (recur (obu/seek r (:payload-end o)))))))

(defn- decode-luma-plane
  "Parses `bytes` end-to-end (sequence header -> frame header ->
   tile_group_obu -> decode_partition -> decode_block, via
   av1.decode-block/make-decode-block-fn wired in as av1.tile-group's
   injectable :decode-block-fn) and returns
   {:frame-header ... :leaf ... :luma-plane [<flat row-major pixel vector>]}."
  [bytes]
  (let [seq-obu (find-obu bytes :obu-sequence-header)
        seq-hdr (sh/parse (:reader-at-payload seq-obu))
        frame-obu (find-obu bytes :obu-frame)
        frame-hdr0 (fh/parse (:reader-at-payload frame-obu) seq-hdr)
        decode-block-fn (db/make-decode-block-fn frame-hdr0 seq-hdr)
        result (tg/parse-frame-obu frame-obu seq-hdr {:decode-block-fn decode-block-fn})
        tile (first (:tiles (:tile-group result)))
        find-leaf (fn find-leaf [node]
                    (if (:leaf node)
                      node
                      (when (:children node) (some find-leaf (:children node)))))
        leaf (some find-leaf (:superblock-partitions tile))]
    {:frame-header (:frame-header result)
     :leaf leaf
     :luma-plane (:luma-plane (:final-tile-state tile))}))

(defn- golden-vec [golden-bytes]
  (mapv #(bit-and 0xff %) golden-bytes))

(deftest gradient-32x32-bit-exact-test
  (let [bytes (fixtures/keyframe-32x32-gradient-bytes)
        golden (golden-vec (fixtures/keyframe-32x32-gradient-golden-yuv))
        {:keys [frame-header leaf luma-plane]} (decode-luma-plane bytes)]
    (testing "frame geometry: 32x32, single superblock"
      (is (= 32 (:frame-width frame-header)))
      (is (= 32 (:frame-height frame-header))))
    (testing "the encoder's disabled-everything-but-DC/NONE/TX_32X32 flags
              (see test/av1/fixtures.clj docstring) actually produced
              exactly the single-leaf/BLOCK_32X32/PARTITION_NONE shape this
              namespace supports -- not a different shape that happened to
              also decode without throwing"
      (is (= tg/BLOCK_32X32 (:b-size leaf)))
      (is (= tg/PARTITION_NONE (:partition leaf)))
      (is (= 3 (get-in leaf [:decode-block :tx-size])) "TX_32X32")
      (is (false? (get-in leaf [:decode-block :skip])))
      (is (= 7 (get-in leaf [:decode-block :eob]))
          "documents the actual observed coefficient density for this fixture"))
    (testing "reconstructed luma plane is bit-exact against dav1d's
              independent decode of the same real encoded bitstream (no
              tolerance -- see namespace docstring)"
      (is (= 1024 (count luma-plane)))
      (is (= golden luma-plane)))))

(deftest busy-32x32-bit-exact-test
  (let [bytes (fixtures/keyframe-32x32-busy-bytes)
        golden (golden-vec (fixtures/keyframe-32x32-busy-golden-yuv))
        {:keys [frame-header leaf luma-plane]} (decode-luma-plane bytes)]
    (testing "frame geometry: 32x32, single superblock"
      (is (= 32 (:frame-width frame-header)))
      (is (= 32 (:frame-height frame-header))))
    (testing "same validated shape as the gradient fixture, but with far
              more nonzero coefficients (eob=67 vs. 7) -- exercises much
              more of get-coeff-base-ctx/get-coeff-br-ctx and per-context
              CDF adaptation within the same single transform block"
      (is (= tg/BLOCK_32X32 (:b-size leaf)))
      (is (= tg/PARTITION_NONE (:partition leaf)))
      (is (= 3 (get-in leaf [:decode-block :tx-size])))
      (is (false? (get-in leaf [:decode-block :skip])))
      (is (= 67 (get-in leaf [:decode-block :eob]))))
    (testing "reconstructed luma plane is bit-exact against dav1d's
              independent decode (no tolerance)"
      (is (= 1024 (count luma-plane)))
      (is (= golden luma-plane)))))

(deftest guard-frame-scope-throws-test
  (testing "make-decode-block-fn rejects out-of-scope frame headers instead
            of silently mis-parsing them (see av1.decode-block/guard-frame-scope!)"
    (let [bytes (fixtures/keyframe-32x32-gradient-bytes)
          seq-obu (find-obu bytes :obu-sequence-header)
          seq-hdr (sh/parse (:reader-at-payload seq-obu))
          frame-obu (find-obu bytes :obu-frame)
          frame-hdr (fh/parse (:reader-at-payload frame-obu) seq-hdr)]
      (testing "coded_lossless=1 is rejected (lossless uses the WHT, not DCT_DCT)"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CodedLossless"
                               (db/make-decode-block-fn (assoc frame-hdr :coded-lossless 1) seq-hdr))))
      (testing "non-monochrome (num_planes>1) is rejected (luma-only phase)"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mono_chrome"
                               (db/make-decode-block-fn frame-hdr (assoc seq-hdr :mono-chrome 0)))))
      (testing "tx_mode other than TX_MODE_LARGEST is rejected (no tx_depth CDF support)"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tx_mode"
                               (db/make-decode-block-fn (assoc frame-hdr :tx-mode :tx-mode-select) seq-hdr)))))))
