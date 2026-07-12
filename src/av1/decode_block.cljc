(ns av1.decode-block
  "decode_block() (AV1 spec 5.11.5 \"Decode block syntax\"), plus the syntax
   it calls that this phase supports: mode_info()/intra_frame_mode_info()
   (5.11.16/5.11.17), read_block_tx_size()/read_tx_size() (5.11.14/5.11.15),
   residual()/transform_block() (5.11.34/5.11.35), coeffs() (5.11.39), and
   the reconstruction process (7.12/7.13, via av1.transform). Transcribed
   field-for-field from AV1 Bitstream & Decoding Process Specification --
   AOMediaCodec/av1-spec master, 06.bitstream.syntax.md #Decode block syntax
   / #Mode info syntax / #Intra frame mode info syntax / #Intra segment ID
   syntax / #Skip syntax / #TX size syntax / #Block TX size syntax /
   #Residual syntax / #Transform block syntax / #Coefficients syntax /
   #Compute transform type function / #Transform type syntax / #Get
   transform set function, 07.bitstream.semantics.md, 08.decoding.process.md
   #Intra prediction process / #Reconstruct process / #2D inverse transform
   process, 09.parsing.process.md #Cdf selection process (fetched
   2026-07-13).

   SCOPE (Phase 1 pixel-reconstruction milestone, ADR-2607122000 Migration
   step 9, continuing av1.tile-group's Phase 0/1 OBU/header/bool-decoder/
   partition-tree work): this is the first phase of this repo that produces
   actual reconstructed pixels, deliberately narrowed the same way
   `org-iso-h264`'s first pixel-reconstruction phase was --

     - exactly ONE partition/leaf shape is exercised end-to-end against
       real data: a single superblock whose decode_partition() walk forces
       PARTITION_SPLIT down through zero-bit reads (per av1.tile-group)
       until a single real PARTITION_NONE read at BLOCK_32X32 produces one
       leaf covering the entire (32x32, in the validated fixture) frame --
       see test/av1/decode_block_test.clj for how a real aomenc encode was
       steered into exactly this shape;
     - exactly ONE transform size is supported: TX_32X32 (`tx-mode` must be
       `:tx-mode-largest` and the block's Max_Tx_Size_Rect must resolve to
       TX_32X32 -- anything else throws). This isn't an arbitrary choice:
       TX_32X32 is also the one size for which get_tx_set() (see
       `read-transform-type` below) is *structurally* forced to
       TX_SET_DCTONLY (Tx_Size_Sqr_Up[TX_32X32] == TX_32X32, spec #Get
       transform set function), meaning TxType == DCT_DCT is guaranteed by
       the bitstream syntax itself (zero bits read) rather than merely
       likely for smooth content -- the most robust possible scope boundary
       for a phase that explicitly excludes ADST/IDTX/FLIPADST;
     - exactly ONE intra prediction mode is supported: DC_PRED. Every
       non-DC_PRED `intra_frame_y_mode` throws (the CDF *read* itself is
       real and correct -- see `read-y-mode` -- only the *result* is
       restricted). In the validated fixture this isn't probabilistic
       either: the real aomenc encode disables every other intra mode
       (`--enable-smooth-intra=0 --enable-paeth-intra=0
       --enable-directional-intra=0`), so DC_PRED is the only mode the
       encoder could have chosen;
     - luma (plane 0) only: this repo's fixtures are `mono_chrome` (aomenc
       `--monochrome`, NumPlanes==1), so HasChroma is always 0 and no
       chroma mode_info/residual syntax exists in the bitstream at all (not
       skipped -- structurally absent). `guard-frame-scope!` throws if a
       stream isn't mono_chrome, since a real 4:2:0 stream would need real
       chroma mode_info/residual parsing this phase doesn't implement;
     - segmentation / delta-Q / delta-LF / screen-content-tools / intra-BC
       must all be disabled at the frame level (`guard-frame-scope!` throws
       otherwise) -- each of those would need CDF tables/context derivation
       this phase doesn't implement (segment_id, delta_q_abs, delta_lf_abs,
       palette_mode_info, use_intrabc);
     - `read-cdef` and `filter_intra_mode_info`'s conditional read ARE
       implemented in full generality (not narrowed) since they're cheap
       (a raw literal read and a single conditional CDF read respectively)
       -- see their docstrings.

   Everything above that throws does so via `ex-info` with a `:reason`
   key, matching the rest of this repo's out-of-scope-input handling
   (av1.frame-header, av1.tile-group).

   This namespace is wired into av1.tile-group/decode-partition via the
   injectable `:decode-block-fn` state key (see that namespace's
   docstring) -- `make-decode-block-fn` below builds that callback."
  (:require [av1.bool-decoder :as bd]
            [av1.tables :as tables]
            [av1.transform :as xform]
            [av1.intra-pred :as pred]
            [av1.tile-group :as tg]))

