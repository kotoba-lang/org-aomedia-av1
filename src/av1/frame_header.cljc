(ns av1.frame-header
  "frame_header_obu() / uncompressed_header() -- AV1 spec section 6.8
   (\"Frame header OBU semantics\"), syntax transcribed from
   06.bitstream.syntax.md #General frame header OBU syntax /
   #Uncompressed header syntax / #Frame size syntax / #Render size syntax /
   #Superres params syntax / #Compute image size function /
   #Tile info syntax / #Tile size calculation function /
   #Quantization params syntax / #Delta quantizer syntax
   (AOMediaCodec/av1-spec master, fetched 2026-07-12).

   SCOPE (Phase 0, ADR-2607122000 Migration step 9): this walks the
   uncompressed_header() bit-exactly from its start through
   quantization_params() (i.e. through base_q_idx and the per-plane delta-Q
   fields) for **intra frames only** (KEY_FRAME / INTRA_ONLY_FRAME with
   FrameIsIntra == 1). segmentation_params() onward (loop filter, CDEF,
   loop restoration, tx mode, skip mode, global motion, film grain) is NOT
   implemented -- deliberately out of scope for Phase 0 (OBU framing + bool
   decoder), left for the pixel-reconstruction phase. Because every OBU is
   self-delimiting via its own leb128 obu_size (see av1.obu), stopping the
   walk partway through a frame_header/frame OBU's payload does not corrupt
   parsing of subsequent OBUs -- av1.obu/parse-all just skips the remaining
   payload bytes byte-exactly.

   Inter frames (frame_type == INTER_FRAME) require decoder state this
   phase does not maintain (RefOrderHint/RefValid/ref_frame_idx across
   frames) and are not supported -- `parse` throws ex-info rather than
   silently mis-parsing."
  (:require [av1.bitreader :as br]))

(def KEY_FRAME 0)
(def INTER_FRAME 1)
(def INTRA_ONLY_FRAME 2)
(def SWITCH_FRAME 3)

(def PRIMARY_REF_NONE 7)
(def SELECT_SCREEN_CONTENT_TOOLS 2)
(def SELECT_INTEGER_MV 2)

(def SUPERRES_NUM 8)
(def SUPERRES_DENOM_MIN 9)
(def SUPERRES_DENOM_BITS 3)

(def MAX_TILE_WIDTH 4096)
(def MAX_TILE_AREA (* 4096 2304))
(def MAX_TILE_ROWS 64)
(def MAX_TILE_COLS 64)

(defn- tile-log2
  "spec #Tile size calculation function: smallest k such that
   blkSize << k >= target."
  [blk-size target]
  (loop [k 0]
    (if (< (bit-shift-left blk-size k) target) (recur (inc k)) k)))

(defn- parse-superres-params
  "spec #Superres params syntax."
  [r {:keys [enable-superres frame-width]}]
  (let [[use-superres r1] (if (= enable-superres 1) (br/f r 1) [0 r])
        [superres-denom r2]
        (if (= use-superres 1)
          (let [[coded-denom r'] (br/f r1 SUPERRES_DENOM_BITS)]
            [(+ coded-denom SUPERRES_DENOM_MIN) r'])
          [SUPERRES_NUM r1])
        upscaled-width frame-width
        frame-width' (quot (+ (* upscaled-width SUPERRES_NUM) (quot superres-denom 2))
                            superres-denom)]
    [{:use-superres use-superres :superres-denom superres-denom
      :upscaled-width upscaled-width :frame-width frame-width'}
     r2]))

(defn- compute-image-size [frame-width frame-height]
  {:mi-cols (* 2 (bit-shift-right (+ frame-width 7) 3))
   :mi-rows (* 2 (bit-shift-right (+ frame-height 7) 3))})

(defn- parse-frame-size
  "spec #Frame size syntax."
  [r {:keys [frame-size-override-flag frame-width-bits-minus-1
             frame-height-bits-minus-1 max-frame-width-minus-1
             max-frame-height-minus-1 enable-superres]}]
  (let [[frame-width frame-height r1]
        (if (= frame-size-override-flag 1)
          (let [[fw1 r'] (br/f r (inc frame-width-bits-minus-1))
                [fh1 r''] (br/f r' (inc frame-height-bits-minus-1))]
            [(inc fw1) (inc fh1) r''])
          [(inc max-frame-width-minus-1) (inc max-frame-height-minus-1) r])
        [superres r2] (parse-superres-params r1 {:enable-superres enable-superres
                                                  :frame-width frame-width})
        img-size (compute-image-size (:frame-width superres) frame-height)]
    [(merge {:frame-height frame-height} superres img-size) r2]))

(defn- parse-render-size
  "spec #Render size syntax."
  [r {:keys [upscaled-width frame-height]}]
  (let [[diff? r1] (br/f r 1)]
    (if (= diff? 1)
      (let [[rw r2] (br/f r1 16)
            [rh r3] (br/f r2 16)]
        [{:render-and-frame-size-different 1
          :render-width (inc rw) :render-height (inc rh)} r3])
      [{:render-and-frame-size-different 0
        :render-width upscaled-width :render-height frame-height} r1])))

(defn- parse-tile-info
  "spec #Tile info syntax."
  [r {:keys [use-128x128-superblock mi-cols mi-rows]}]
  (let [sb-cols (if (= use-128x128-superblock 1)
                  (bit-shift-right (+ mi-cols 31) 5)
                  (bit-shift-right (+ mi-cols 15) 4))
        sb-rows (if (= use-128x128-superblock 1)
                  (bit-shift-right (+ mi-rows 31) 5)
                  (bit-shift-right (+ mi-rows 15) 4))
        sb-shift (if (= use-128x128-superblock 1) 5 4)
        sb-size (+ sb-shift 2)
        max-tile-width-sb (bit-shift-right MAX_TILE_WIDTH sb-size)
        max-tile-area-sb (bit-shift-right MAX_TILE_AREA (* 2 sb-size))
        min-log2-tile-cols (tile-log2 max-tile-width-sb sb-cols)
        max-log2-tile-cols (tile-log2 1 (min sb-cols MAX_TILE_COLS))
        max-log2-tile-rows (tile-log2 1 (min sb-rows MAX_TILE_ROWS))
        min-log2-tiles (max min-log2-tile-cols (tile-log2 max-tile-area-sb (* sb-rows sb-cols)))
        [uniform? r1] (br/f r 1)]
    (if (= uniform? 1)
      (let [[tile-cols-log2 r2]
            (loop [log2 min-log2-tile-cols, r r1]
              (if (< log2 max-log2-tile-cols)
                (let [[inc? r'] (br/f r 1)]
                  (if (= inc? 1) (recur (inc log2) r') [log2 r']))
                [log2 r]))
            tile-width-sb (bit-shift-right (+ sb-cols (dec (bit-shift-left 1 tile-cols-log2)))
                                            tile-cols-log2)
            tile-cols (quot (+ sb-cols tile-width-sb -1) tile-width-sb)
            mi-col-starts (conj (mapv #(bit-shift-left (* % tile-width-sb) sb-shift) (range tile-cols))
                                 mi-cols)
            min-log2-tile-rows (max (- min-log2-tiles tile-cols-log2) 0)
            [tile-rows-log2 r3]
            (loop [log2 min-log2-tile-rows, r r2]
              (if (< log2 max-log2-tile-rows)
                (let [[inc? r'] (br/f r 1)]
                  (if (= inc? 1) (recur (inc log2) r') [log2 r']))
                [log2 r]))
            tile-height-sb (bit-shift-right (+ sb-rows (dec (bit-shift-left 1 tile-rows-log2)))
                                             tile-rows-log2)
            tile-rows (quot (+ sb-rows tile-height-sb -1) tile-height-sb)
            mi-row-starts (conj (mapv #(bit-shift-left (* % tile-height-sb) sb-shift) (range tile-rows))
                                 mi-rows)
            [context-update-tile-id tile-size-bytes r4]
            (if (or (> tile-cols-log2 0) (> tile-rows-log2 0))
              (let [[cuti r'] (br/f r3 (+ tile-rows-log2 tile-cols-log2))
                    [tsb-minus-1 r''] (br/f r' 2)]
                [cuti (inc tsb-minus-1) r''])
              [0 nil r3])]
        [{:uniform-tile-spacing-flag 1
          :tile-cols-log2 tile-cols-log2 :tile-rows-log2 tile-rows-log2
          :tile-cols tile-cols :tile-rows tile-rows
          :mi-col-starts mi-col-starts :mi-row-starts mi-row-starts
          :context-update-tile-id context-update-tile-id
          :tile-size-bytes tile-size-bytes}
         r4])
      ;; non-uniform spacing: ns(n)-coded per-tile sizes.
      (let [[tile-cols r2 tile-cols-log2 widest-tile-sb mi-col-starts]
            (loop [i 0, start-sb 0, r r1, widest 0, starts []]
              (if (< start-sb sb-cols)
                (let [max-width (min (- sb-cols start-sb) max-tile-width-sb)
                      [w-minus-1 r'] (br/ns r max-width)
                      size-sb (inc w-minus-1)]
                  (recur (inc i) (+ start-sb size-sb) r' (max size-sb widest)
                         (conj starts (bit-shift-left start-sb sb-shift))))
                [i r (tile-log2 1 i) widest (conj starts mi-cols)]))
            max-tile-area-sb' (if (> min-log2-tiles 0)
                                 (bit-shift-right (* sb-rows sb-cols) (inc min-log2-tiles))
                                 (* sb-rows sb-cols))
            max-tile-height-sb (max (quot max-tile-area-sb' widest-tile-sb) 1)
            [tile-rows r3 tile-rows-log2 mi-row-starts]
            (loop [i 0, start-sb 0, r r2, widest 0, starts []]
              (if (< start-sb sb-rows)
                (let [max-height (min (- sb-rows start-sb) max-tile-height-sb)
                      [h-minus-1 r'] (br/ns r max-height)
                      size-sb (inc h-minus-1)]
                  (recur (inc i) (+ start-sb size-sb) r' (max size-sb widest)
                         (conj starts (bit-shift-left start-sb sb-shift))))
                [i r (tile-log2 1 i) (conj starts mi-rows)]))
            [context-update-tile-id tile-size-bytes r4]
            (if (or (> tile-cols-log2 0) (> tile-rows-log2 0))
              (let [[cuti r'] (br/f r3 (+ tile-rows-log2 tile-cols-log2))
                    [tsb-minus-1 r''] (br/f r' 2)]
                [cuti (inc tsb-minus-1) r''])
              [0 nil r3])]
        [{:uniform-tile-spacing-flag 0
          :tile-cols-log2 tile-cols-log2 :tile-rows-log2 tile-rows-log2
          :tile-cols tile-cols :tile-rows tile-rows
          :mi-col-starts mi-col-starts :mi-row-starts mi-row-starts
          :context-update-tile-id context-update-tile-id
          :tile-size-bytes tile-size-bytes}
         r4]))))

(defn- read-delta-q
  "spec #Delta quantizer syntax."
  [r]
  (let [[delta-coded r1] (br/f r 1)]
    (if (= delta-coded 1)
      (br/su r1 7)
      [0 r1])))

(defn- parse-quantization-params
  "spec #Quantization params syntax. `num-planes`/`separate-uv-delta-q`
   come from the sequence header's color_config(). NOTE: the
   using_qmatrix/qm_y/qm_u/qm_v block is unconditional on NumPlanes per the
   spec table (it sits *after* the NumPlanes>1 if/else, not inside it) --
   qm_y and qm_u are both always read when using_qmatrix, even for
   mono_chrome (NumPlanes==1) sequences."
  [r {:keys [num-planes separate-uv-delta-q]}]
  (let [[base-q-idx r1] (br/f r 8)
        [delta-q-y-dc r2] (read-delta-q r1)
        [diff-uv-delta delta-q-u-dc delta-q-u-ac delta-q-v-dc delta-q-v-ac r3]
        (if (> num-planes 1)
          (let [[duv r'] (if (= separate-uv-delta-q 1) (br/f r2 1) [0 r2])
                [udc r''] (read-delta-q r')
                [uac r'''] (read-delta-q r'')
                [vdc vac r'''']
                (if (= duv 1)
                  (let [[v1 r5] (read-delta-q r''')
                        [v2 r6] (read-delta-q r5)]
                    [v1 v2 r6])
                  [udc uac r'''])]
            [duv udc uac vdc vac r''''])
          [nil 0 0 0 0 r2])
        [using-qmatrix r4] (br/f r3 1)
        [qm-y qm-u qm-v r5]
        (if (= using-qmatrix 1)
          (let [[y r'] (br/f r4 4)
                [u r''] (br/f r' 4)
                [v r'''] (if (= separate-uv-delta-q 0) [u r''] (br/f r'' 4))]
            [y u v r'''])
          [nil nil nil r4])]
    [{:base-q-idx base-q-idx :delta-q-y-dc delta-q-y-dc
      :diff-uv-delta diff-uv-delta
      :delta-q-u-dc delta-q-u-dc :delta-q-u-ac delta-q-u-ac
      :delta-q-v-dc delta-q-v-dc :delta-q-v-ac delta-q-v-ac
      :using-qmatrix using-qmatrix :qm-y qm-y :qm-u qm-u :qm-v qm-v}
     r5]))

;; -- segmentation_params() / delta_q_params() / delta_lf_params() -- spec
;; #Segmentation params syntax / #Quantizer index delta parameters syntax /
;; #Loop filter delta parameters syntax. MAX_SEGMENTS/SEG_LVL_* from spec
;; section 3 ("Symbols and abbreviated terms").

(def MAX_SEGMENTS 8)
(def SEG_LVL_ALT_Q 0)
(def SEG_LVL_MAX 8)
(def Segmentation_Feature_Bits [8 6 6 6 6 3 0 0])
(def Segmentation_Feature_Signed [1 1 1 1 1 0 0 0])
(def MAX_LOOP_FILTER 63)
(def Segmentation_Feature_Max [255 MAX_LOOP_FILTER MAX_LOOP_FILTER MAX_LOOP_FILTER MAX_LOOP_FILTER 7 0 0])

(defn- clip3 [lo hi v] (max lo (min hi v)))

(defn- parse-segmentation-params
  "spec #Segmentation params syntax. SCOPE: `primary-ref-frame` is always
   PRIMARY_REF_NONE for the intra frames this phase supports (frame-header/
   parse only ever sets primary-ref-frame to PRIMARY_REF_NONE when
   frame-is-intra?), so the `segmentation_update_map`/
   `segmentation_temporal_update`/`segmentation_update_data` bits (only read
   on the `primary_ref_frame != PRIMARY_REF_NONE` path) are never reached --
   throws if that invariant is ever violated rather than silently
   mis-parsing. When segmentation_enabled == 1, segmentation_update_data is
   therefore always 1 and the FeatureEnabled/FeatureData table is always
   read in full."
  [r {:keys [primary-ref-frame]}]
  (let [[segmentation-enabled r1] (br/f r 1)]
    (if (zero? segmentation-enabled)
      [{:segmentation-enabled 0
        :feature-enabled (vec (repeat MAX_SEGMENTS (vec (repeat SEG_LVL_MAX 0))))
        :feature-data (vec (repeat MAX_SEGMENTS (vec (repeat SEG_LVL_MAX 0))))}
       r1]
      (let [_ (when-not (= primary-ref-frame PRIMARY_REF_NONE)
                (throw (ex-info "segmentation_params(): primary_ref_frame != PRIMARY_REF_NONE not supported (needs cross-frame segmentation-map state, out of Phase 0/1 scope)"
                                 {:primary-ref-frame primary-ref-frame})))
            [feature-enabled feature-data r2]
            (loop [i 0, r r1, fe [], fd []]
              (if (>= i MAX_SEGMENTS)
                [fe fd r]
                (let [[fe-row fd-row r']
                      (loop [j 0, rr r, fe-row [], fd-row []]
                        (if (>= j SEG_LVL_MAX)
                          [fe-row fd-row rr]
                          (let [[feature-enabled? ra] (br/f rr 1)
                                [clipped-value rb]
                                (if (= feature-enabled? 1)
                                  (let [bits (nth Segmentation_Feature_Bits j)
                                        limit (nth Segmentation_Feature_Max j)
                                        signed? (= 1 (nth Segmentation_Feature_Signed j))]
                                    (if signed?
                                      (let [[v r''] (br/su ra (inc bits))]
                                        [(clip3 (- limit) limit v) r''])
                                      (let [[v r''] (br/f ra bits)]
                                        [(clip3 0 limit v) r''])))
                                  [0 ra])]
                            (recur (inc j) rb (conj fe-row feature-enabled?) (conj fd-row clipped-value)))))]
                  (recur (inc i) r' (conj fe fe-row) (conj fd fd-row)))))]
        [{:segmentation-enabled 1 :feature-enabled feature-enabled :feature-data feature-data}
         r2]))))

(defn- parse-delta-q-params
  "spec #Quantizer index delta parameters syntax."
  [r base-q-idx]
  (if (pos? base-q-idx)
    (let [[delta-q-present r1] (br/f r 1)
          [delta-q-res r2] (if (= delta-q-present 1) (br/f r1 2) [0 r1])]
      [{:delta-q-present delta-q-present :delta-q-res delta-q-res} r2])
    [{:delta-q-present 0 :delta-q-res 0} r]))

(defn- parse-delta-lf-params
  "spec #Loop filter delta parameters syntax."
  [r {:keys [delta-q-present allow-intrabc]}]
  (if (= delta-q-present 1)
    (let [[delta-lf-present r1] (if (zero? allow-intrabc) (br/f r 1) [0 r])
          [delta-lf-res delta-lf-multi r2]
          (if (= delta-lf-present 1)
            (let [[res r'] (br/f r1 2)
                  [multi r''] (br/f r' 1)]
              [res multi r''])
            [0 0 r1])]
      [{:delta-lf-present delta-lf-present :delta-lf-res delta-lf-res :delta-lf-multi delta-lf-multi} r2])
    [{:delta-lf-present 0 :delta-lf-res 0 :delta-lf-multi 0} r]))

(defn- seg-feature-active? [feature-enabled segment-id feature-idx]
  (= 1 (get-in feature-enabled [segment-id feature-idx])))

(defn- get-qindex-ignore-delta-q
  "spec `get_qindex(1, segmentId)` -- the ignoreDeltaQ=1 branch only, which
   is the only branch uncompressed_header()'s CodedLossless/AllLossless
   computation needs. The ignoreDeltaQ=0 branch (CurrentQIndex, tracked
   per-superblock via delta_q) belongs to decode_block()'s residual decode
   path, out of Phase 0/1 scope."
  [base-q-idx feature-enabled feature-data segment-id]
  (if (seg-feature-active? feature-enabled segment-id SEG_LVL_ALT_Q)
    (clip3 0 255 (+ base-q-idx (get-in feature-data [segment-id SEG_LVL_ALT_Q])))
    base-q-idx))

(defn- compute-lossless
  "spec #Uncompressed header syntax, the `CodedLossless`/`AllLossless` loop
   right after delta_lf_params() (`for segmentId ... LosslessArray[segmentId]
   = qindex==0 && DeltaQYDc==0 && ...`)."
  [{:keys [base-q-idx delta-q-y-dc delta-q-u-dc delta-q-u-ac delta-q-v-dc delta-q-v-ac]}
   {:keys [feature-enabled feature-data]}
   frame-width upscaled-width]
  (let [lossless-array
        (mapv (fn [segment-id]
                (let [qindex (get-qindex-ignore-delta-q base-q-idx feature-enabled feature-data segment-id)]
                  (and (zero? qindex) (zero? delta-q-y-dc) (zero? delta-q-u-ac)
                       (zero? delta-q-u-dc) (zero? delta-q-v-ac) (zero? delta-q-v-dc))))
              (range MAX_SEGMENTS))
        coded-lossless? (every? true? lossless-array)]
    {:lossless-array lossless-array
     :coded-lossless (if coded-lossless? 1 0)
     :all-lossless (if (and coded-lossless? (= frame-width upscaled-width)) 1 0)}))

;; -- loop_filter_params() -- spec #Loop filter params syntax. Ref-frame enum
;; order (needed for the loop_filter_ref_deltas default/index order) is
;; 07.bitstream.semantics.md's "RefFrame[0]" table: INTRA_FRAME=0
;; LAST_FRAME=1 LAST2_FRAME=2 LAST3_FRAME=3 GOLDEN_FRAME=4 BWDREF_FRAME=5
;; ALTREF2_FRAME=6 ALTREF_FRAME=7.

(def TOTAL_REFS_PER_FRAME 8)
(def Loop_Filter_Ref_Deltas_Default [1 0 0 0 -1 0 -1 -1])
(def Loop_Filter_Mode_Deltas_Default [0 0])

(defn- read-delta-updates
  "Shared shape for the loop_filter_params() `update_ref_delta`/
   `update_mode_delta` loops: f(1) flag, and if set, su(1+6) overwriting the
   slot; if unset, the slot keeps its prior (default) value."
  [r n defaults]
  (loop [i 0, r r, acc defaults]
    (if (>= i n)
      [acc r]
      (let [[upd ra] (br/f r 1)]
        (if (= upd 1)
          (let [[v rb] (br/su ra 7)]
            (recur (inc i) rb (assoc acc i v)))
          (recur (inc i) ra acc))))))

(defn- parse-loop-filter-params
  [r {:keys [coded-lossless allow-intrabc num-planes]}]
  (if (or (= coded-lossless 1) (= allow-intrabc 1))
    [{:loop-filter-level [0 0 0 0] :loop-filter-sharpness 0 :loop-filter-delta-enabled 0
      :loop-filter-ref-deltas Loop_Filter_Ref_Deltas_Default
      :loop-filter-mode-deltas Loop_Filter_Mode_Deltas_Default}
     r]
    (let [[lvl0 r1] (br/f r 6)
          [lvl1 r2] (br/f r1 6)
          [lvl2 lvl3 r3]
          (if (and (> num-planes 1) (or (pos? lvl0) (pos? lvl1)))
            (let [[l2 r'] (br/f r2 6) [l3 r''] (br/f r' 6)] [l2 l3 r''])
            [0 0 r2])
          [sharpness r4] (br/f r3 3)
          [delta-enabled r5] (br/f r4 1)
          [ref-deltas mode-deltas r9]
          (if (= delta-enabled 1)
            (let [[delta-update r6] (br/f r5 1)]
              (if (= delta-update 1)
                (let [[rd r7] (read-delta-updates r6 TOTAL_REFS_PER_FRAME Loop_Filter_Ref_Deltas_Default)
                      [md r8] (read-delta-updates r7 2 Loop_Filter_Mode_Deltas_Default)]
                  [rd md r8])
                [Loop_Filter_Ref_Deltas_Default Loop_Filter_Mode_Deltas_Default r6]))
            [Loop_Filter_Ref_Deltas_Default Loop_Filter_Mode_Deltas_Default r5])]
      [{:loop-filter-level [lvl0 lvl1 lvl2 lvl3] :loop-filter-sharpness sharpness
        :loop-filter-delta-enabled delta-enabled
        :loop-filter-ref-deltas ref-deltas :loop-filter-mode-deltas mode-deltas}
       r9])))

(defn- parse-cdef-params
  "spec #CDEF params syntax."
  [r {:keys [coded-lossless allow-intrabc enable-cdef num-planes]}]
  (if (or (= coded-lossless 1) (= allow-intrabc 1) (zero? enable-cdef))
    [{:cdef-bits 0 :cdef-damping 3
      :cdef-y-pri-strength [0] :cdef-y-sec-strength [0]
      :cdef-uv-pri-strength [0] :cdef-uv-sec-strength [0]}
     r]
    (let [[damping-minus-3 r1] (br/f r 2)
          [cdef-bits r2] (br/f r1 2)
          n (bit-shift-left 1 cdef-bits)
          [y-pri y-sec uv-pri uv-sec r3]
          (loop [i 0, r r2, yp [], ys [], up [], us []]
            (if (>= i n)
              [yp ys up us r]
              (let [[p ra] (br/f r 4)
                    [s0 rb] (br/f ra 2)
                    s (if (= s0 3) 4 s0)]
                (if (> num-planes 1)
                  (let [[up0 rc] (br/f rb 4)
                        [us0 rd] (br/f rc 2)
                        us1 (if (= us0 3) 4 us0)]
                    (recur (inc i) rd (conj yp p) (conj ys s) (conj up up0) (conj us us1)))
                  (recur (inc i) rb (conj yp p) (conj ys s) up us)))))]
      [{:cdef-damping (+ damping-minus-3 3) :cdef-bits cdef-bits
        :cdef-y-pri-strength y-pri :cdef-y-sec-strength y-sec
        :cdef-uv-pri-strength uv-pri :cdef-uv-sec-strength uv-sec}
       r3])))

;; Remap_Lr_Type: lr_type (0..3) -> FrameRestorationType code. RESTORE_NONE=0
;; RESTORE_WIENER=1 RESTORE_SGRPROJ=2 RESTORE_SWITCHABLE=3 (07.bitstream.
;; semantics.md's lr_type table); Remap_Lr_Type = {NONE, SWITCHABLE, WIENER, SGRPROJ}.
(def Remap_Lr_Type [0 3 1 2])
(def RESTORATION_TILESIZE_MAX 256)

(defn- parse-lr-params
  "spec #Loop restoration params syntax."
  [r {:keys [all-lossless allow-intrabc enable-restoration use-128x128-superblock
             subsampling-x subsampling-y num-planes]}]
  (if (or (= all-lossless 1) (= allow-intrabc 1) (zero? enable-restoration))
    [{:frame-restoration-type [0 0 0] :uses-lr 0} r]
    (let [[frame-restoration-type uses-chroma-lr? r1]
          (loop [i 0, r r, frt [], uses-chroma? false]
            (if (>= i num-planes)
              [frt uses-chroma? r]
              (let [[lr-type r'] (br/f r 2)
                    rt (nth Remap_Lr_Type lr-type)]
                (recur (inc i) r' (conj frt rt)
                       (or uses-chroma? (and (pos? i) (not= rt 0)))))))
          frame-restoration-type' (into frame-restoration-type (repeat (- 3 (count frame-restoration-type)) 0))
          uses-lr? (boolean (some #(not= % 0) frame-restoration-type))
          [lr-unit-shift lr-uv-shift lr-sizes r2]
          (if uses-lr?
            (let [[shift0 r']
                  (if (= use-128x128-superblock 1)
                    (let [[s r''] (br/f r1 1)] [(inc s) r''])
                    (let [[s r''] (br/f r1 1)]
                      (if (= s 1)
                        (let [[extra r'''] (br/f r'' 1)] [(+ s extra) r'''])
                        [s r''])))
                  size0 (bit-shift-right RESTORATION_TILESIZE_MAX (- 2 shift0))
                  [uv-shift r''] (if (and (= subsampling-x 1) (= subsampling-y 1) uses-chroma-lr?)
                                   (br/f r' 1)
                                   [0 r'])
                  size1 (bit-shift-right size0 uv-shift)]
              [shift0 uv-shift [size0 size1 size1] r''])
            [nil nil [nil nil nil] r1])]
      [{:frame-restoration-type frame-restoration-type' :uses-lr (if uses-lr? 1 0)
        :lr-unit-shift lr-unit-shift :lr-uv-shift lr-uv-shift :loop-restoration-size lr-sizes}
       r2])))

(defn- read-tx-mode
  "spec #TX mode syntax."
  [r coded-lossless]
  (if (= coded-lossless 1)
    [{:tx-mode :only-4x4} r]
    (let [[tx-mode-select r'] (br/f r 1)]
      [{:tx-mode (if (= tx-mode-select 1) :tx-mode-select :tx-mode-largest)} r'])))

;; frame_reference_mode() / skip_mode_params() / global_motion_params(): spec
;; #Frame reference mode syntax / #Skip mode params syntax / #Global motion
;; params syntax. All three read ZERO bits whenever FrameIsIntra == 1 (their
;; entire body is `if (FrameIsIntra) { <defaults, return> }` before any
;; `@@`-marked syntax element) -- since Phase 0/1's `frame-is-intra?` is
;; always true (inter frames throw earlier in `parse`), none of these are
;; ever reached in this phase; kept as explicit no-op passthroughs (rather
;; than inlined constants) so the correspondence to the spec's function
;; names/scope stays visible and the throw-on-violation guard has somewhere
;; to live if inter-frame support is added later.
(defn- frame-reference-mode [frame-is-intra?]
  (when-not frame-is-intra?
    (throw (ex-info "frame_reference_mode(): !FrameIsIntra not supported (out of Phase 0/1 scope)" {})))
  {:reference-select 0})

(defn- skip-mode-params [frame-is-intra?]
  (when-not frame-is-intra?
    (throw (ex-info "skip_mode_params(): !FrameIsIntra not supported (out of Phase 0/1 scope)" {})))
  {:skip-mode-present 0})

(defn- global-motion-params [frame-is-intra?]
  (when-not frame-is-intra?
    (throw (ex-info "global_motion_params(): !FrameIsIntra not supported (out of Phase 0/1 scope)" {})))
  {:gm-type (vec (repeat 8 :identity))})

(defn- read-points-loop
  "Shared shape for film_grain_params()'s `point_*_value`/`point_*_scaling`
   f(8)/f(8) loops (point_y/point_cb/point_cr)."
  [r n]
  (loop [i 0, r r, acc []]
    (if (>= i n)
      [acc r]
      (let [[v ra] (br/f r 8)
            [s rb] (br/f ra 8)]
        (recur (inc i) rb (conj acc [v s]))))))

(defn- read-coeffs-loop [r n]
  (loop [i 0, r r, acc []]
    (if (>= i n)
      [acc r]
      (let [[v r'] (br/f r 8)] (recur (inc i) r' (conj acc v))))))

(defn- parse-film-grain-params
  "spec #Film grain params syntax. SCOPE: `update_grain` is unconditionally 1
   here -- frame_type is always KEY_FRAME/INTRA_ONLY_FRAME in Phase 0/1 scope
   (never INTER_FRAME, `parse` throws earlier otherwise), so the
   update_grain==0 / film_grain_params_ref_idx / load_grain_params()
   cross-frame-grain-state branch is structurally unreachable and not
   implemented."
  [r {:keys [film-grain-params-present show-frame showable-frame mono-chrome
             subsampling-x subsampling-y]}]
  (if (or (zero? film-grain-params-present)
          (and (zero? show-frame) (zero? showable-frame)))
    [{:apply-grain 0} r]
    (let [[apply-grain r1] (br/f r 1)]
      (if (zero? apply-grain)
        [{:apply-grain 0} r1]
        (let [[grain-seed r2] (br/f r1 16)
              [num-y-points r3] (br/f r2 4)
              [y-points r4] (read-points-loop r3 num-y-points)
              [chroma-scaling-from-luma r5]
              (if (= mono-chrome 1) [0 r4] (br/f r4 1))
              simple-chroma? (or (= mono-chrome 1) (= chroma-scaling-from-luma 1)
                                  (and (= subsampling-x 1) (= subsampling-y 1) (zero? num-y-points)))
              [num-cb-points cb-points num-cr-points cr-points r6]
              (if simple-chroma?
                [0 [] 0 [] r5]
                (let [[ncb r'] (br/f r5 4)
                      [cbp r''] (read-points-loop r' ncb)
                      [ncr r'''] (br/f r'' 4)
                      [crp r''''] (read-points-loop r''' ncr)]
                  [ncb cbp ncr crp r'''']))
              [grain-scaling-minus-8 r7] (br/f r6 2)
              [ar-coeff-lag r8] (br/f r7 2)
              num-pos-luma (* 2 ar-coeff-lag (inc ar-coeff-lag))
              [ar-coeffs-y r9 num-pos-chroma]
              (if (pos? num-y-points)
                (let [[coeffs r'] (read-coeffs-loop r8 num-pos-luma)]
                  [coeffs r' (inc num-pos-luma)])
                [[] r8 num-pos-luma])
              [ar-coeffs-cb r10]
              (if (or (= chroma-scaling-from-luma 1) (pos? num-cb-points))
                (read-coeffs-loop r9 num-pos-chroma)
                [[] r9])
              [ar-coeffs-cr r11]
              (if (or (= chroma-scaling-from-luma 1) (pos? num-cr-points))
                (read-coeffs-loop r10 num-pos-chroma)
                [[] r10])
              [ar-coeff-shift-minus-6 r12] (br/f r11 2)
              [grain-scale-shift r13] (br/f r12 2)
              [cb-mult cb-luma-mult cb-offset r14]
              (if (pos? num-cb-points)
                (let [[m r'] (br/f r13 8) [lm r''] (br/f r' 8) [o r'''] (br/f r'' 9)]
                  [m lm o r'''])
                [nil nil nil r13])
              [cr-mult cr-luma-mult cr-offset r15]
              (if (pos? num-cr-points)
                (let [[m r'] (br/f r14 8) [lm r''] (br/f r' 8) [o r'''] (br/f r'' 9)]
                  [m lm o r'''])
                [nil nil nil r14])
              [overlap-flag r16] (br/f r15 1)
              [clip-to-restricted-range r17] (br/f r16 1)]
          [{:apply-grain 1 :grain-seed grain-seed :update-grain 1
            :num-y-points num-y-points :y-points y-points
            :chroma-scaling-from-luma chroma-scaling-from-luma
            :num-cb-points num-cb-points :cb-points cb-points
            :num-cr-points num-cr-points :cr-points cr-points
            :grain-scaling-minus-8 grain-scaling-minus-8
            :ar-coeff-lag ar-coeff-lag
            :ar-coeffs-y ar-coeffs-y :ar-coeffs-cb ar-coeffs-cb :ar-coeffs-cr ar-coeffs-cr
            :ar-coeff-shift-minus-6 ar-coeff-shift-minus-6
            :grain-scale-shift grain-scale-shift
            :cb-mult cb-mult :cb-luma-mult cb-luma-mult :cb-offset cb-offset
            :cr-mult cr-mult :cr-luma-mult cr-luma-mult :cr-offset cr-offset
            :overlap-flag overlap-flag :clip-to-restricted-range clip-to-restricted-range}
           r17])))))

(defn parse
  "Parse uncompressed_header() through quantization_params() for an intra
   frame (KEY_FRAME or INTRA_ONLY_FRAME). `reader` must be positioned at
   the start of the frame_header_obu()/frame_obu() payload. `seq-hdr` is
   the map returned by av1.sequence-header/parse for the active sequence.

   Returns a map of the fields read (frame-type, show-frame,
   error-resilient-mode, disable-cdf-update, allow-screen-content-tools,
   force-integer-mv, order-hint, primary-ref-frame, refresh-frame-flags,
   frame-width/frame-height/upscaled-width/render-width/render-height,
   tile-info, base-q-idx and the other quantization_params() fields).

   Throws ex-info for show_existing_frame == 1 (needs cross-frame
   RefFrameType state not tracked here) and for inter frames (needs
   cross-frame reference-frame state not tracked here) -- both explicitly
   out of Phase 0 scope.

   `temporal-id`/`spatial-id` (default 0) come from the OBU extension
   header (av1.obu), needed only for the decoder_model_info
   buffer_removal_time in/out-of-layer check."
  ([reader seq-hdr] (parse reader seq-hdr 0 0))
  ([reader seq-hdr temporal-id spatial-id]
  (let [{:keys [reduced-still-picture-header enable-order-hint order-hint-bits
                seq-force-screen-content-tools seq-force-integer-mv
                frame-id-numbers-present-flag]} seq-hdr
        [show-existing-frame frame-type show-frame showable-frame error-resilient-mode r1]
        (if (= reduced-still-picture-header 1)
          [0 KEY_FRAME 1 0 nil reader]
          (let [[sef ra] (br/f reader 1)]
            (if (= sef 1)
              (throw (ex-info "show_existing_frame == 1 not supported (needs cross-frame RefFrameType state, out of Phase 0 scope)"
                               {:show-existing-frame 1}))
              (let [[ft rb] (br/f ra 2)
                    [sf rc] (br/f rb 1)
                    ;; spec: `if (show_frame && decoder_model_info_present_flag &&
                    ;; !equal_picture_interval) temporal_point_info()` reads
                    ;; frame_presentation_time f(n) before the showable_frame
                    ;; decision -- not implemented (needs
                    ;; frame_presentation_time_length_minus_1 plumbing this phase
                    ;; doesn't carry), so throw rather than silently mis-parse.
                    _ (when (and (= sf 1)
                                 (= (:decoder-model-info-present-flag seq-hdr) 1)
                                 (not= (get-in seq-hdr [:timing-info :equal-picture-interval]) 1))
                        (throw (ex-info "temporal_point_info() not supported (decoder_model_info_present_flag && !equal_picture_interval, out of Phase 0/1 scope)"
                                         {:decoder-model-info-present-flag (:decoder-model-info-present-flag seq-hdr)})))
                    ;; spec: `if (show_frame) showable_frame = frame_type !=
                    ;; KEY_FRAME; else @@showable_frame f(1)`. Phase 0 never read
                    ;; this bit (silently correct only because the Phase 0 fixture
                    ;; always has show_frame==1) -- fixed here since every field
                    ;; after this one depends on the bit position being exact.
                    [sfable rd] (if (= sf 1)
                                  [(if (= ft KEY_FRAME) 0 1) rc]
                                  (br/f rc 1))
                    [erm re] (if (or (= ft SWITCH_FRAME) (and (= ft KEY_FRAME) (= sf 1)))
                               [1 rd]
                               (br/f rd 1))]
                [0 ft sf sfable erm re]))))
        frame-is-intra? (or (= frame-type INTRA_ONLY_FRAME) (= frame-type KEY_FRAME))]
    (when-not frame-is-intra?
      (throw (ex-info "inter frames (frame_type == INTER_FRAME) not supported (needs cross-frame reference-frame state, out of Phase 0 scope)"
                       {:frame-type frame-type})))
    (let [[disable-cdf-update r2] (br/f r1 1)
          [allow-screen-content-tools r3]
          (if (= seq-force-screen-content-tools SELECT_SCREEN_CONTENT_TOOLS)
            (br/f r2 1)
            [seq-force-screen-content-tools r2])
          [_force-integer-mv-raw r4]
          (if (= allow-screen-content-tools 1)
            (if (= seq-force-integer-mv SELECT_INTEGER_MV)
              (br/f r3 1)
              [seq-force-integer-mv r3])
            [0 r3])
          ;; FrameIsIntra forces force_integer_mv = 1 regardless of the above.
          _force-integer-mv 1
          id-len (when (= frame-id-numbers-present-flag 1)
                   (+ (:additional-frame-id-length-minus-1 seq-hdr)
                      (:delta-frame-id-length-minus-2 seq-hdr)
                      3))
          [current-frame-id r5]
          (if (= frame-id-numbers-present-flag 1)
            (br/f r4 id-len)
            [0 r4])
          [frame-size-override-flag r6]
          (if (= frame-type SWITCH_FRAME)
            [1 r5]
            (if (= reduced-still-picture-header 1)
              [0 r5]
              (br/f r5 1)))
          [order-hint r7] (br/f r6 order-hint-bits)
          [primary-ref-frame r8]
          (if (or frame-is-intra? (= error-resilient-mode 1))
            [PRIMARY_REF_NONE r7]
            (br/f r7 3))
          ;; decoder_model_info_present_flag / buffer_removal_time: only
          ;; relevant when the sequence header enabled the decoder model;
          ;; uncommon for simple single-frame encodes. Skipped bit-exactly
          ;; when absent (no bits to read).
          decoder-model-info-present-flag (:decoder-model-info-present-flag seq-hdr)
          [buffer-removal-time-present-flag r9]
          (if (= decoder-model-info-present-flag 1) (br/f r8 1) [0 r8])
          r10
          (if (= buffer-removal-time-present-flag 1)
            (let [ops (:operating-points seq-hdr)
                  brtl (inc (get-in seq-hdr [:decoder-model-info :buffer-removal-time-length-minus-1] 0))]
              (reduce (fn [r op]
                        (let [op-idc (:operating-point-idc op)
                              in-temporal-layer (bit-and (bit-shift-right op-idc temporal-id) 1)
                              in-spatial-layer (bit-and (bit-shift-right op-idc (+ spatial-id 8)) 1)]
                          (if (and (= (:decoder-model-present-for-this-op op) 1)
                                   (or (zero? op-idc)
                                       (and (= in-temporal-layer 1) (= in-spatial-layer 1))))
                            (let [[_v r'] (br/f r brtl)] r')
                            r)))
                      r9 ops))
            r9)
          all-frames (dec (bit-shift-left 1 8)) ;; NUM_REF_FRAMES = 8
          [refresh-frame-flags r11]
          (if (or (= frame-type SWITCH_FRAME) (and (= frame-type KEY_FRAME) (= show-frame 1)))
            [all-frames r10]
            (br/f r10 8))
          ;; ref_order_hint loop: only when (!FrameIsIntra || refresh_frame_flags != allFrames)
          ;; && error_resilient_mode && enable_order_hint. For FrameIsIntra with
          ;; refresh_frame_flags == allFrames (the common single-keyframe case)
          ;; this is always skipped.
          r12
          (if (and (or (not frame-is-intra?) (not= refresh-frame-flags all-frames))
                   (= error-resilient-mode 1) (= enable-order-hint 1))
            (loop [i 0, r r11]
              (if (>= i 8) r (let [[_v r'] (br/f r order-hint-bits)] (recur (inc i) r'))))
            r11)
          [frame-size-fields r13] (parse-frame-size r12 seq-hdr)
          [render-size-fields r14] (parse-render-size r13 frame-size-fields)
          [allow-intrabc r15]
          (if (and (= allow-screen-content-tools 1)
                    (= (:upscaled-width frame-size-fields) (:frame-width frame-size-fields)))
            (br/f r14 1)
            [0 r14])
          [disable-frame-end-update-cdf r16]
          (if (or (= reduced-still-picture-header 1) (= disable-cdf-update 1))
            [1 r15]
            (br/f r15 1))
          [tile-info r17] (parse-tile-info r16 (merge seq-hdr frame-size-fields))
          [quant r18] (parse-quantization-params r17 seq-hdr)
          [seg r19] (parse-segmentation-params r18 {:primary-ref-frame primary-ref-frame})
          [delta-q r20] (parse-delta-q-params r19 (:base-q-idx quant))
          [delta-lf r21] (parse-delta-lf-params r20 {:delta-q-present (:delta-q-present delta-q)
                                                      :allow-intrabc allow-intrabc})
          lossless (compute-lossless quant seg (:frame-width frame-size-fields) (:upscaled-width frame-size-fields))
          [lf r22] (parse-loop-filter-params r21 {:coded-lossless (:coded-lossless lossless)
                                                   :allow-intrabc allow-intrabc
                                                   :num-planes (:num-planes seq-hdr)})
          [cdef r23] (parse-cdef-params r22 {:coded-lossless (:coded-lossless lossless)
                                              :allow-intrabc allow-intrabc
                                              :enable-cdef (:enable-cdef seq-hdr)
                                              :num-planes (:num-planes seq-hdr)})
          [lr r24] (parse-lr-params r23 {:all-lossless (:all-lossless lossless)
                                         :allow-intrabc allow-intrabc
                                         :enable-restoration (:enable-restoration seq-hdr)
                                         :use-128x128-superblock (:use-128x128-superblock seq-hdr)
                                         :subsampling-x (:subsampling-x seq-hdr)
                                         :subsampling-y (:subsampling-y seq-hdr)
                                         :num-planes (:num-planes seq-hdr)})
          [tx-mode r25] (read-tx-mode r24 (:coded-lossless lossless))
          reference-mode (frame-reference-mode frame-is-intra?)
          skip-mode (skip-mode-params frame-is-intra?)
          ;; spec: `if (FrameIsIntra || error_resilient_mode || !enable_warped_motion)
          ;; allow_warped_motion = 0 else @@allow_warped_motion f(1)` --
          ;; FrameIsIntra is always true in this phase's scope, so this is
          ;; always 0 with no bit read.
          allow-warped-motion 0
          [reduced-tx-set r26] (br/f r25 1)
          global-motion (global-motion-params frame-is-intra?)
          [film-grain r27] (parse-film-grain-params r26 {:film-grain-params-present (:film-grain-params-present seq-hdr)
                                                          :show-frame show-frame
                                                          :showable-frame showable-frame
                                                          :mono-chrome (:mono-chrome seq-hdr)
                                                          :subsampling-x (:subsampling-x seq-hdr)
                                                          :subsampling-y (:subsampling-y seq-hdr)})]
      {:show-existing-frame show-existing-frame
       :frame-type frame-type
       :frame-is-intra frame-is-intra?
       :show-frame show-frame
       :showable-frame showable-frame
       :error-resilient-mode error-resilient-mode
       :disable-cdf-update disable-cdf-update
       :allow-screen-content-tools allow-screen-content-tools
       :force-integer-mv _force-integer-mv
       :current-frame-id current-frame-id
       :frame-size-override-flag frame-size-override-flag
       :order-hint order-hint
       :primary-ref-frame primary-ref-frame
       :refresh-frame-flags refresh-frame-flags
       :allow-intrabc allow-intrabc
       :disable-frame-end-update-cdf disable-frame-end-update-cdf
       :frame-width (:frame-width frame-size-fields)
       :frame-height (:frame-height frame-size-fields)
       :upscaled-width (:upscaled-width frame-size-fields)
       :mi-cols (:mi-cols frame-size-fields)
       :mi-rows (:mi-rows frame-size-fields)
       :render-width (:render-width render-size-fields)
       :render-height (:render-height render-size-fields)
       :tile-info tile-info
       :quantization-params quant
       :base-q-idx (:base-q-idx quant)
       :segmentation-params seg
       :delta-q-params delta-q
       :delta-lf-params delta-lf
       :coded-lossless (:coded-lossless lossless)
       :all-lossless (:all-lossless lossless)
       :loop-filter-params lf
       :cdef-params cdef
       :lr-params lr
       :tx-mode (:tx-mode tx-mode)
       :reference-select (:reference-select reference-mode)
       :skip-mode-present (:skip-mode-present skip-mode)
       :allow-warped-motion allow-warped-motion
       :reduced-tx-set reduced-tx-set
       :gm-type (:gm-type global-motion)
       :film-grain-params film-grain
       ;; Bugfix (Phase 1 pixel-reconstruction continuation, ADR-2607122000
       ;; Migration step 9): av1.tile-group/parse-tile-group-obu reads
       ;; `:use-128x128-superblock` (and residual()/decode_block() need
       ;; `:mono-chrome`/`:num-planes`/`:subsampling-x`/`:subsampling-y`) off
       ;; *this* map, but before this fix none of these seq_header_obu()
       ;; fields were copied into frame-header's top-level return map --
       ;; only reachable via the (separate) seq-hdr argument callers already
       ;; hold. The lookup silently returned nil (falsy), so
       ;; parse-tile-group-obu always fell back to BLOCK_64X64 superblocks
       ;; even for a `use_128x128_superblock == 1` stream (confirmed against
       ;; a real SVT-AV1... a real aomenc-encoded 32x32 monochrome stream,
       ;; 2026-07-13: `seq-hdr` decoded `:use-128x128-superblock 1` but the
       ;; superblock loop silently used BLOCK_64X64 instead of
       ;; BLOCK_128X128). Copying these through here (instead of threading a
       ;; second seq-hdr argument into every downstream fn) keeps frame-hdr
       ;; self-contained the same way :base-q-idx is already duplicated here.
       :use-128x128-superblock (:use-128x128-superblock seq-hdr)
       :mono-chrome (:mono-chrome seq-hdr)
       :num-planes (:num-planes seq-hdr)
       :subsampling-x (:subsampling-x seq-hdr)
       :subsampling-y (:subsampling-y seq-hdr)
       :reader-after-quantization-params r18
       :reader-after-frame-header r27}))))
