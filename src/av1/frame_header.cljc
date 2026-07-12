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
            [context-update-tile-id tile-size-bytes r4]
            (if (or (> tile-cols-log2 0) (> tile-rows-log2 0))
              (let [[cuti r'] (br/f r3 (+ tile-rows-log2 tile-cols-log2))
                    [tsb-minus-1 r''] (br/f r' 2)]
                [cuti (inc tsb-minus-1) r''])
              [0 nil r3])]
        [{:uniform-tile-spacing-flag 1
          :tile-cols-log2 tile-cols-log2 :tile-rows-log2 tile-rows-log2
          :tile-cols tile-cols :tile-rows tile-rows
          :context-update-tile-id context-update-tile-id
          :tile-size-bytes tile-size-bytes}
         r4])
      ;; non-uniform spacing: ns(n)-coded per-tile sizes.
      (let [[tile-cols r2 tile-cols-log2 widest-tile-sb]
            (loop [i 0, start-sb 0, r r1, widest 0]
              (if (< start-sb sb-cols)
                (let [max-width (min (- sb-cols start-sb) max-tile-width-sb)
                      [w-minus-1 r'] (br/ns r max-width)
                      size-sb (inc w-minus-1)]
                  (recur (inc i) (+ start-sb size-sb) r' (max size-sb widest)))
                [i r (tile-log2 1 i) widest]))
            max-tile-area-sb' (if (> min-log2-tiles 0)
                                 (bit-shift-right (* sb-rows sb-cols) (inc min-log2-tiles))
                                 (* sb-rows sb-cols))
            max-tile-height-sb (max (quot max-tile-area-sb' widest-tile-sb) 1)
            [tile-rows r3 tile-rows-log2]
            (loop [i 0, start-sb 0, r r2, widest 0]
              (if (< start-sb sb-rows)
                (let [max-height (min (- sb-rows start-sb) max-tile-height-sb)
                      [h-minus-1 r'] (br/ns r max-height)
                      size-sb (inc h-minus-1)]
                  (recur (inc i) (+ start-sb size-sb) r' (max size-sb widest)))
                [i r (tile-log2 1 i)]))
            [context-update-tile-id tile-size-bytes r4]
            (if (or (> tile-cols-log2 0) (> tile-rows-log2 0))
              (let [[cuti r'] (br/f r3 (+ tile-rows-log2 tile-cols-log2))
                    [tsb-minus-1 r''] (br/f r' 2)]
                [cuti (inc tsb-minus-1) r''])
              [0 nil r3])]
        [{:uniform-tile-spacing-flag 0
          :tile-cols-log2 tile-cols-log2 :tile-rows-log2 tile-rows-log2
          :tile-cols tile-cols :tile-rows tile-rows
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
        [show-existing-frame frame-type show-frame error-resilient-mode r1]
        (if (= reduced-still-picture-header 1)
          [0 KEY_FRAME 1 nil reader]
          (let [[sef r'] (br/f reader 1)]
            (if (= sef 1)
              (throw (ex-info "show_existing_frame == 1 not supported (needs cross-frame RefFrameType state, out of Phase 0 scope)"
                               {:show-existing-frame 1}))
              (let [[ft r''] (br/f r' 2)
                    [sf r'''] (br/f r'' 1)
                    [erm r''''] (if (or (= ft SWITCH_FRAME) (and (= ft KEY_FRAME) (= sf 1)))
                                  [1 r''']
                                  (br/f r''' 1))]
                [0 ft sf erm r'''']))))
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
          [quant r18] (parse-quantization-params r17 seq-hdr)]
      {:show-existing-frame show-existing-frame
       :frame-type frame-type
       :frame-is-intra frame-is-intra?
       :show-frame show-frame
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
       :reader-after-quantization-params r18}))))