(def DC_PRED 0)
(def DCT_DCT 0)

;; ---------------------------------------------------------------------
;; Frame-level scope guard (checked once, at decode-block-fn construction).

(defn guard-frame-scope!
  "Throws ex-info unless `frame-hdr`/`seq-hdr` describe a stream entirely
   within this namespace's supported scope (see namespace docstring).
   Per-block conditions (skip/YMode/TxSize/use_filter_intra) are checked
   per-block in `decode-block` itself, not here."
  [frame-hdr seq-hdr]
  (letfn [(bail! [reason]
            (throw (ex-info (str "av1.decode-block: out of scope: " reason)
                             {:reason reason})))]
    (when (not= 1 (:mono-chrome seq-hdr))
      (bail! "mono_chrome must be 1 (luma-only phase; no chroma mode_info/residual support)"))
    (when (not= 1 (:num-planes frame-hdr))
      (bail! "num_planes must be 1"))
    (when (pos? (:coded-lossless frame-hdr))
      (bail! "CodedLossless must be 0 (lossless uses the WHT, not DCT_DCT)"))
    (when (pos? (get-in frame-hdr [:segmentation-params :segmentation-enabled]))
      (bail! "segmentation_enabled must be 0 (no read_segment_id support)"))
    (when (pos? (get-in frame-hdr [:delta-q-params :delta-q-present]))
      (bail! "delta_q_present must be 0 (no delta_q_abs CDF support)"))
    (when (pos? (get-in frame-hdr [:delta-lf-params :delta-lf-present]))
      (bail! "delta_lf_present must be 0 (no delta_lf_abs CDF support)"))
    (when (pos? (:allow-screen-content-tools frame-hdr))
      (bail! "allow_screen_content_tools must be 0 (no palette_mode_info support)"))
    (when (pos? (:allow-intrabc frame-hdr))
      (bail! "allow_intrabc must be 0 (no use_intrabc CDF support)"))
    (when (not= :tx-mode-largest (:tx-mode frame-hdr))
      (bail! "tx_mode must be TX_MODE_LARGEST (no tx_depth CDF support for TX_MODE_SELECT, and TX_MODE_ONLY_4X4 implies Lossless)"))))

;; ---------------------------------------------------------------------
;; Generic per-context CDF get/put, mirroring av1.tile-group's
;; get-partition-cdf/put-partition-cdf pattern (persist the tile-adapted
;; cdf under `cdf-key` in state, keyed by `ctx-key`; fall back to the
;; supplied default row the first time a context is seen).

(defn- get-cdf [state cdf-key ctx-key default]
  (get-in state [cdf-key ctx-key] default))

(defn- put-cdf [state cdf-key ctx-key cdf]
  (assoc-in state [cdf-key ctx-key] cdf))

