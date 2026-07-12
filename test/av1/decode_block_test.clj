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
   different coefficient densities within the same TX_32X32 block.

   `keyframe-64x64-vpred`/`keyframe-64x64-hpred` (Phase 1 mode-coverage
   extension, ADR-2607122000 Migration step 9 continuation) extend the
   above to real MULTI-leaf frames (2x2 grid of BLOCK_32X32 leaves) and
   V_PRED/H_PRED, exercising av1.decode-block's cross-block context
   tracking (YModes/Intra_Mode_Context via `read-y-mode`, AboveDcContext/
   LeftDcContext via `get-dc-sign-ctx`, footprint-wide grid writes via
   `record-footprint!`) for the first time -- see test/av1/fixtures.clj's
   docstrings for the content design and av1.decode-block's namespace
   docstring for the exact scope boundary."
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

(defn- find-leaves
  "Collects every `:leaf` node from a tile's superblock-partitions tree, in
   raster/z-order (matches decode_partition()'s real traversal order)."
  [tile]
  (let [leaves (atom [])
        find-leaf (fn find-leaf [node]
                    (if (:leaf node)
                      (swap! leaves conj node)
                      (when (:children node) (doseq [c (:children node)] (find-leaf c)))))]
    (doseq [sb (:superblock-partitions tile)] (find-leaf sb))
    @leaves))

(defn- decode-luma-plane+leaves
  "Like `decode-luma-plane` but also returns every leaf (for the multi-leaf
   V_PRED/H_PRED fixtures below, which have 4 leaves per frame, not 1)."
  [bytes]
  (let [seq-obu (find-obu bytes :obu-sequence-header)
        seq-hdr (sh/parse (:reader-at-payload seq-obu))
        frame-obu (find-obu bytes :obu-frame)
        frame-hdr0 (fh/parse (:reader-at-payload frame-obu) seq-hdr)
        decode-block-fn (db/make-decode-block-fn frame-hdr0 seq-hdr)
        result (tg/parse-frame-obu frame-obu seq-hdr {:decode-block-fn decode-block-fn})
        tile (first (:tiles (:tile-group result)))]
    {:frame-header (:frame-header result)
     :leaves (find-leaves tile)
     :luma-plane (:luma-plane (:final-tile-state tile))}))

(deftest v-pred-64x64-bit-exact-test
  (let [bytes (fixtures/keyframe-64x64-vpred-bytes)
        golden (golden-vec (fixtures/keyframe-64x64-vpred-golden-yuv))
        {:keys [frame-header leaves luma-plane]} (decode-luma-plane+leaves bytes)]
    (testing "frame geometry: 64x64, one 64x64 superblock forced to split
              into a 2x2 grid of BLOCK_32X32 leaves (--min-partition-size=32
              --max-partition-size=32, see test/av1/fixtures.clj docstring)"
      (is (= 64 (:frame-width frame-header)))
      (is (= 64 (:frame-height frame-header)))
      (is (= 4 (count leaves)) "top-left/top-right/bottom-left/bottom-right"))
    (testing "every leaf is BLOCK_32X32/PARTITION_NONE/TX_32X32 (this
              namespace's per-block scope is unchanged by having multiple
              leaves per frame)"
      (doseq [leaf leaves]
        (is (= tg/BLOCK_32X32 (:b-size leaf)))
        (is (= tg/PARTITION_NONE (:partition leaf)))
        (is (= 3 (get-in leaf [:decode-block :tx-size])) "TX_32X32")))
    (testing "the real encoder actually chose DC_PRED for the first three
              leaves and V_PRED (not merely a value this repo assumed) for
              the last -- see test/av1/fixtures.clj docstring for why this
              content/flag combination reliably produces this exact
              mode assignment via real RD comparison, not by construction"
      (let [[tl tr bl br] leaves]
        (is (= db/DC_PRED (get-in tl [:decode-block :y-mode])))
        (is (= db/DC_PRED (get-in tr [:decode-block :y-mode])))
        (is (= db/DC_PRED (get-in bl [:decode-block :y-mode])))
        (is (= db/V_PRED (get-in br [:decode-block :y-mode]))
            "bottom-right: real avail-above neighbor (top-right) with a
             matching column-invariant profile makes V_PRED cheaper than
             DC_PRED's blended average -- confirmed via real aomenc RDO")))
    (testing "reconstructed luma plane is bit-exact against dav1d's
              independent decode of the same real encoded bitstream (no
              tolerance) -- this is the primary regression test for the
              angle_delta_y bug this extension uncovered and fixed (see
              av1.decode-block/read-angle-delta-y's docstring): without a
              real angle_delta_y symbol read for every V_PRED/H_PRED
              block, the bottom-right leaf's residual desyncs from the
              real bitstream by exactly that symbol's width, producing a
              plausible-looking but wrong reconstruction that only a
              bit-exact comparison (not visual inspection) catches"
      (is (= 4096 (count luma-plane)))
      (is (= golden luma-plane)))))

(deftest h-pred-64x64-bit-exact-test
  (let [bytes (fixtures/keyframe-64x64-hpred-bytes)
        golden (golden-vec (fixtures/keyframe-64x64-hpred-golden-yuv))
        {:keys [frame-header leaves luma-plane]} (decode-luma-plane+leaves bytes)]
    (testing "frame geometry: 64x64, 2x2 grid of BLOCK_32X32 leaves"
      (is (= 64 (:frame-width frame-header)))
      (is (= 64 (:frame-height frame-header)))
      (is (= 4 (count leaves))))
    (testing "every leaf is BLOCK_32X32/PARTITION_NONE/TX_32X32"
      (doseq [leaf leaves]
        (is (= tg/BLOCK_32X32 (:b-size leaf)))
        (is (= tg/PARTITION_NONE (:partition leaf)))
        (is (= 3 (get-in leaf [:decode-block :tx-size])))))
    (testing "the real encoder chose DC_PRED for the first three leaves and
              H_PRED for the last"
      (let [[tl tr bl br] leaves]
        (is (= db/DC_PRED (get-in tl [:decode-block :y-mode])))
        (is (= db/DC_PRED (get-in tr [:decode-block :y-mode])))
        (is (= db/DC_PRED (get-in bl [:decode-block :y-mode])))
        (is (= db/H_PRED (get-in br [:decode-block :y-mode]))
            "bottom-right: real avail-left neighbor (bottom-left) with a
             matching row-invariant profile makes H_PRED cheaper than
             DC_PRED's blended average")))
    (testing "reconstructed luma plane is bit-exact against dav1d's
              independent decode (no tolerance)"
      (is (= 4096 (count luma-plane)))
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
