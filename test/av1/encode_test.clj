(ns av1.encode-test
  "End-to-end validation of av1.encode/encode-keyframe (2026-07 AV1 encode
   task, ADR-2607122000 Migration step 9 continuation -- \"AV1 encode
   side\", the mirror image of this repo's decode-side pixel-
   reconstruction milestones): a single monochrome 32x32 KEY_FRAME, one
   BLOCK_32X32/PARTITION_NONE/DC_PRED/TX_32X32/DCT_DCT leaf.

   Validation strategy (matching the H.264 sibling repo's own
   decode->encode task methodology, and this repo's own decode-side
   \"real bitstream + independent decoder, bit-exact, no tolerance\"
   practice):

     1. Round-trip: this repo's OWN decoder (av1.decode-block, already
        validated against real aomenc/SVT-AV1 streams) decodes THIS
        repo's own encoder's output and must reproduce the target pixels
        exactly for flat/low-frequency content (SSD=0), and closely for
        busier content (small, expected lossy-transform-coding error).
     2. REAL independent decoder (dav1d, via checked-in fixtures --
        `test/av1/fixtures.clj`'s `encode-keyframe-32x32-*-bytes`/
        `-golden-yuv` pairs): this repo's own decode of the CHECKED-IN
        `.obu` fixture must be bit-exact (no tolerance) against the
        CHECKED-IN `dav1d.yuv` golden output -- the single most important
        check for this task (does dav1d, a real independent AV1 decoder,
        actually accept and correctly decode this repo's own encoder's
        bitstream). See test/av1/fixtures.clj's docstrings for the exact
        `dav1d`/`aomdec` invocations used to produce these fixtures, and
        for `aomdec`'s independent cross-check of the same `.obu` files.
     3. Determinism: re-running `av1.encode/encode-keyframe` with the same
        arguments used to generate each checked-in fixture reproduces the
        checked-in `.obu` bytes EXACTLY -- confirms the fixtures aren't
        stale (drifted from the current encoder) and that encoding is
        deterministic (no hidden randomness/nondeterministic map-ordering
        dependency)."
  (:require [clojure.test :refer [deftest testing is]]
            [av1.bitreader :as br]
            [av1.obu :as obu]
            [av1.sequence-header :as sh]
            [av1.frame-header :as fh]
            [av1.tile-group :as tg]
            [av1.decode-block :as db]
            [av1.encode :as enc]
            [av1.fixtures :as fixtures]))

(defn- find-obu [bytes type-kw]
  (loop [r (br/make-reader bytes)]
    (let [o (obu/parse-obu r)]
      (cond
        (= type-kw (get-in o [:header :obu-type-kw])) o
        (>= (:payload-end o) (* 8 (count bytes))) nil
        :else (recur (obu/seek r (:payload-end o)))))))

(defn- decode-luma-plane
  "Parses `bytes` end-to-end via this repo's own decode side (sequence
   header -> frame header -> tile_group_obu -> decode_partition ->
   decode_block, av1.decode-block/make-decode-block-fn wired in) and
   returns the flat row-major reconstructed luma plane."
  [bytes]
  (let [seq-obu (find-obu bytes :obu-sequence-header)
        seq-hdr (sh/parse (:reader-at-payload seq-obu))
        frame-obu (find-obu bytes :obu-frame)
        frame-hdr0 (fh/parse (:reader-at-payload frame-obu) seq-hdr)
        decode-block-fn (db/make-decode-block-fn frame-hdr0 seq-hdr)
        result (tg/parse-frame-obu frame-obu seq-hdr {:decode-block-fn decode-block-fn})
        tile (first (:tiles (:tile-group result)))]
    (:luma-plane (:final-tile-state tile))))

(defn- ssd [a b] (reduce + (map (fn [x y] (let [d (- x y)] (* d d))) a b)))
(defn- max-abs-diff [a b] (apply max (map (fn [x y] (abs (- (long x) (long y)))) a b)))