(defn- read-cdf-symbol
  "Read one symbol against the tile-persisted (or default) cdf at
   `[cdf-key ctx-key]`, adapting per `(:cdf-adapt? state)`. Returns
   [symbol state']."
  [state cdf-key ctx-key default]
  (let [cdf (get-cdf state cdf-key ctx-key default)
        adapt? (:cdf-adapt? state true)
        [sym cdf' bd'] (bd/read-symbol (:bd state) cdf adapt?)]
    [sym (-> state (assoc :bd bd') (put-cdf cdf-key ctx-key cdf'))]))

;; ---------------------------------------------------------------------
;; read_skip() -- spec #Skip syntax + 09.parsing.process.md "skip" cdf
;; selection (ctx from AvailU/AvailL neighbor Skips[], tracked in
;; `(:skips state)` under `[row col]`).

(defn- read-skip [state row col avail-u? avail-l?]
  (let [ctx (+ (if avail-u? (get (:skips state) [(dec row) col] 0) 0)
               (if avail-l? (get (:skips state) [row (dec col)] 0) 0))
        [skip state'] (read-cdf-symbol state :skip-cdfs ctx (nth tables/Default-Skip-Cdf ctx))]
    [skip state']))

;; ---------------------------------------------------------------------
;; intra_frame_y_mode -- 09.parsing.process.md "intra_frame_y_mode":
;; ctx = (Intra_Mode_Context[abovemode], Intra_Mode_Context[leftmode]),
;; abovemode/leftmode default to DC_PRED when unavailable. Since this
;; namespace only ever accepts YMode==DC_PRED (else throws) and
;; Intra_Mode_Context[DC_PRED] == 0, every reachable ctx is *always* (0,0)
;; -- so no YModes grid needs to be tracked at all, unlike Skips above.

(defn- read-y-mode [state]
  (let [[sym state'] (read-cdf-symbol state :y-mode-cdf [0 0] tables/Default-Intra-Frame-Y-Mode-Cdf-0-0)]
    (when (not= DC_PRED sym)
      (throw (ex-info "av1.decode-block: out of scope: intra_frame_y_mode != DC_PRED"
                       {:reason :non-dc-intra-mode :y-mode sym})))
    [sym state']))

;; ---------------------------------------------------------------------
;; read_cdef() -- spec #Read CDEF syntax. Implemented in full generality
;; (not narrowed to cdef_bits==0): a raw L(cdef_bits) literal read via
;; av1.bool-decoder/read-literal (no CDF table needed at all for this
;; syntax element), gated on the same conditions as the spec and cached
;; per 64x64-aligned grid cell via `(:cdef-idx state)` so a later block
;; inside the same 64x64 area doesn't re-read (mirrors the spec's
;; `cdef_idx[r][c] == -1` check).

(defn- read-cdef [state row col skip? coded-lossless? enable-cdef? allow-intrabc?]
  (if (or skip? coded-lossless? (not enable-cdef?) allow-intrabc?)
    state
    (let [cdef-bits (:cdef-bits state)
          cdef-size4 (nth tg/Num_4x4_Blocks_Wide tg/BLOCK_64X64)
          mask (bit-not (dec cdef-size4))
          key [(bit-and row mask) (bit-and col mask)]]
      (if (contains? (:cdef-idx state) key)
        state
        (let [[_idx bd'] (if (pos? cdef-bits) (bd/read-literal (:bd state) cdef-bits) [0 (:bd state)])]
          (-> state (assoc :bd bd') (assoc-in [:cdef-idx key] true)))))))

;; ---------------------------------------------------------------------
;; filter_intra_mode_info() -- spec #Filter intra mode info syntax. Only
;; ever reads a bit when enable_filter_intra && YMode==DC_PRED (always
;; true here) && PaletteSizeY==0 (always true here) &&
;; Max(Block_Width,Block_Height)<=32. This namespace doesn't have the
;; Default_Filter_Intra_Cdfs table (out of scope, see namespace
;; docstring), so it throws if that per-block condition is ever true
;; instead of silently mis-parsing; when false (as in every fixture this
;; phase validates against, since TX_32X32-selecting blocks are always
;; >32x32), it's correctly a zero-bit no-op.

(defn- guard-no-filter-intra! [enable-filter-intra? mi-size]
  (let [bw (* 4 (nth tg/Num_4x4_Blocks_Wide mi-size))
        bh (* 4 (nth tg/Num_4x4_Blocks_High mi-size))]
    (when (and enable-filter-intra? (<= (max bw bh) 32))
      (throw (ex-info "av1.decode-block: out of scope: filter_intra_mode_info() would read a bit (no Default_Filter_Intra_Cdfs table)"
                       {:reason :filter-intra-not-supported})))))

;; ---------------------------------------------------------------------
;; read_block_tx_size() / read_tx_size() -- spec #Block TX size syntax /
;; #TX size syntax. is_inter is always 0 here, so read_block_tx_size()
;; always takes the `read_tx_size(!skip || !is_inter)` branch (allowSelect
;; is always true since `!is_inter` is always true); guard-frame-scope!
;; already enforces tx_mode == TX_MODE_LARGEST, so TxSize ==
;; Max_Tx_Size_Rect[MiSize] directly, zero bits read (TX_MODE_SELECT's
;; tx_depth would need a CDF table this phase doesn't have).

(defn- tx-size-for [mi-size]
  (let [tx-sz (nth tables/Max-Tx-Size-Rect mi-size)]
    (when (not= tx-sz tables/TX_32X32)
      (throw (ex-info "av1.decode-block: out of scope: only TX_32X32 is a supported transform size"
                       {:reason :unsupported-tx-size :tx-size tx-sz :mi-size mi-size})))
    tx-sz))

;; ---------------------------------------------------------------------
;; transform_type() / get_tx_set() -- spec #Transform type syntax / #Get
;; transform set function. For TX_32X32, Tx_Size_Sqr_Up[TX_32X32] ==
;; TX_32X32 forces get_tx_set() to return TX_SET_DCTONLY (0) for BOTH the
;; intra and inter branches (06.bitstream.syntax.md #Get transform set
;; function: "if (txSzSqrUp == TX_32X32) return TX_SET_DCTONLY" for intra),
;; so `set > 0` in transform_type() is always false and TxType is assigned
;; DCT_DCT with *zero bits read* -- this is a structural guarantee of the
;; bitstream syntax for this tx size, not a probabilistic outcome (see
;; namespace docstring). Asserted (not silently assumed) below.

(defn- read-transform-type [tx-sz]
  (when (not= tx-sz tables/TX_32X32)
    (throw (ex-info "av1.decode-block: internal: read-transform-type only supports TX_32X32"
                     {:reason :unsupported-tx-size})))
  ;; Tx_Size_Sqr_Up[TX_32X32] == TX_32X32 -> get_tx_set() == TX_SET_DCTONLY
  ;; (0) unconditionally -> transform_type()'s `set > 0` is false -> TxType
  ;; forced to DCT_DCT, zero bits read.
  DCT_DCT)

;; ---------------------------------------------------------------------
;; coeffs() -- spec #Coefficients syntax (5.11.39). DCT_DCT/TX_32X32/
;; luma-only, per namespace docstring.

(def ^:private TX32-BWL 5) ;; Tx_Width_Log2[TX_32X32]
(def ^:private TX32-W 32)
(def ^:private TX32-SEG-EOB 1024)

(defn- get-coeff-base-ctx
  "spec 09.parsing.process.md get_coeff_base_ctx(), TX_CLASS_2D only (see
   namespace docstring -- DCT_DCT is the only supported PlaneTxType, so
   get_tx_class always returns TX_CLASS_2D)."
  [quant pos c eob?]
  (if eob?
    (cond
      (zero? c) (- tables/SIG_COEF_CONTEXTS 4)
      (<= c (quot (bit-shift-left TX32-W TX32-BWL) 8)) (- tables/SIG_COEF_CONTEXTS 3)
      (<= c (quot (bit-shift-left TX32-W TX32-BWL) 4)) (- tables/SIG_COEF_CONTEXTS 2)
      :else (dec tables/SIG_COEF_CONTEXTS))
    (let [row (bit-shift-right pos TX32-BWL)
          col (- pos (bit-shift-left row TX32-BWL))
          mag (reduce (fn [acc [dr dc]]
                        (let [rr (+ row dr) rc (+ col dc)]
                          (if (and (>= rr 0) (>= rc 0) (< rr TX32-W) (< rc TX32-W))
                            (+ acc (min (abs (long (nth quant (+ (bit-shift-left rr TX32-BWL) rc)))) 3))
                            acc)))
                      0 tables/Sig-Ref-Diff-Offset-2D)
          ctx (min (bit-shift-right (inc mag) 1) 4)]
      (if (and (zero? row) (zero? col))
        0
        (+ ctx (get-in tables/Coeff-Base-Ctx-Offset-32x32 [(min row 4) (min col 4)]))))))

(defn- get-coeff-br-ctx
  "spec 09.parsing.process.md coeff_br ctx derivation, TX_CLASS_2D only."
  [quant pos]
  (let [row (bit-shift-right pos TX32-BWL)
        col (- pos (bit-shift-left row TX32-BWL))
        mag (reduce (fn [acc [dr dc]]
                      (let [rr (+ row dr) rc (+ col dc)]
                        (if (and (>= rr 0) (>= rc 0) (< rr TX32-W) (< rc TX32-W))
                          (+ acc (min (long (nth quant (+ (bit-shift-left rr TX32-BWL) rc)))
                                      (+ tables/COEFF_BASE_RANGE tables/NUM_BASE_LEVELS 1)))
                          acc)))
                    0 tables/Mag-Ref-Offset-With-Tx-Class-2D)
        mag (min (bit-shift-right (inc mag) 1) 6)]
    (if (zero? pos)
      mag
      (if (and (< row 2) (< col 2)) (+ mag 7) (+ mag 14)))))

(defn- read-eob-extra-bit [state]
  (let [[bit bd'] (bd/read-literal (:bd state) 1)]
    [bit (assoc state :bd bd')]))

(defn- read-golomb-bit [state]
  (let [[bit bd'] (bd/read-literal (:bd state) 1)]
    [bit (assoc state :bd bd')]))

(defn- read-eob
  "spec #Coefficients syntax eob_pt_1024/eob_extra/eob_extra_bit reads (this
   phase only ever selects eob_pt_1024, see namespace docstring --
   eobMultisize == Min(5,5)+Min(5,5)-4 == 6 for TX_32X32, which always
   falls to the `else` -> eob_pt_1024 branch). eob_extra's ctx
   (09.parsing.process.md: `TileEobExtraCdf[txSzCtx][ptype][eobPt-3]`) is
   exactly `eob-shift` here since `eob-shift = Max(-1, eobPt-3)` and this
   branch only runs when `eob-shift >= 0` (i.e. eob-shift == eobPt-3)."
  [state q-idx]
  (let [[eob-pt-sym state1] (read-cdf-symbol state :eob-pt-1024-cdf [] (nth tables/Default-Eob-Pt-1024-Cdf-Luma q-idx))
        eob-pt (inc eob-pt-sym)
        eob0 (if (< eob-pt 2) eob-pt (inc (bit-shift-left 1 (- eob-pt 2))))
        eob-shift (max -1 (- eob-pt 3))]
    (if (>= eob-shift 0)
      (let [[eob-extra state2] (read-cdf-symbol state1 :eob-extra-cdfs eob-shift
                                                 (get-in tables/Default-Eob-Extra-Cdf-32x32-Luma [q-idx eob-shift]))
            eob1 (if (pos? eob-extra) (+ eob0 (bit-shift-left 1 eob-shift)) eob0)
            n (max 0 (- eob-pt 2))
            [eob-final state3]
            (reduce (fn [[eob s] i]
                      (let [shift (- n 1 i)
                            [bit s'] (read-eob-extra-bit s)]
                        [(if (pos? bit) (+ eob (bit-shift-left 1 shift)) eob) s']))
                    [eob1 state2] (range 1 n))]
        [eob-final state3])
      [eob0 state1])))

(defn- read-golomb [state]
  (loop [length 1, state state]
    (let [[bit state'] (read-golomb-bit state)]
      (if (zero? bit)
        (recur (inc length) state')
        (let [[x state'']
              (reduce (fn [[x s] _i]
                        (let [[b s'] (read-golomb-bit s)]
                          [(bit-or (bit-shift-left x 1) b) s']))
                      [1 state'] (range (- length 2) -1 -1))]
          [x state''])))))

(defn- read-coeffs
  "spec #Coefficients syntax coeffs(0, startX, startY, TX_32X32) -- plane 0
   only. `q-idx` is av1.tables/q-ctx-idx of the frame's base_q_idx.
   Returns [eob quant state'] where `quant` is a flat row-major 1024-length
   vector (Quant[pos], signed, already de-scanned into raster position).

   `quant` is threaded as a plain persistent vector (not a transient) --
   1024 elements is small enough that this repo's usual
   simplicity-over-micro-optimization stance applies; every context lookup
   (get-coeff-base-ctx/get-coeff-br-ctx) needs read access to whatever has
   already been decoded into `quant` so far, which is simplest to express
   as \"the current persistent value\" rather than juggling transient
   checkpoints."
  [state q-idx]
  (let [txb-skip-ctx 0 ;; bw==w && bh==h for this phase's only block shape (see namespace docstring)
        [all-zero state1] (read-cdf-symbol state :txb-skip-cdfs txb-skip-ctx
                                            (get-in tables/Default-Txb-Skip-Cdf-32x32 [q-idx txb-skip-ctx]))]
    (if (pos? all-zero)
      [0 (vec (repeat TX32-SEG-EOB 0)) state1]
      (let [_tx-type (read-transform-type tables/TX_32X32) ;; DCT_DCT, zero bits (see read-transform-type)
            [eob state2] (read-eob state1 q-idx)
            scan tables/Default-Scan-32x32
            ;; level pass: c = eob-1 downto 0 (spec: `for (c = eob-1; c >= 0; c--)`)
            [quant1 state3]
            (reduce
             (fn [[quant s] c]
               (let [pos (nth scan c)
                     eob? (= c (dec eob))
                     ctx (if eob?
                           (+ (- (get-coeff-base-ctx quant pos c true) tables/SIG_COEF_CONTEXTS)
                              tables/SIG_COEF_CONTEXTS_EOB)
                           (get-coeff-base-ctx quant pos c false))
                     [level s2]
                     (if eob?
                       (let [[sym s'] (read-cdf-symbol s :coeff-base-eob-cdfs ctx (get-in tables/Default-Coeff-Base-Eob-Cdf-32x32-Luma [q-idx ctx]))]
                         [(inc sym) s'])
                       (let [[sym s'] (read-cdf-symbol s :coeff-base-cdfs ctx (get-in tables/Default-Coeff-Base-Cdf-32x32-Luma [q-idx ctx]))]
                         [sym s']))
                     [level2 s3]
                     (if (> level tables/NUM_BASE_LEVELS)
                       (loop [idx 0, level level, s s2]
                         (if (>= idx (quot tables/COEFF_BASE_RANGE (dec tables/BR_CDF_SIZE)))
                           [level s]
                           (let [br-ctx (get-coeff-br-ctx quant pos)
                                 [br s'] (read-cdf-symbol s :coeff-br-cdfs br-ctx (get-in tables/Default-Coeff-Br-Cdf-32x32-Luma [q-idx br-ctx]))]
                             (if (< br (dec tables/BR_CDF_SIZE))
                               [(+ level br) s']
                               (recur (inc idx) (+ level br) s')))))
                       [level s2])]
                 [(assoc quant pos level2) s3]))
             [(vec (repeat TX32-SEG-EOB 0)) state2]
             (range (dec eob) -1 -1))
            ;; sign + golomb pass: c = 0..eob-1 (spec: `for (c = 0; c < eob; c++)`)
            [quant2 dc-category cul-level state4]
            (reduce
             (fn [[quant dc-cat cul s] c]
               (let [pos (nth scan c)
                     qv (nth quant pos)]
                 (if (zero? qv)
                   [quant dc-cat cul s]
                   (let [dc-sign-ctx 0 ;; no prior blocks in this phase's validated single-block scope
                         [sign s2]
                         (if (zero? c)
                           (read-cdf-symbol s :dc-sign-cdfs dc-sign-ctx (get-in tables/Default-Dc-Sign-Cdf-Luma [q-idx dc-sign-ctx]))
                           (let [[b bd'] (bd/read-literal (:bd s) 1)]
                             [b (assoc s :bd bd')]))
                         [qv2 s3]
                         (if (> qv (+ tables/NUM_BASE_LEVELS tables/COEFF_BASE_RANGE))
                           (let [[x s'] (read-golomb s2)]
                             [(+ x tables/COEFF_BASE_RANGE tables/NUM_BASE_LEVELS) s'])
                           [qv s2])
                         dc-cat' (if (and (zero? pos) (pos? qv2)) (if (pos? sign) 1 2) dc-cat)
                         qv3 (bit-and qv2 0xFFFFF)
                         cul' (+ cul qv3)
                         qv4 (if (pos? sign) (- qv3) qv3)]
                     [(assoc quant pos qv4) dc-cat' cul' s3]))))
             [quant1 0 0 state3]
             (range eob))
            cul-level (min 63 cul-level)]
        [eob quant2 (assoc state4 :cul-level cul-level :dc-category dc-category)]))))

;; ---------------------------------------------------------------------
;; predict_intra (DC_PRED only) + reconstruct -- transform_block() (spec
;; #Transform block syntax), restricted to plane 0, single transform block
;; per leaf (this phase's only validated block shape has MiSize's plane
;; residual size exactly equal to TX_32X32, so residual()'s stepX/stepY
;; loop always runs exactly once -- see namespace docstring).

(defn- write-block [plane frame-w x y w h values]
  (reduce (fn [pl [i j]] (assoc pl (+ (* (+ y i) frame-w) x j) (nth values (+ (* i w) j))))
          plane
          (for [i (range h) j (range w)] [i j])))

(defn- decode-transform-block
  [state frame-hdr row col avail-u? avail-l? q-idx]
  (let [tx-sz tables/TX_32X32
        log2W 5 log2H 5 ;; Tx_Width_Log2[TX_32X32] / Tx_Height_Log2[TX_32X32]
        w 32 h 32
        frame-w (* 4 (:mi-cols frame-hdr)) frame-h (* 4 (:mi-rows frame-hdr))
        x (* 4 col) y (* 4 row)
        plane0 (or (:luma-plane state) (vec (repeat (* frame-w frame-h) 0)))
        have-left? avail-l? have-above? avail-u?
        pred (pred/dc-predict plane0 frame-w frame-h x y have-left? have-above? log2W log2H 8)
        plane1 (write-block plane0 frame-w x y w h pred)
        state1 (assoc state :luma-plane plane1)
        [eob quant state2] (read-coeffs state1 q-idx)]
    (if (pos? eob)
      (let [base-q-idx (:base-q-idx frame-hdr)
            delta-q-y-dc (get-in frame-hdr [:quantization-params :delta-q-y-dc])
            clip3 (fn [lo hi v] (cond (< v lo) lo (> v hi) hi :else v))
            dc-q-index (clip3 0 255 (+ base-q-idx delta-q-y-dc))
            ac-q-index (clip3 0 255 base-q-idx)
            dc-quant (nth tables/Dc-Qlookup-8bit dc-q-index)
            ac-quant (nth tables/Ac-Qlookup-8bit ac-q-index)
            dq-denom (xform/dq-denom tx-sz)
            dequant (xform/dequantize quant w h dq-denom dc-quant ac-quant 8)
            residual (xform/inverse-transform-2d dequant log2W log2H 8)
            hi (dec (bit-shift-left 1 8))
            recon (mapv (fn [p r] (let [v (+ p r)] (cond (< v 0) 0 (> v hi) hi :else v))) pred residual)
            plane2 (write-block plane1 frame-w x y w h recon)]
        [{:eob eob} (assoc state2 :luma-plane plane2)])
      [{:eob 0} state2])))

;; ---------------------------------------------------------------------
;; decode_block() entry point.

(defn make-decode-block-fn
  "Builds the `:decode-block-fn` callback (see av1.tile-group/decode-partition
   docstring for the contract) for one frame. Throws immediately
   (guard-frame-scope!) if `frame-hdr`/`seq-hdr` are outside this
   namespace's supported scope -- callers should catch/report before this
   point if they want a softer failure mode than an exception at
   construction time."
  [frame-hdr seq-hdr]
  (guard-frame-scope! frame-hdr seq-hdr)
  (let [q-idx (tables/q-ctx-idx (:base-q-idx frame-hdr))
        enable-cdef? (pos? (:enable-cdef seq-hdr))
        enable-filter-intra? (pos? (:enable-filter-intra seq-hdr))
        coded-lossless? (pos? (:coded-lossless frame-hdr))
        allow-intrabc? (pos? (:allow-intrabc frame-hdr))]
    (fn [state row col mi-size avail-u? avail-l?]
      (guard-no-filter-intra! enable-filter-intra? mi-size)
      (let [[skip state1] (read-skip state row col avail-u? avail-l?)
            state1 (read-cdef state1 row col (pos? skip) coded-lossless? enable-cdef? allow-intrabc?)
            ;; intra_segment_id(): segmentation_enabled forced 0 by
            ;; guard-frame-scope!, so segment_id=0 (assignment only, no read).
            [_y-mode state2] (read-y-mode state1)
            tx-sz (tx-size-for mi-size)
            state3 (assoc-in state2 [:skips [row col]] skip)]
        (if (pos? skip)
          ;; reset_block_context(): no residual coded for this leaf. This
          ;; phase's only validated block shape has exactly one leaf for
          ;; the whole frame, so there's no "future block" to reset context
          ;; for; still write DC_PRED as the reconstructed pixels (skip
          ;; means prediction-only, no residual).
          (let [frame-w (* 4 (:mi-cols frame-hdr)) frame-h (* 4 (:mi-rows frame-hdr))
                x (* 4 col) y (* 4 row)
                plane0 (or (:luma-plane state3) (vec (repeat (* frame-w frame-h) 0)))
                pred (pred/dc-predict plane0 frame-w frame-h x y avail-l? avail-u? 5 5 8)
                plane1 (write-block plane0 frame-w x y 32 32 pred)]
            [{:skip true :tx-size tx-sz} (assoc state3 :luma-plane plane1)])
          (let [[result state4] (decode-transform-block state3 frame-hdr row col avail-u? avail-l? q-idx)]
            [(assoc result :skip false :tx-size tx-sz) state4]))))))
