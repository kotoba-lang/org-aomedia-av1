(ns av1.transform-encode-test
  "Verifies av1.transform's ENCODE-side additions (forward-transform-2d,
   quantize) against its own pre-existing, real-data-validated decode
   side (inverse-transform-2d, dequantize): forward + quantize + dequantize
   + inverse should reproduce the original residual exactly for
   constant/low-frequency content (see forward-transform-2d's namespace
   docstring for why this is an exact, not approximate, orthonormal-basis
   inverse of inverse-transform-2d -- G=1/4 on the inverse side, 4x on the
   forward side, both probed numerically against the real
   inverse-transform-2d before being relied on), and with small, expected
   lossy-quantization error for busier content (normal for any DCT-based
   codec, not a bug)."
  (:require [clojure.test :refer [deftest testing is]]
            [av1.transform :as tf]))

(defn- ssd [a b] (reduce + (map (fn [x y] (let [d (- x y)] (* d d))) a b)))
(defn- max-abs-diff [a b] (apply max (map (fn [x y] (abs (- (long x) (long y)))) a b)))

(defn- roundtrip [residual dc-q ac-q]
  (let [coeff (tf/forward-transform-2d residual 5 5)
        quant (tf/quantize coeff 32 32 2 dc-q ac-q)
        dequant (tf/dequantize quant 32 32 2 dc-q ac-q 8)]
    {:quant quant :recon (tf/inverse-transform-2d dequant 5 5 8)}))

(deftest flat-residual-exact-roundtrip-test
  (testing "a perfectly flat (DC-only) residual round-trips exactly, and
            forward-transform-2d produces ONLY a nonzero DC coefficient
            (every AC coefficient is exactly 0, by DCT orthogonality)"
    (doseq [v [0 1 -1 10 -10 50 -50 100 -100 127 -128]]
      (let [residual (vec (repeat 1024 v))
            {:keys [quant recon]} (roundtrip residual 91 91)]
        (is (= 0 (ssd residual recon)) (str "flat residual " v " did not round-trip exactly"))
        (is (every? zero? (rest quant)) (str "flat residual " v " produced a nonzero AC coefficient"))))))

(deftest low-frequency-sinusoid-exact-roundtrip-test
  (testing "a smooth single-cycle horizontal sinusoid round-trips exactly
            even through quantization (coarse-enough quantizer, low
            enough frequency content that rounding never crosses an
            integer boundary)"
    (let [residual (vec (for [_y (range 32) x (range 32)]
                          (long (Math/round (* 20.0 (Math/sin (* 2 Math/PI (/ x 32.0))))))))
          {:keys [recon]} (roundtrip residual 20 20)]
      (is (= 0 (ssd residual recon)))
      (is (= 0 (max-abs-diff residual recon))))))

(deftest busy-content-small-lossy-error-test
  (testing "busier multi-frequency content with a fine quantizer
            round-trips with small (not zero) error -- normal lossy
            transform-coding behavior, bounded here as a regression check"
    (let [residual (vec (for [y (range 32) x (range 32)]
                          (long (+ (* 15 (Math/sin (* 0.3 x))) (* 10 (Math/cos (* 0.2 y)))))))
          {:keys [recon]} (roundtrip residual 4 4)]
      (is (< (ssd residual recon) 200) "busy-content SSD should be small at a fine quantizer")
      (is (<= (max-abs-diff residual recon) 5) "busy-content max-abs-diff should be small at a fine quantizer"))))

(deftest quantize-inverts-dequantize-for-exact-multiples-test
  (testing "quantize is an exact inverse of dequantize whenever the target
            dequantized value is an exact multiple of the quantizer step
            (no rounding loss possible in that case)"
    (doseq [dc-q [4 20 91 255]]
      (doseq [target-quant [-100 -5 -1 0 1 5 100]]
        (let [dequant-val (tf/dequantize [target-quant] 1 1 2 dc-q dc-q 8)
              requantized (tf/quantize dequant-val 1 1 2 dc-q dc-q)]
          (is (= [target-quant] requantized)
              (str "quantize(dequantize(" target-quant ")) with dc-q=" dc-q " should invert exactly")))))))

(deftest negative-coefficients-roundtrip-test
  (testing "a residual with both a negative DC bias and a real AC term
            round-trips (sign handling, both in forward-transform-2d's
            float math and quantize's rounding)"
    (let [residual (vec (for [_y (range 32) x (range 32)]
                          (long (+ -30 (* 8.0 (Math/cos (* Math/PI (/ x 16.0))))))))
          {:keys [recon]} (roundtrip residual 10 10)]
      (is (<= (max-abs-diff residual recon) 2)))))
