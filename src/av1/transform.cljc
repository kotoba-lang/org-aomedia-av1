(ns av1.transform
  "AV1 dequantization + inverse DCT + 2D inverse transform + reconstruction,
   transcribed field-for-field from AV1 Bitstream & Decoding Process
   Specification section 7.12 (\"Reconstruction and dequantization\") /
   7.13 (\"Inverse transform process\") -- AOMediaCodec/av1-spec master,
   08.decoding.process.md #Reconstruct process / #1D transforms /
   #2D inverse transform process, 04.conventions.md #Round2 (fetched
   2026-07-13).

   SCOPE (Phase 1 continuation, ADR-2607122000 Migration step 9 -- see
   av1.decode-block namespace docstring for the full scope statement):
   DCT_DCT only (no ADST/FLIPADST/IDTX -- av1.decode-block throws before
   calling this namespace for any other PlaneTxType), 8-bit only (no
   high-bitdepth). The inverse-DCT process below (`inverse-dct!`) is
   transcribed in full generality for n = 2..6 (TX_4X4 .. TX_64X64) per
   the spec's own unified algorithm -- this is no harder to transcribe
   completely than partially, since the spec gives one flat, self-similar
   step list gated by `n` conditions -- but av1.decode-block only ever
   calls it with n=5 (TX_32X32, this phase's only supported transform
   size; see av1.tables namespace docstring for why), so only that path is
   validated against real data.

   Implementation note: the spec's 1D transform processes are described as
   in-place mutation of an array T. This namespace mirrors that with a
   Clojure transient vector (assoc!/nth), the same mutation-via-transient
   style av1.bool-decoder already uses for CDF adaptation -- portable to
   both `:clj` and `:cljs` (no Java-array/JS-typed-array dependency)."
  (:require [av1.tables :as tables]))

(defn round2
  "04.conventions.md Round2(x,n): (x + (1<<(n-1))) >> n, or x when n==0.
   Works for negative x via bit-shift-right's arithmetic (sign-extending)
   right shift, matching the spec's floor-division definition."
  [x n]
  (if (zero? n) x (bit-shift-right (+ x (bit-shift-left 1 (dec n))) n)))

(defn brev
  "08.decoding.process.md #Butterfly functions: brev(numBits, x) = bit-reversal
   of the low numBits bits of x."
  [num-bits x]
  (loop [i 0, t 0]
    (if (>= i num-bits)
      t
      (let [bit (bit-and (bit-shift-right x i) 1)]
        (recur (inc i) (+ t (bit-shift-left bit (- num-bits 1 i))))))))

(defn cos128
  "cos128(angle): Cos128_Lookup-based piecewise definition, spec #Butterfly
   functions."
  [angle]
  (let [angle2 (bit-and angle 255)]
    (cond
      (<= 0 angle2 64) (nth tables/Cos128-Lookup angle2)
      (<= 65 angle2 128) (- (nth tables/Cos128-Lookup (- 128 angle2)))
      (<= 129 angle2 192) (- (nth tables/Cos128-Lookup (- angle2 128)))
      :else (nth tables/Cos128-Lookup (- 256 angle2)))))

(defn sin128 [angle] (cos128 (- angle 64)))

(defn- t-get [t i] (nth t i))
(defn- t-set [t i v] (assoc! t i v))

(defn B
  "spec #Butterfly functions: B(a,b,angle,flip,r) -- butterfly rotation
   (+ index swap when flip==1). Returns the updated transient T. `r` is
   accepted (not used computationally) only to keep this fn's signature a
   1:1 match with the spec's B(a,b,angle,flip,r) -- the spec's `r` is a
   *bitstream conformance requirement* on the values B produces (\"it is a
   requirement of bitstream conformance that the values saved into T are
   representable by r bits\"), not an input B's own pseudocode reads from."
  [t a b angle flip _r]
  (let [ta (t-get t a) tb (t-get t b)
        x (- (* ta (cos128 angle)) (* tb (sin128 angle)))
        y (+ (* ta (sin128 angle)) (* tb (cos128 angle)))
        va (round2 x 12) vb (round2 y 12)]
    (if (zero? flip)
      (-> t (t-set a va) (t-set b vb))
      (-> t (t-set a vb) (t-set b va)))))

(defn H
  "spec #Butterfly functions: H(a,b,flip,r) -- Hadamard rotation with
   intermediate clamping to r bits, (+ index swap when flip==1)."
  [t a b flip r]
  (let [[a b] (if (zero? flip) [a b] [b a])
        x (t-get t a) y (t-get t b)
        lo (- (bit-shift-left 1 (dec r))) hi (dec (bit-shift-left 1 (dec r)))
        clip3 (fn [v] (cond (< v lo) lo (> v hi) hi :else v))]
    (-> t (t-set a (clip3 (+ x y))) (t-set b (clip3 (- x y))))))

(defn inverse-dct-permute
  "spec #Inverse DCT array permutation process: in-place bit-reversal
   permutation of T (length 2^n)."
  [t n]
  (let [n0 (bit-shift-left 1 n)
        copy (mapv #(t-get t %) (range n0))]
    (reduce (fn [t i] (t-set t i (nth copy (brev n i)))) t (range n0))))

(defn inverse-dct!
  "spec #Inverse DCT process (7.13.2.3): in-place inverse DCT of permuted
   array T (length 2^n, 2<=n<=6). `r` is the intermediate clamping range.
   Transcribed step-for-step (all 31 steps, each gated by the same `n`
   condition as the spec) -- see namespace docstring for why the full
   n=2..6 range is transcribed even though av1.decode-block only exercises
   n=5 (TX_32X32)."
  [t n r]
  (let [t (inverse-dct-permute t n)
        ge (fn [k] (>= n k))
        t (if (= n 6) (reduce (fn [t i] (B t (+ 32 i) (- 63 i) (- 63 (* 4 (brev 4 i))) 0 r)) t (range 16)) t)
        t (if (ge 5) (reduce (fn [t i] (B t (+ 16 i) (- 31 i) (+ 6 (bit-shift-left (brev 3 (- 7 i)) 3)) 0 r)) t (range 8)) t)
        t (if (= n 6) (reduce (fn [t i] (H t (+ 32 (* i 2)) (+ 33 (* i 2)) (bit-and i 1) r)) t (range 16)) t)
        t (if (ge 4) (reduce (fn [t i] (B t (+ 8 i) (- 15 i) (+ 12 (bit-shift-left (brev 2 (- 3 i)) 4)) 0 r)) t (range 4)) t)
        t (if (ge 5) (reduce (fn [t i] (H t (+ 16 (* 2 i)) (+ 17 (* 2 i)) (bit-and i 1) r)) t (range 8)) t)
        t (if (= n 6)
            (reduce (fn [t [i j]] (B t (- 62 (* i 4) j) (+ 33 (* i 4) j) (+ 60 (- (* 16 (brev 2 i))) (* 64 j)) 1 r))
                    t (for [i (range 4) j (range 2)] [i j]))
            t)
        t (if (ge 3) (reduce (fn [t i] (B t (+ 4 i) (- 7 i) (- 56 (* 32 i)) 0 r)) t (range 2)) t)
        t (if (ge 4) (reduce (fn [t i] (H t (+ 8 (* 2 i)) (+ 9 (* 2 i)) (bit-and i 1) r)) t (range 4)) t)
        t (if (ge 5)
            (reduce (fn [t [i j]] (B t (- 30 (* 4 i) j) (+ 17 (* 4 i) j) (+ 24 (bit-shift-left j 6) (bit-shift-left (- 1 i) 5)) 1 r))
                    t (for [i (range 2) j (range 2)] [i j]))
            t)
        t (if (= n 6) (reduce (fn [t [i j]] (H t (+ 32 (* i 4) j) (- (+ 35 (* i 4)) j) (bit-and i 1) r)) t (for [i (range 8) j (range 2)] [i j])) t)
        t (reduce (fn [t i] (B t (* 2 i) (inc (* 2 i)) (+ 32 (* 16 i)) (- 1 i) r)) t (range 2))
        t (if (ge 3) (reduce (fn [t i] (H t (+ 4 (* 2 i)) (+ 5 (* 2 i)) i r)) t (range 2)) t)
        t (if (ge 4) (reduce (fn [t i] (B t (- 14 i) (+ 9 i) (+ 48 (* 64 i)) 1 r)) t (range 2)) t)
        t (if (ge 5) (reduce (fn [t [i j]] (H t (+ 16 (* 4 i) j) (- (+ 19 (* 4 i)) j) (bit-and i 1) r)) t (for [i (range 4) j (range 2)] [i j])) t)
        t (if (= n 6) (reduce (fn [t [i j]] (B t (- 61 (* i 8) j) (+ 34 (* i 8) j) (+ 56 (- (* i 32)) (* (bit-shift-right j 1) 64)) 1 r)) t (for [i (range 2) j (range 4)] [i j])) t)
        t (reduce (fn [t i] (H t i (- 3 i) 0 r)) t (range 2))
        t (if (ge 3) (B t 6 5 32 1 r) t)
        t (if (ge 4) (reduce (fn [t [i j]] (H t (+ 8 (* 4 i) j) (- (+ 11 (* 4 i)) j) i r)) t (for [i (range 2) j (range 2)] [i j])) t)
        t (if (ge 5) (reduce (fn [t i] (B t (- 29 i) (+ 18 i) (+ 48 (* (bit-shift-right i 1) 64)) 1 r)) t (range 4)) t)
        t (if (= n 6) (reduce (fn [t [i j]] (H t (+ 32 (* 8 i) j) (- (+ 39 (* 8 i)) j) (bit-and i 1) r)) t (for [i (range 4) j (range 4)] [i j])) t)
        t (if (ge 3) (reduce (fn [t i] (H t i (- 7 i) 0 r)) t (range 4)) t)
        t (if (ge 4) (reduce (fn [t i] (B t (- 13 i) (+ 10 i) 32 1 r)) t (range 2)) t)
        t (if (ge 5) (reduce (fn [t [i j]] (H t (+ 16 (* i 8) j) (- (+ 23 (* i 8)) j) i r)) t (for [i (range 2) j (range 4)] [i j])) t)
        t (if (= n 6) (reduce (fn [t i] (B t (- 59 i) (+ 36 i) (if (< i 4) 48 112) 1 r)) t (range 8)) t)
        t (if (ge 4) (reduce (fn [t i] (H t i (- 15 i) 0 r)) t (range 8)) t)
        t (if (ge 5) (reduce (fn [t i] (B t (- 27 i) (+ 20 i) 32 1 r)) t (range 4)) t)
        t (if (= n 6) (reduce (fn [t i] (-> t (H (+ 32 i) (- 47 i) 0 r) (H (+ 48 i) (- 63 i) 1 r))) t (range 8)) t)
        t (if (ge 5) (reduce (fn [t i] (H t i (- 31 i) 0 r)) t (range 16)) t)
        t (if (= n 6) (reduce (fn [t i] (B t (- 55 i) (+ 40 i) 32 1 r)) t (range 8)) t)
        t (if (= n 6) (reduce (fn [t i] (H t i (- 63 i) 0 r)) t (range 32)) t)]
    t))

(defn- clip3 [lo hi v] (cond (< v lo) lo (> v hi) hi :else v))

(defn dequantize
  "spec #Reconstruct process, step 1 (dequantization only -- the inverse
   transform is `inverse-transform-2d` below). `quant` is a flat vector of
   length tw*th (row-major, Quant[i*tw+j]) already de-scanned into raster
   order by the caller. Returns Dequant as a flat row-major vector of the
   same length.

   SCOPE: `using-qmatrix` must be 0 (quantizer matrices not implemented --
   av1.decode-block throws before calling this fn otherwise); DC/AC
   quantizer values (`dc-quant`/`ac-quant`, already looked up by the caller
   via av1.tables/Dc-Qlookup-8bit / Ac-Qlookup-8bit) are plain scalars, no
   q2/Quantizer_Matrix scaling term."
  [quant tw th dq-denom dc-quant ac-quant bit-depth]
  (let [clip-lo (- (bit-shift-left 1 (+ 7 bit-depth)))
        clip-hi (dec (bit-shift-left 1 (+ 7 bit-depth)))]
    (mapv (fn [idx]
            (let [q (if (zero? idx) dc-quant ac-quant)
                  dq (* (nth quant idx) q)
                  sign (if (neg? dq) -1 1)
                  dq2 (quot (* sign (bit-and (abs dq) 0xFFFFFF)) dq-denom)]
              (clip3 clip-lo clip-hi dq2)))
          (range (* tw th)))))

(defn- make-row
  "Row `row-idx` of the w-wide row-transform input T[j], spec step 1: `T[j]
   = Dequant[i][j]` if i<32 and j<32 (both bounded by `th`/`tw` = Min(32,h)/
   Min(32,w)), else 0. `dequant-2d` (from `dequantize`) only has `tw*th`
   elements, so `row-idx >= th` (only reachable for the h>32 rectangular
   sizes this phase doesn't support, see av1.decode-block) must short-
   circuit to an all-zero row rather than index out of bounds."
  [dequant-2d w row-idx tw th]
  (if (>= row-idx th)
    (vec (repeat w 0))
    (mapv (fn [j] (if (< j tw) (nth dequant-2d (+ (* row-idx tw) j)) 0)) (range w))))

(defn inverse-transform-2d
  "spec #2D inverse transform process (7.13.3), DCT_DCT only (row and
   column transform both invoke inverse-dct!; av1.decode-block throws
   before calling this fn for any other PlaneTxType/Lossless combination).
   `dequant` is the flat row-major tw*th Dequant array from `dequantize`
   (tw = th = Min(32,w) -- for this phase's only supported size, TX_32X32,
   tw==th==w==h==32 so no zero-padding is actually exercised, but the
   general w>32/h>32 zero-padding step is still implemented for fidelity).
   Returns Residual as a flat row-major w*h vector.

   log2W/log2H equal (square transform only, matches this phase's TX_32X32
   scope) so the `Abs(log2W-log2H)==1` rectangular-transform rescale step
   is never exercised and is omitted (av1.decode-block only calls this fn
   for square transforms)."
  [dequant log2W log2H bit-depth]
  (let [w (bit-shift-left 1 log2W) h (bit-shift-left 1 log2H)
        tw (min 32 w) th (min 32 h)
        row-shift (case log2W 2 0 3 1 4 2 5 2 6 2)
        col-shift 4
        row-clamp (+ bit-depth 8)
        col-clamp (max (+ bit-depth 6) 16)
        ;; row transforms
        row-residual
        (mapv (fn [i]
                (let [row (make-row dequant w i tw th)
                      t0 (transient row)
                      t1 (inverse-dct! t0 log2W row-clamp)
                      out (persistent! t1)]
                  (mapv #(round2 % row-shift) out)))
              (range h))
        ;; clip between row and column transforms
        clipped (mapv (fn [row] (mapv #(clip3 (- (bit-shift-left 1 (dec col-clamp))) (dec (bit-shift-left 1 (dec col-clamp))) %) row)) row-residual)
        ;; column transforms
        cols (mapv (fn [j]
                     (let [col (mapv #(nth (nth clipped %) j) (range h))
                           t0 (transient col)
                           t1 (inverse-dct! t0 log2H col-clamp)
                           out (persistent! t1)]
                       (mapv #(round2 % col-shift) out)))
                   (range w))]
    ;; transpose cols (w vectors of length h) back to row-major h x w
    (vec (for [i (range h) j (range w)] (nth (nth cols j) i)))))

(defn dq-denom
  "spec #Reconstruct process: dqDenom by txSz -- TX_32X32/TX_16X32/TX_32X16/
   TX_16X64/TX_64X16 -> 2, TX_64X64/TX_32X64/TX_64X32 -> 4, else 1. This
   phase only supports TX_32X32 (dqDenom==2); the full rule is transcribed
   for fidelity since it's a cheap `cond`."
  [tx-sz]
  (cond
    (contains? #{tables/TX_32X32 tables/TX_16X32 tables/TX_32X16 tables/TX_16X64 tables/TX_64X16} tx-sz) 2
    (contains? #{tables/TX_64X64 tables/TX_32X64 tables/TX_64X32} tx-sz) 4
    :else 1))
