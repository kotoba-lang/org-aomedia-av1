(ns av1.intra-pred
  "AV1 DC intra prediction process, transcribed from AV1 Bitstream &
   Decoding Process Specification section 7.11.2 (\"Intra prediction
   process\") / 7.11.2.5 (\"DC intra prediction process\") --
   AOMediaCodec/av1-spec master, 08.decoding.process.md #Intra prediction
   process / #DC intra prediction process (fetched 2026-07-13).

   SCOPE (Phase 1 continuation, ADR-2607122000 Migration step 9 -- see
   av1.decode-block namespace docstring for the full scope statement):
   DC_PRED only (mode is not a parameter here -- av1.decode-block throws
   before calling this namespace for any other intra mode). 8-bit only.
   Luma-only (plane is implicit -- caller passes the plane's own pixel
   buffer)."
  )

(defn dc-predict
  "spec 7.11.2.5: DC intra prediction. `frame` is a row-major flat vector of
   the plane's *reconstructed-so-far* pixel buffer (width `frame-w`,
   `frame-h` rows -- CurrFrame per spec), `x`/`y` the top-left sample of the
   block to predict, `have-left?`/`have-above?` availability flags (spec's
   haveLeft/haveAbove -- this phase never constructs haveAboveRight/
   haveBelowLeft since the DC formula doesn't need them), `log2W`/`log2H`
   the block's log2 width/height, `bit-depth` (8 in this repo). Returns a
   flat row-major w*h vector of predicted samples.

   maxX/maxY (used to clip the AboveRow/LeftCol read positions per spec)
   are derived from `frame-w`/`frame-h` directly (this repo's plane buffer
   is always sized exactly (MiCols*4) x (MiRows*4), matching spec's
   `(MiCols*MI_SIZE)-1` / `(MiRows*MI_SIZE)-1` for plane 0 -- chroma's
   subsampled maxX/maxY is out of scope, this repo is monochrome/luma-only,
   see av1.decode-block)."
  [frame frame-w frame-h x y have-left? have-above? log2W log2H bit-depth]
  (let [w (bit-shift-left 1 log2W)
        h (bit-shift-left 1 log2H)
        max-x (dec frame-w)
        max-y (dec frame-h)
        px (fn [xx yy] (nth frame (+ (* yy frame-w) xx)))
        left-col (fn [k] (px (dec x) (min max-y (+ y k))))
        above-row (fn [k] (px (min max-x (+ x k)) (dec y)))
        clip1 (fn [v] (let [hi (dec (bit-shift-left 1 bit-depth))]
                        (cond (< v 0) 0 (> v hi) hi :else v)))
        dc-val
        (cond
          (and have-left? have-above?)
          (let [sum (+ (reduce + (map left-col (range h)))
                       (reduce + (map above-row (range w)))
                       (bit-shift-right (+ w h) 1))]
            (quot sum (+ w h)))

          have-left?
          (let [sum (reduce + (map left-col (range h)))]
            (clip1 (bit-shift-right (+ sum (bit-shift-right h 1)) log2H)))

          have-above?
          (let [sum (reduce + (map above-row (range w)))]
            (clip1 (bit-shift-right (+ sum (bit-shift-right w 1)) log2W)))

          :else
          (bit-shift-left 1 (dec bit-depth)))]
    (vec (repeat (* w h) dc-val))))