(defn- bytes->unsigned-vec
  "java.io.InputStream bytes come back as a Java byte[] (signed);
   fixtures.clj's `load-resource` returns that raw byte[] -- normalize to
   unsigned 0-255 ints for pixel comparison against this repo's own
   0-255 luma-plane vectors."
  [^bytes raw]
  (mapv #(bit-and % 0xff) raw))

;; ---------------------------------------------------------------------
;; 1. Round-trip against the ORIGINAL target pixels (not the checked-in
;;    fixture -- this exercises av1.encode/encode-keyframe fresh, end to
;;    end, every test run).

(deftest flat-skip-roundtrip-test
  (testing "a flat 128 (== DC_PRED's no-neighbors predicted value exactly)
            32x32 frame, encoded with skip=1 (no residual), decodes back
            to the target EXACTLY"
    (let [target (vec (repeat 1024 128))
          bytes (enc/encode-keyframe target 100 {:skip? true})
          decoded (decode-luma-plane bytes)]
      (is (= target decoded)))))

(deftest flat-dc-only-roundtrip-test
  (testing "a flat (non-128) 32x32 frame, encoded with a real (non-skip)
            DC-only residual, decodes back to the target EXACTLY (a
            constant residual is exactly the DC basis function -- no
            quantization error possible for a single coefficient at a
            reasonable quantizer)"
    (doseq [v [90 138 200]]
      (let [target (vec (repeat 1024 v))
            bytes (enc/encode-keyframe target 100 {:skip? false})
            decoded (decode-luma-plane bytes)]
        (is (= target decoded) (str "flat " v " did not round-trip exactly"))))))

(deftest gradient-small-lossy-error-test
  (testing "a smooth low-frequency sinusoid round-trips with small
            (expected, normal lossy-transform-coding) error at a moderate
            quantizer"
    (let [target (vec (for [_y (range 32) x (range 32)]
                        (int (+ 128 (* 20 (Math/sin (* 2 Math/PI (/ x 32.0))))))))
          bytes (enc/encode-keyframe target 60 {:skip? false})
          decoded (decode-luma-plane bytes)]
      (is (<= (max-abs-diff target decoded) 3))
      (is (< (ssd target decoded) 500)))))

(deftest negative-residual-sign-test
  (testing "a dark (below-128, negative-residual) flat frame exercises the
            dc_sign symbol's negative branch and round-trips exactly"
    (let [target (vec (repeat 1024 40))
          bytes (enc/encode-keyframe target 80 {:skip? false})
          decoded (decode-luma-plane bytes)]
      (is (= target decoded)))))

;; ---------------------------------------------------------------------
;; 2. REAL independent decoder (dav1d) validation via checked-in fixtures
;;    -- see test/av1/fixtures.clj's docstrings for exactly how each
;;    `.obu`/`.dav1d.yuv` pair was produced (this repo's own encoder, then
;;    real `dav1d`/cross-checked against real `aomdec`).

(deftest dav1d-bit-exact-flat-test
  (testing "encode-keyframe-32x32-flat: this repo's own decode of its own
            checked-in encoder output is bit-exact against dav1d's
            independent decode of the SAME bitstream"
    (let [our-decoded (decode-luma-plane (fixtures/encode-keyframe-32x32-flat-bytes))
          dav1d-decoded (bytes->unsigned-vec (fixtures/encode-keyframe-32x32-flat-golden-yuv))]
      (is (= our-decoded dav1d-decoded))
      (is (= our-decoded (vec (repeat 1024 128)))))))

(deftest dav1d-bit-exact-dc-test
  (testing "encode-keyframe-32x32-dc: bit-exact against dav1d, AND exactly
            matches the original target (170) -- a pure-DC residual has
            no quantization loss at this quantizer"
    (let [our-decoded (decode-luma-plane (fixtures/encode-keyframe-32x32-dc-bytes))
          dav1d-decoded (bytes->unsigned-vec (fixtures/encode-keyframe-32x32-dc-golden-yuv))]
      (is (= our-decoded dav1d-decoded))
      (is (= our-decoded (vec (repeat 1024 170)))))))

(deftest dav1d-bit-exact-gradient-test
  (testing "encode-keyframe-32x32-gradient: bit-exact against dav1d (real
            multi-coefficient AC residual, several coeff_base/
            coeff_base_eob symbols)"
    (let [our-decoded (decode-luma-plane (fixtures/encode-keyframe-32x32-gradient-bytes))
          dav1d-decoded (bytes->unsigned-vec (fixtures/encode-keyframe-32x32-gradient-golden-yuv))]
      (is (= our-decoded dav1d-decoded)))))

(deftest dav1d-bit-exact-busy-golomb-test
  (testing "encode-keyframe-32x32-busy: bit-exact against dav1d -- this
            fixture's quantized coefficients include several with
            |value| > 14 (confirmed in test/av1/fixtures.clj's docstring),
            so this is the regression test for av1.encode-block/
            write-golomb specifically (the coeff_br continuation loop's
            full-4-iteration 'maxed out' escape path)"
    (let [our-decoded (decode-luma-plane (fixtures/encode-keyframe-32x32-busy-bytes))
          dav1d-decoded (bytes->unsigned-vec (fixtures/encode-keyframe-32x32-busy-golden-yuv))]
      (is (= our-decoded dav1d-decoded)))))

;; ---------------------------------------------------------------------
;; 3. Determinism -- re-encoding with the same target pixels/args used to
;;    produce each checked-in fixture reproduces it byte-for-byte.

(deftest encoder-determinism-matches-checked-in-fixtures-test
  (testing "re-running av1.encode/encode-keyframe with each fixture's
            documented arguments (see test/av1/fixtures.clj) reproduces
            the checked-in .obu bytes exactly -- confirms the fixtures
            are not stale and encoding has no hidden nondeterminism"
    (is (= (vec (bytes->unsigned-vec (fixtures/encode-keyframe-32x32-flat-bytes)))
           (vec (enc/encode-keyframe (vec (repeat 1024 128)) 100 {:skip? true}))))
    (is (= (vec (bytes->unsigned-vec (fixtures/encode-keyframe-32x32-dc-bytes)))
           (vec (enc/encode-keyframe (vec (repeat 1024 170)) 100 {:skip? false}))))
    (is (= (vec (bytes->unsigned-vec (fixtures/encode-keyframe-32x32-gradient-bytes)))
           (vec (enc/encode-keyframe
                 (vec (for [_y (range 32) x (range 32)]
                        (int (+ 128 (* 20 (Math/sin (* 2 Math/PI (/ x 32.0))))))))
                 60 {:skip? false}))))
    (is (= (vec (bytes->unsigned-vec (fixtures/encode-keyframe-32x32-busy-bytes)))
           (vec (enc/encode-keyframe
                 (vec (for [y (range 32) x (range 32)]
                        (max 0 (min 255 (int (+ 128 (* 60 (Math/sin (* 0.9 x)))
                                                (* 40 (Math/cos (* 0.7 y)))
                                                (* 15 (Math/sin (* 3.1 x)))))))))
                 20 {:skip? false}))))))
