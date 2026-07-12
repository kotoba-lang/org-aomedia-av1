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
   transform set function / #Intra angle info luma syntax / #Intra angle
   info chroma syntax / #Get plane residual size function / #Get TX size
   function, 07.bitstream.semantics.md, 08.decoding.process.md #Intra
   prediction process / #Reconstruct process / #2D inverse transform
   process, 09.parsing.process.md #Cdf selection process (fetched
   2026-07-13).

   SCOPE (Phase 1 pixel-reconstruction milestone, ADR-2607122000 Migration
   step 9, continuing av1.tile-group's Phase 0/1 OBU/header/bool-decoder/
   partition-tree work): this is the first phase of this repo that produces
   actual reconstructed pixels, deliberately narrowed the same way
   `org-iso-h264`'s first pixel-reconstruction phase was --

     - the original DC_PRED-only fixtures exercise exactly ONE partition/
       leaf shape end-to-end: a single superblock whose decode_partition()
       walk forces PARTITION_SPLIT down through zero-bit reads (per
       av1.tile-group) until a single real PARTITION_NONE read at
       BLOCK_32X32 produces one leaf covering the entire (32x32, in that
       fixture) frame. The V_PRED/H_PRED mode-coverage fixtures extend this
       to a real MULTI-leaf frame -- a 64x64-pixel, single-64x64-superblock
       frame encoded with `--min-partition-size=32 --max-partition-size=32`
       (forcing the encoder's real, normally-signaled `partition` symbol
       read at the top-level BLOCK_64X64 call to be PARTITION_SPLIT,
       producing exactly 4 BLOCK_32X32 leaves in a 2x2 grid) -- every leaf
       is still exactly BLOCK_32X32/TX_32X32/DCT_DCT (luma). See test/av1/
       decode_block_test.clj / test/av1/fixtures.clj for how each real
       aomenc encode was steered into its exact shape;
     - exactly ONE luma transform size is supported: TX_32X32 (`tx-mode`
       must be `:tx-mode-largest` and the block's Max_Tx_Size_Rect must
       resolve to TX_32X32 -- anything else throws). This isn't an
       arbitrary choice: TX_32X32 is also the one size for which
       get_tx_set() (see `read-transform-type` below) is *structurally*
       forced to TX_SET_DCTONLY (Tx_Size_Sqr_Up[TX_32X32] == TX_32X32,
       spec #Get transform set function), meaning TxType == DCT_DCT is
       guaranteed by the bitstream syntax itself (zero bits read) rather
       than merely likely for smooth content -- the most robust possible
       scope boundary for a phase that explicitly excludes ADST/IDTX/
       FLIPADST;
     - exactly THREE luma intra prediction modes are supported: DC_PRED,
       V_PRED, H_PRED. Every other `intra_frame_y_mode` (D45/D135/D113/
       D157/D203/D67/SMOOTH*/PAETH) still throws (the CDF *read* itself is
       real and correct for all 13 modes -- see `read-y-mode` -- only the
       *result* is restricted to these three);
     - CHROMA (Cb/Cr) DECODE, this phase's continuation (ADR-2607122000
       Migration step 9, chroma extension): 4:2:0 only (subsampling_x=1,
       subsampling_y=1). Since this repo's only supported leaf shape is
       BLOCK_32X32 (bw4=bh4=8, never 1), HasChroma (spec #Decode block
       syntax) is always `NumPlanes > 1` unconditionally -- none of the
       bw4==1/bh4==1 subsampled-avail special cases in the spec's HasChroma/
       AvailUChroma/AvailLChroma derivation are ever reachable here, so this
       namespace doesn't implement them (AvailUChroma/AvailLChroma are
       simply AvailU/AvailL). A BLOCK_32X32 luma leaf subsamples (4:2:0) to
       exactly BLOCK_16X16/TX_16X16 chroma (Subsampled_Size[BLOCK_32X32][1][1]
       == BLOCK_16X16, Max_Tx_Size_Rect[BLOCK_16X16] == TX_16X16 -- both
       spot-checked directly against the spec's own tables, see av1.tables
       namespace docstring's chroma section) -- hardcoded rather than
       table-driven for the same reason TX32-W/TX32-BWL were hardcoded for
       luma (this phase supports exactly one shape per plane). Only
       UV_DC_PRED (chroma DC prediction, `uv_mode` decoded value 0) is
       supported -- `read-uv-mode` throws for any other decoded value
       (including UV_CFL_PRED, which would need `read_cfl_alphas()`, out of
       scope) the same way `read-y-mode` restricts luma. `compute_tx_type()`
       for plane>0 is NEVER a bitstream read (`transform_type()` is only
       ever called for plane==0, per spec #Coefficients syntax) -- it's
       `Mode_To_Txfm[UVMode]`, which for UVMode==DC_PRED is DCT_DCT
       (spot-checked against the spec's own Mode_To_Txfm table) and always
       `is_tx_type_in_set` (DCT_DCT is index 0, which is 1/true in every row
       of Tx_Type_In_Set_Intra) -- so chroma TxType is DCT_DCT with a
       *structural* zero-bit guarantee for this scope, exactly mirroring
       luma's TX_32X32 DCT_DCT guarantee, and needs no read-transform-type
       call at all (unlike luma, which does call it, itself a zero-bit
       forced read).

       CROSS-BLOCK CHROMA CONTEXT (SCOPE BOUNDARY): unlike luma (which
       tracks real cross-leaf AboveDcContext/LeftDcContext/YModes for the
       multi-leaf V_PRED/H_PRED fixtures), this namespace's chroma support
       is validated ONLY for a single BLOCK_32X32 leaf covering the WHOLE
       frame (mirroring the ORIGINAL single-leaf DC_PRED-only luma
       milestone, before the multi-leaf mode-coverage extension) --
       `AvailU`/`AvailL` (hence `AvailUChroma`/`AvailLChroma`, identical
       here) must both be false, or this namespace throws
       `:unsupported-multi-leaf-chroma`. The per-plane AboveDcContext/
       LeftDcContext/AboveLevelContext/LeftLevelContext maps
       (`record-above!`/`record-left!`/`get-dc-sign-ctx`/
       `get-txb-skip-ctx-chroma` below) ARE implemented as real map lookups
       (not hardcoded constants) so a future multi-leaf-chroma extension
       only needs to lift this one guard, not rederive the context
       machinery -- but for now, since no second chroma leaf ever exists to
       query them, every chroma ctx this phase's fixtures exercise is
       provably the same value a real cross-leaf-aware implementation would
       produce (all context maps start empty and are only ever read back by
       a leaf that can't exist yet). The U (Cb) and V (Cr) planes are
       decoded with genuinely SHARED coefficient-CDF adaptation state (see
       `chroma-spec` below) -- matching the spec exactly (`ptype` is
       \"luma vs chroma\", not \"Y vs U vs V\"; TileTxbSkipCdf/
       TileCoeffBaseCdf/etc. are single arrays adapted first by U's reads,
       then continued by V's reads) -- but separate, per-plane
       AboveDcContext/LeftDcContext/AboveLevelContext/LeftLevelContext
       (spec: these ARE separately indexed by literal plane 1 vs 2);
     - luma-only frames (mono_chrome=1, num_planes=1) remain fully
       supported unchanged (all pre-existing monochrome fixtures/tests
       still validate identically -- `guard-frame-scope!`'s color-format
       check now accepts either monochrome OR 4:2:0, see below, and every
       chroma code path below is gated on `color?` so a monochrome frame
       never touches it);
     - segmentation / delta-Q / delta-LF / screen-content-tools / intra-BC
       must all be disabled at the frame level (`guard-frame-scope!` throws
       otherwise) -- each of those would need CDF tables/context derivation
       this phase doesn't implement (segment_id, delta_q_abs, delta_lf_abs,
       palette_mode_info, use_intrabc);
     - `read-cdef` and `filter_intra_mode_info`'s conditional-read GATE ARE
       implemented in full generality (not narrowed) since determining
       whether a bit *would* be read is cheap (a raw literal read and a
       single condition check respectively); `filter_intra_mode_info`'s
       gate correctly depends on the block's actual YMode (`YMode ==
       DC_PRED`, per spec) -- see `guard-no-filter-intra!` below -- but the
       CDF read *itself*, if the gate ever says a bit would be read,
       remains unsupported and throws (no `Default_Filter_Intra_Cdfs`
       table, see its docstring). `filter_intra_mode_info()` has no chroma
       equivalent in the spec (it's luma-only), so it's unaffected by the
       chroma extension.

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
(def V_PRED 1)
(def H_PRED 2)
(def DCT_DCT 0)
(def UV_DC_PRED 0)

;; ---------------------------------------------------------------------
;; Frame-level scope guard (checked once, at decode-block-fn construction).

(defn guard-frame-scope!
  "Throws ex-info unless `frame-hdr`/`seq-hdr` describe a stream entirely
   within this namespace's supported scope (see namespace docstring).
   Per-block conditions (skip/YMode/UVMode/TxSize/use_filter_intra/
   multi-leaf-chroma) are checked per-block in `decode-block` itself, not
   here."
  [frame-hdr seq-hdr]
  (letfn [(bail! [reason]
            (throw (ex-info (str "av1.decode-block: out of scope: " reason)
                             {:reason reason})))]
    (let [mono? (= 1 (:mono-chrome seq-hdr))
          color-420? (and (zero? (long (:mono-chrome seq-hdr)))
                           (= 3 (:num-planes frame-hdr))
                           (= 1 (:subsampling-x seq-hdr))
                           (= 1 (:subsampling-y seq-hdr)))]
      (when-not (or mono? color-420?)
        (bail! "mono_chrome must be 1 (luma-only) or (mono_chrome=0, num_planes=3, subsampling_x=1, subsampling_y=1) 4:2:0 color -- no 4:2:2/4:4:4 chroma support")))
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
   [symbol state']. `cdf-key` is shared between the U and V planes for the
   chroma-family keys (see namespace docstring's cross-block-context
   section) -- callers pass the same `cdf-key` for both planes to get the
   spec's real shared-adaptation behavior."
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
;; abovemode/leftmode are the actual Y modes of the neighboring
;; ALREADY-DECODED blocks (AvailU ? YModes[row-1][col] : DC_PRED),
;; (AvailL ? YModes[row][col-1] : DC_PRED) -- properly tracked via a
;; `(:y-modes state)` grid (mirroring Skips' pattern above), keyed by every
;; mi position in each decoded leaf's full footprint (`record-footprint!`
;; below), not just its (row,col) origin. Only
;; Default_Intra_Frame_Y_Mode_Cdf[0][0] is transcribed (see
;; av1.tables/Default-Intra-Frame-Y-Mode-Cdf-0-0's docstring), so this
;; throws rather than silently using the wrong default CDF if a real
;; neighbor's mapped context is ever nonzero.

(defn- read-y-mode [state row col avail-u? avail-l?]
  (let [neighbor-mode (fn [avail? r c] (if avail? (get (:y-modes state) [r c] DC_PRED) DC_PRED))
        ctx-of (fn [m] (nth tables/Intra-Mode-Context-Dc-V-H m))
        abovemode (ctx-of (neighbor-mode avail-u? (dec row) col))
        leftmode (ctx-of (neighbor-mode avail-l? row (dec col)))
        ctx [abovemode leftmode]]
    (when (not= [0 0] ctx)
      (throw (ex-info "av1.decode-block: out of scope: intra_frame_y_mode ctx != (0,0) (only Default_Intra_Frame_Y_Mode_Cdf[0][0] is transcribed)"
                       {:reason :unsupported-y-mode-ctx :ctx ctx})))
    (let [[sym state'] (read-cdf-symbol state :y-mode-cdf [0 0] tables/Default-Intra-Frame-Y-Mode-Cdf-0-0)]
      (when (not (contains? #{DC_PRED V_PRED H_PRED} sym))
        (throw (ex-info "av1.decode-block: out of scope: intra_frame_y_mode not in {DC_PRED,V_PRED,H_PRED}"
                         {:reason :unsupported-intra-mode :y-mode sym})))
      [sym state'])))

;; ---------------------------------------------------------------------
;; uv_mode -- 09.parsing.process.md "uv_mode": cdf is
;; TileUVModeCflAllowedCdf[YMode] whenever Max(Block_Width,Block_Height)<=32
;; (always true for this namespace's only supported mi-size, BLOCK_32X32 ->
;; 32<=32) or Lossless (never true, guard-frame-scope! forces
;; CodedLossless=0) -- so this namespace never needs
;; TileUVModeCflNotAllowedCdf at all. ctx is simply the block's own
;; already-decoded YMode (0/1/2, since read-y-mode already restricts it) --
;; NOT a neighbor lookup (unlike y_mode/dc_sign), so no cross-block state is
;; needed here. Restricts the decoded UVMode to UV_DC_PRED (0) -- every
;; other UVMode (including UV_CFL_PRED, which would need read_cfl_alphas())
;; throws, mirroring read-y-mode's restriction of YMode.

(defn- read-uv-mode [state y-mode]
  (let [[sym state'] (read-cdf-symbol state :uv-mode-cdf y-mode
                                       (nth tables/Default-Uv-Mode-Cfl-Allowed-Cdf-Dc-V-H y-mode))]
    (when (not= UV_DC_PRED sym)
      (throw (ex-info "av1.decode-block: out of scope: uv_mode not UV_DC_PRED"
                       {:reason :unsupported-uv-mode :uv-mode sym})))
    [sym state']))

;; ---------------------------------------------------------------------
;; Footprint-wide grid writes (spec #Decode block syntax's per-block loops
;; `YModes[r+y][c+x] = YMode` / `Skips[r+y][c+x] = skip`, for y=0..bh4-1,
;; x=0..bw4-1) -- mirrors av1.tile-group/set-mi-sizes' identical pattern.
;; bw4/bh4 come from the block's mi-size (Num_4x4_Blocks_Wide/High), always
;; 8x8 for this namespace's only supported mi-size (BLOCK_32X32). Luma-only
;; (the spec's UVModes grid is written too, but nothing in this namespace's
;; scope ever queries it back -- uv_mode's ctx is the block's own YMode, not
;; a neighbor's UVMode, see read-uv-mode above -- so it isn't tracked).

(defn- record-footprint! [state grid-key row col mi-size value]
  (let [w (nth tg/Num_4x4_Blocks_Wide mi-size)
        h (nth tg/Num_4x4_Blocks_High mi-size)]
    (update state grid-key
            (fn [m] (reduce (fn [m [dr dc]] (assoc m [(+ row dr) (+ col dc)] value))
                             (or m {})
                             (for [dr (range h), dc (range w)] [dr dc]))))))

;; ---------------------------------------------------------------------
;; intra_angle_info_y() -- spec #Intra angle info luma syntax. Called
;; UNCONDITIONALLY by intra_frame_mode_info() right after intra_frame_y_mode
;; (06.bitstream.syntax.md #Intra frame mode info syntax), with NO gate on
;; any sequence/frame-header enable flag -- only on `MiSize >= BLOCK_8X8`
;; (always true, this namespace's only mi-size is BLOCK_32X32) and
;; `is_directional_mode(YMode)` (true for V_PRED/H_PRED, false for
;; DC_PRED).
;;
;; ctx = YMode - V_PRED (09.parsing.process.md \"angle_delta_y\": cdf is
;; TileAngleDeltaCdf[YMode-V_PRED]) -- 0 for V_PRED, 1 for H_PRED.
;;
;; AngleDeltaY = angle_delta_y - MAX_ANGLE_DELTA. This namespace's
;; av1.intra-pred v-predict/h-predict calls assume pAngle is exactly
;; 90/180 (AngleDeltaY == 0), so this throws rather than silently
;; mis-predicting if it's ever nonzero.
;;
;; intra_angle_info_uv() (spec #Intra angle info chroma syntax) is the same
;; shape but for UVMode -- since read-uv-mode already restricts UVMode to
;; UV_DC_PRED (not directional), is_directional_mode(UVMode) is always
;; false, so intra_angle_info_uv() is a zero-bit no-op for every color
;; frame this namespace supports and needs no read function at all (unlike
;; intra_angle_info_y(), which DOES read real bits for V_PRED/H_PRED).

(defn- read-angle-delta-y [state y-mode]
  (if (contains? #{V_PRED H_PRED} y-mode)
    (let [ctx (- y-mode V_PRED)
          [sym state'] (read-cdf-symbol state :angle-delta-y-cdf ctx (nth tables/Default-Angle-Delta-Cdf ctx))
          angle-delta-y (- sym tables/MAX_ANGLE_DELTA)]
      (when (not= 0 angle-delta-y)
        (throw (ex-info "av1.decode-block: out of scope: AngleDeltaY != 0 (av1.intra-pred's v-predict/h-predict assume angleDelta forced 0)"
                         {:reason :unsupported-angle-delta :angle-delta-y angle-delta-y})))
      [angle-delta-y state'])
    [0 state]))

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
;; ever reads a bit when enable_filter_intra && YMode==DC_PRED &&
;; PaletteSizeY==0 (always true in this namespace's scope, no palette
;; support) && Max(Block_Width,Block_Height)<=32. No chroma equivalent
;; exists in the spec (filter_intra is luma-only), so this is unaffected by
;; the chroma extension.

(defn- guard-no-filter-intra! [enable-filter-intra? mi-size y-mode]
  (let [bw (* 4 (nth tg/Num_4x4_Blocks_Wide mi-size))
        bh (* 4 (nth tg/Num_4x4_Blocks_High mi-size))]
    (when (and enable-filter-intra? (= y-mode DC_PRED) (<= (max bw bh) 32))
      (throw (ex-info "av1.decode-block: out of scope: filter_intra_mode_info() would read a bit (no Default_Filter_Intra_Cdfs table)"
                       {:reason :filter-intra-not-supported})))))

;; ---------------------------------------------------------------------
;; read_block_tx_size() / read_tx_size() -- spec #Block TX size syntax /
;; #TX size syntax. is_inter is always 0 here, so read_block_tx_size()
;; always takes the `read_tx_size(!skip || !is_inter)` branch (allowSelect
;; is always true since `!is_inter` is always true); guard-frame-scope!
;; already enforces tx_mode == TX_MODE_LARGEST, so TxSize ==
;; Max_Tx_Size_Rect[MiSize] directly, zero bits read (TX_MODE_SELECT's
;; tx_depth would need a CDF table this phase doesn't have). Luma tx-size
;; only -- chroma's tx size (TX_16X16, via get_tx_size()/
;; get_plane_residual_size(), see namespace docstring) is hardcoded in
;; `chroma-spec` below rather than computed through this fn, since this
;; namespace only ever reaches exactly one chroma shape.

(defn- tx-size-for [mi-size]
  (let [tx-sz (nth tables/Max-Tx-Size-Rect mi-size)]
    (when (not= tx-sz tables/TX_32X32)
      (throw (ex-info "av1.decode-block: out of scope: only TX_32X32 is a supported transform size"
                       {:reason :unsupported-tx-size :tx-size tx-sz :mi-size mi-size})))
    tx-sz))

;; ---------------------------------------------------------------------
;; transform_type() / get_tx_set() -- spec #Transform type syntax / #Get
;; transform set function. LUMA ONLY (plane==0) -- coeffs() only ever calls
;; transform_type() when plane==0, per spec; chroma's TxType comes from
;; compute_tx_type()'s Mode_To_Txfm[UVMode] path instead, a *different* code
;; path that reads zero bits unconditionally for UV_DC_PRED (see namespace
;; docstring) and therefore needs no analogous read fn at all. For
;; TX_32X32, Tx_Size_Sqr_Up[TX_32X32] == TX_32X32 forces get_tx_set() to
;; return TX_SET_DCTONLY (0), so `set > 0` in transform_type() is always
;; false and TxType is assigned DCT_DCT with *zero bits read* -- a
;; structural guarantee of the bitstream syntax for this tx size, not a
;; probabilistic outcome (see namespace docstring). Asserted (not silently
;; assumed) below.

(defn- read-transform-type [tx-sz]
  (when (not= tx-sz tables/TX_32X32)
    (throw (ex-info "av1.decode-block: internal: read-transform-type only supports TX_32X32"
                     {:reason :unsupported-tx-size})))
  ;; Tx_Size_Sqr_Up[TX_32X32] == TX_32X32 -> get_tx_set() == TX_SET_DCTONLY
  ;; (0) unconditionally -> transform_type()'s `set > 0` is false -> TxType
  ;; forced to DCT_DCT, zero bits read.
  DCT_DCT)

;; ---------------------------------------------------------------------
;; coeffs() -- spec #Coefficients syntax (5.11.39), generalized over
;; plane/tx-size via the `spec` map `read-coeffs` takes (see
;; `luma-spec`/`chroma-spec` below) so the SAME implementation serves both
;; luma (TX_32X32, bwl=5, w=32) and chroma (TX_16X16, bwl=4, w=16) --
;; DCT_DCT/TX_CLASS_2D only, per namespace docstring.

(defn- get-coeff-base-ctx
  "spec 09.parsing.process.md get_coeff_base_ctx(), TX_CLASS_2D only (see
   namespace docstring -- DCT_DCT is the only supported PlaneTxType, so
   get_tx_class always returns TX_CLASS_2D). `bwl`/`w` are the transform
   block's Tx_Width_Log2/Tx_Width (5/32 for luma's TX_32X32, 4/16 for
   chroma's TX_16X16 -- both square, so width==height). Coeff_Base_Ctx_Offset
   is identical for TX_16X16 and TX_32X32 in the spec's own table (see
   av1.tables namespace docstring's chroma section), so both callers share
   `tables/Coeff-Base-Ctx-Offset-32x32` without needing a separate slice."
  [quant pos c eob? bwl w]
  (if eob?
    (cond
      (zero? c) (- tables/SIG_COEF_CONTEXTS 4)
      (<= c (quot (bit-shift-left w bwl) 8)) (- tables/SIG_COEF_CONTEXTS 3)
      (<= c (quot (bit-shift-left w bwl) 4)) (- tables/SIG_COEF_CONTEXTS 2)
      :else (dec tables/SIG_COEF_CONTEXTS))
    (let [row (bit-shift-right pos bwl)
          col (- pos (bit-shift-left row bwl))
          mag (reduce (fn [acc [dr dc]]
                        (let [rr (+ row dr) rc (+ col dc)]
                          (if (and (>= rr 0) (>= rc 0) (< rr w) (< rc w))
                            (+ acc (min (abs (long (nth quant (+ (bit-shift-left rr bwl) rc)))) 3))
                            acc)))
                      0 tables/Sig-Ref-Diff-Offset-2D)
          ctx (min (bit-shift-right (inc mag) 1) 4)]
      (if (and (zero? row) (zero? col))
        0
        (+ ctx (get-in tables/Coeff-Base-Ctx-Offset-32x32 [(min row 4) (min col 4)]))))))

;; ---------------------------------------------------------------------
;; dc_sign ctx -- 09.parsing.process.md "dc_sign": ctx derived from the
;; ALREADY-DECODED neighboring transform blocks' DcCategory, tracked in
;; `(:above-dc state)`/`(:left-dc state)` (spec's AboveDcContext[plane]/
;; LeftDcContext[plane], NOW GENUINELY PER-PLANE -- see namespace
;; docstring's cross-block-context section -- keyed
;; `[:above-dc plane col]`/`[:left-dc plane row]`, plane 0/1/2 for
;; luma/U/V respectively). av1.tables/Default-Dc-Sign-Cdf-Luma /
;; Default-Dc-Sign-Cdf-Chroma carry the full DC_SIGN_CONTEXTS==3 entries
;; (transcribed in full generality from the start), so ctx is computed
;; exactly per spec with no scope restriction needed for either plane
;; family.

(defn- dc-sign-delta [category]
  (case (long category) 1 -1 2 1 0))

(defn- get-dc-sign-ctx [state plane col row w4 h4 mi-cols mi-rows]
  (let [above (get-in state [:above-dc plane] {})
        left (get-in state [:left-dc plane] {})
        above-sum (reduce (fn [acc k]
                             (let [x4 (+ col k)]
                               (if (< x4 mi-cols)
                                 (+ acc (dc-sign-delta (get above x4 0)))
                                 acc)))
                           0 (range w4))
        total (reduce (fn [acc k]
                         (let [y4 (+ row k)]
                           (if (< y4 mi-rows)
                             (+ acc (dc-sign-delta (get left y4 0)))
                             acc)))
                       above-sum (range h4))]
    (cond (neg? total) 1 (pos? total) 2 :else 0)))

;; record-above!/record-left! -- spec #Coefficients syntax's coeffs()
;; epilogue: `AboveLevelContext[plane][x4+i]=culLevel`,
;; `AboveDcContext[plane][x4+i]=dcCategory` (LeftLevelContext/LeftDcContext
;; symmetric), written unconditionally (including the all_zero==1 case,
;; with culLevel=0/dcCategory=0) regardless of plane. Both dc and level
;; context are now tracked together (level context is new -- needed for
;; chroma's txb_skip ctx, see `get-txb-skip-ctx-chroma` below; luma's
;; txb_skip ctx is still the hardcoded 0 the original milestone
;; established, since bw==w && bh==h always for this namespace's only luma
;; block shape, so luma never actually reads back AboveLevelContext/
;; LeftLevelContext -- they're still written here for fidelity/symmetry
;; with the spec's unconditional write, and in case a future extension
;; needs them). `plane` namespaces the map (0/1/2), matching
;; `get-dc-sign-ctx` above.

(defn- record-above! [state plane col w4 dc-category cul-level]
  (-> state
      (update-in [:above-dc plane] (fn [m] (reduce (fn [m k] (assoc m (+ col k) dc-category)) (or m {}) (range w4))))
      (update-in [:above-level plane] (fn [m] (reduce (fn [m k] (assoc m (+ col k) cul-level)) (or m {}) (range w4))))))

(defn- record-left! [state plane row h4 dc-category cul-level]
  (-> state
      (update-in [:left-dc plane] (fn [m] (reduce (fn [m k] (assoc m (+ row k) dc-category)) (or m {}) (range h4))))
      (update-in [:left-level plane] (fn [m] (reduce (fn [m k] (assoc m (+ row k) cul-level)) (or m {}) (range h4))))))

;; get_txb_skip_ctx (chroma / plane>0 branch) -- 09.parsing.process.md
;; "all_zero": for plane>0, `above`/`left` OR together
;; AboveLevelContext[plane]/AboveDcContext[plane] (LeftLevelContext/
;; LeftDcContext) over the block's w4/h4 span; ctx = 7 + (above!=0) +
;; (left!=0) (+3 more if bw*bh > w*h -- NEVER true for this namespace's
;; only chroma shape, BLOCK_16X16/TX_16X16 has bw=bh=w=h=16, asserted
;; below rather than silently assumed). SCOPE: per namespace docstring,
;; this namespace only ever calls this when AvailU/AvailL are both false
;; (single whole-frame leaf), so `above`/`left` are always 0 here (no
;; earlier block ever wrote to these maps) and this always returns
;; exactly 7 -- but implemented as real map lookups (not a hardcoded
;; constant), so a future multi-leaf-chroma extension is a smaller change.

(defn- get-txb-skip-ctx-chroma [state plane col row w4 h4 bw bh w h mi-cols mi-rows]
  (when (> (* bw bh) (* w h))
    (throw (ex-info "av1.decode-block: internal: get-txb-skip-ctx-chroma's +3 branch is unreachable for this namespace's only chroma block shape"
                     {:reason :unsupported-chroma-txb-skip-ctx})))
  (let [above-dc (get-in state [:above-dc plane] {})
        left-dc (get-in state [:left-dc plane] {})
        above-level (get-in state [:above-level plane] {})
        left-level (get-in state [:left-level plane] {})
        above (reduce (fn [acc k] (let [x4 (+ col k)]
                                     (if (< x4 mi-cols)
                                       (bit-or acc (long (get above-level x4 0)) (long (get above-dc x4 0)))
                                       acc)))
                       0 (range w4))
        left (reduce (fn [acc k] (let [y4 (+ row k)]
                                    (if (< y4 mi-rows)
                                      (bit-or acc (long (get left-level y4 0)) (long (get left-dc y4 0)))
                                      acc)))
                     0 (range h4))]
    (+ 7 (if (not= 0 above) 1 0) (if (not= 0 left) 1 0))))

(defn- get-coeff-br-ctx
  "spec 09.parsing.process.md coeff_br ctx derivation, TX_CLASS_2D only.
   `bwl`/`w` as in get-coeff-base-ctx above."
  [quant pos bwl w]
  (let [row (bit-shift-right pos bwl)
        col (- pos (bit-shift-left row bwl))
        mag (reduce (fn [acc [dr dc]]
                      (let [rr (+ row dr) rc (+ col dc)]
                        (if (and (>= rr 0) (>= rc 0) (< rr w) (< rc w))
                          (+ acc (min (long (nth quant (+ (bit-shift-left rr bwl) rc)))
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
  "spec #Coefficients syntax eob_pt_1024/eob_pt_256/eob_extra/
   eob_extra_bit reads, generalized over the eob_pt cdf-key/table and
   eob_extra cdf-key/table (luma always selects eob_pt_1024 -- since
   eobMultisize == Min(5,5)+Min(5,5)-4 == 6 for TX_32X32 -- and chroma
   always selects eob_pt_256 -- eobMultisize == Min(4,5)+Min(4,5)-4 == 4
   for TX_16X16 -- both structural, see `luma-spec`/`chroma-spec` below
   for which literal table each caller passes). eob_extra's ctx
   (09.parsing.process.md: `TileEobExtraCdf[txSzCtx][ptype][eobPt-3]`) is
   exactly `eob-shift` here since `eob-shift = Max(-1, eobPt-3)` and this
   branch only runs when `eob-shift >= 0` (i.e. eob-shift == eobPt-3)."
  [state q-idx eob-pt-cdf-key eob-pt-table eob-extra-cdf-key eob-extra-table]
  (let [[eob-pt-sym state1] (read-cdf-symbol state eob-pt-cdf-key [] (nth eob-pt-table q-idx))
        eob-pt (inc eob-pt-sym)
        eob0 (if (< eob-pt 2) eob-pt (inc (bit-shift-left 1 (- eob-pt 2))))
        eob-shift (max -1 (- eob-pt 3))]
    (if (>= eob-shift 0)
      (let [[eob-extra state2] (read-cdf-symbol state1 eob-extra-cdf-key eob-shift
                                                 (get-in eob-extra-table [q-idx eob-shift]))
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
  "spec #Coefficients syntax coeffs(plane, startX, startY, txSz) --
   generalized over plane/tx-size via `spec` (see `luma-spec`/
   `chroma-spec` below): `:bwl`/`:w`/`:seg-eob`/`:scan` (tx-size shape),
   `:plane` (0/1/2, for the per-plane above-dc/left-dc/above-level/
   left-level maps), and the seven `:*-cdf-key`/`:*-table` pairs (which
   default-cdf table + adaptation-state key family to use -- luma's own
   vs. the shared-between-U-and-V chroma family, see namespace
   docstring). `txb-skip-ctx` is passed in already-computed (luma: always
   0, see `get-coeff-base-ctx`'s docstring; chroma:
   `get-txb-skip-ctx-chroma`) since it depends on state captured before
   this fn's own writes.

   `row`/`col` are the transform block's mi position in THIS PLANE's own
   4x4-unit coordinate space (== the leaf's luma row/col for plane 0; for
   chroma, luma row/col right-shifted by subsampling_x/y, see
   `decode-transform-block` below) and `mi-cols`/`mi-rows` this plane's own
   subsampled mi dimensions. Returns [eob quant state'] where `quant` is a
   flat row-major `seg-eob`-length vector (Quant[pos], signed, already
   de-scanned into raster position).

   `quant` is threaded as a plain persistent vector (not a transient) --
   segEob is small enough (<=1024) that this repo's usual
   simplicity-over-micro-optimization stance applies; every context lookup
   (get-coeff-base-ctx/get-coeff-br-ctx) needs read access to whatever has
   already been decoded into `quant` so far, which is simplest to express
   as \"the current persistent value\" rather than juggling transient
   checkpoints."
  [state q-idx row col mi-cols mi-rows txb-skip-ctx spec]
  (let [{:keys [plane bwl w seg-eob scan
                txb-skip-cdf-key txb-skip-table
                eob-pt-cdf-key eob-pt-table eob-extra-cdf-key eob-extra-table
                coeff-base-eob-cdf-key coeff-base-eob-table
                coeff-base-cdf-key coeff-base-table
                coeff-br-cdf-key coeff-br-table
                dc-sign-cdf-key dc-sign-table]} spec
        w4 (bit-shift-right w 2) h4 w4
        [all-zero state1] (read-cdf-symbol state txb-skip-cdf-key txb-skip-ctx
                                            (get-in txb-skip-table [q-idx txb-skip-ctx]))]
    (if (pos? all-zero)
      [0 (vec (repeat seg-eob 0))
       (-> state1 (record-above! plane col w4 0 0) (record-left! plane row h4 0 0))]
      (let [dc-sign-ctx (get-dc-sign-ctx state1 plane col row w4 h4 mi-cols mi-rows)
            ;; transform_type()/read-transform-type is luma-only (plane==0,
            ;; zero bits, asserted DCT_DCT -- see its docstring); chroma's
            ;; TxType is DCT_DCT with zero bits unconditionally for this
            ;; namespace's UV_DC_PRED-only scope (see namespace docstring)
            ;; via a DIFFERENT code path (compute_tx_type()'s
            ;; Mode_To_Txfm[UVMode], not transform_type()) -- so plane>0
            ;; never calls read-transform-type at all, only plane==0 does
            ;; (still asserted here, not skipped, for parity/fidelity with
            ;; the spec's real per-plane call structure).
            _tx-type (when (zero? plane) (read-transform-type (if (= w 32) tables/TX_32X32 tables/TX_16X16)))
            [eob state2] (read-eob state1 q-idx eob-pt-cdf-key eob-pt-table eob-extra-cdf-key eob-extra-table)
            ;; level pass: c = eob-1 downto 0 (spec: `for (c = eob-1; c >= 0; c--)`)
            [quant1 state3]
            (reduce
             (fn [[quant s] c]
               (let [pos (nth scan c)
                     eob? (= c (dec eob))
                     ctx (if eob?
                           (+ (- (get-coeff-base-ctx quant pos c true bwl w) tables/SIG_COEF_CONTEXTS)
                              tables/SIG_COEF_CONTEXTS_EOB)
                           (get-coeff-base-ctx quant pos c false bwl w))
                     [level s2]
                     (if eob?
                       (let [[sym s'] (read-cdf-symbol s coeff-base-eob-cdf-key ctx (get-in coeff-base-eob-table [q-idx ctx]))]
                         [(inc sym) s'])
                       (let [[sym s'] (read-cdf-symbol s coeff-base-cdf-key ctx (get-in coeff-base-table [q-idx ctx]))]
                         [sym s']))
                     [level2 s3]
                     (if (> level tables/NUM_BASE_LEVELS)
                       (loop [idx 0, level level, s s2]
                         (if (>= idx (quot tables/COEFF_BASE_RANGE (dec tables/BR_CDF_SIZE)))
                           [level s]
                           (let [br-ctx (get-coeff-br-ctx quant pos bwl w)
                                 [br s'] (read-cdf-symbol s coeff-br-cdf-key br-ctx (get-in coeff-br-table [q-idx br-ctx]))]
                             (if (< br (dec tables/BR_CDF_SIZE))
                               [(+ level br) s']
                               (recur (inc idx) (+ level br) s')))))
                       [level s2])]
                 [(assoc quant pos level2) s3]))
             [(vec (repeat seg-eob 0)) state2]
             (range (dec eob) -1 -1))
            ;; sign + golomb pass: c = 0..eob-1 (spec: `for (c = 0; c < eob; c++)`)
            [quant2 dc-category cul-level state4]
            (reduce
             (fn [[quant dc-cat cul s] c]
               (let [pos (nth scan c)
                     qv (nth quant pos)]
                 (if (zero? qv)
                   [quant dc-cat cul s]
                   (let [[sign s2]
                         (if (zero? c)
                           (read-cdf-symbol s dc-sign-cdf-key dc-sign-ctx (get-in dc-sign-table [q-idx dc-sign-ctx]))
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
            cul-level (min 63 cul-level)
            state5 (-> state4 (record-above! plane col w4 dc-category cul-level) (record-left! plane row h4 dc-category cul-level))]
        [eob quant2 state5]))))

;; ---------------------------------------------------------------------
;; predict_intra (DC_PRED/V_PRED/H_PRED) + reconstruct -- transform_block()
;; (spec #Transform block syntax), single transform block per leaf per
;; plane (this phase's only validated block shape has MiSize's plane
;; residual size exactly equal to the plane's own tx size, so residual()'s
;; stepX/stepY loop always runs exactly once for every plane -- see
;; namespace docstring). Shared by luma (mode = YMode, one of DC/V/H_PRED)
;; and chroma (mode is always UV_DC_PRED == DC_PRED == 0, since read-uv-mode
;; already restricts UVMode).

(defn- predict-intra
  "Dispatches to av1.intra-pred's dc-predict/v-predict/h-predict by
   `mode` -- all three share the same parameter shape (see av1.intra-pred
   namespace docstring). `mode` is the block's YMode for plane 0, or its
   (always UV_DC_PRED/0) UVMode for plane>0."
  [mode plane0 frame-w frame-h x y have-left? have-above? log2W log2H bit-depth]
  (case (long mode)
    0 (pred/dc-predict plane0 frame-w frame-h x y have-left? have-above? log2W log2H bit-depth)
    1 (pred/v-predict plane0 frame-w frame-h x y have-left? have-above? log2W log2H bit-depth)
    2 (pred/h-predict plane0 frame-w frame-h x y have-left? have-above? log2W log2H bit-depth)))

(defn- write-block [plane frame-w x y w h values]
  (reduce (fn [pl [i j]] (assoc pl (+ (* (+ y i) frame-w) x j) (nth values (+ (* i w) j))))
          plane
          (for [i (range h) j (range w)] [i j])))

(defn- decode-transform-block
  "Decodes ONE plane's single transform block for this leaf --
   predict_intra + coeffs() + (if eob>0) dequantize + inverse_transform_2d
   + reconstruct + clip (spec #Transform block syntax, restricted to this
   namespace's one-tx-block-per-plane-per-leaf scope, see namespace
   docstring). `spec` is `luma-spec` (plane 0) or one of `u-spec`/`v-spec`
   (plane 1/2, sharing `chroma-spec`'s cdf-key/table family but each with
   its own `:plane`/`:delta-q-dc`/`:delta-q-ac`). `mode` is YMode (plane 0)
   or UV_DC_PRED (plane>0, always 0). `row`/`col` are the leaf's LUMA mi
   position (this fn derives the plane's own subsampled position/pixel
   coordinates internally via `:subx`/`:suby` in `spec`)."
  [state frame-hdr row col avail-u? avail-l? q-idx mode spec]
  (let [{:keys [plane w subx suby delta-q-dc delta-q-ac plane-key]} spec
        log2 (case w 32 5 16 4)
        mi-cols (:mi-cols frame-hdr) mi-rows (:mi-rows frame-hdr)
        plane-mi-cols (bit-shift-right mi-cols subx) plane-mi-rows (bit-shift-right mi-rows suby)
        frame-w (* 4 plane-mi-cols) frame-h (* 4 plane-mi-rows)
        prow (bit-shift-right row suby) pcol (bit-shift-right col subx)
        x (* 4 pcol) y (* 4 prow)
        w4 (bit-shift-right w 2)
        plane0 (or (get state plane-key) (vec (repeat (* frame-w frame-h) 0)))
        pred (predict-intra mode plane0 frame-w frame-h x y avail-l? avail-u? log2 log2 8)
        plane1 (write-block plane0 frame-w x y w w pred)
        state1 (assoc state plane-key plane1)
        txb-skip-ctx (if (zero? plane)
                       0 ;; bw==w && bh==h for this phase's only luma block shape
                       (get-txb-skip-ctx-chroma state1 plane pcol prow w4 w4 w w w w plane-mi-cols plane-mi-rows))
        [eob quant state2] (read-coeffs state1 q-idx prow pcol plane-mi-cols plane-mi-rows txb-skip-ctx spec)]
    (if (pos? eob)
      (let [base-q-idx (:base-q-idx frame-hdr)
            clip3 (fn [lo hi v] (cond (< v lo) lo (> v hi) hi :else v))
            dc-q-index (clip3 0 255 (+ base-q-idx delta-q-dc))
            ac-q-index (clip3 0 255 (+ base-q-idx delta-q-ac))
            dc-quant (nth tables/Dc-Qlookup-8bit dc-q-index)
            ac-quant (nth tables/Ac-Qlookup-8bit ac-q-index)
            dq-denom (xform/dq-denom (if (= w 32) tables/TX_32X32 tables/TX_16X16))
            dequant (xform/dequantize quant w w dq-denom dc-quant ac-quant 8)
            residual (xform/inverse-transform-2d dequant log2 log2 8)
            hi (dec (bit-shift-left 1 8))
            recon (mapv (fn [p r] (let [v (+ p r)] (cond (< v 0) 0 (> v hi) hi :else v))) pred residual)
            plane2 (write-block plane1 frame-w x y w w recon)]
        [{:eob eob} (assoc state2 plane-key plane2)])
      [{:eob 0} state2])))

;; ---------------------------------------------------------------------
;; Plane specs -- bundles the tx-size shape (bwl/w/seg-eob/scan/subx/suby)
;; and the default-cdf-table/adaptation-state-key family (luma's own vs.
;; the shared-between-U-and-V chroma family) that `decode-transform-block`/
;; `read-coeffs` need. `delta-q-dc`/`delta-q-ac`/`plane` are filled in per
;; frame/per-plane in `make-decode-block-fn` below (`assoc`ed onto these
;; base maps).

(def ^:private luma-spec-base
  {:plane 0 :bwl 5 :w 32 :seg-eob 1024 :scan tables/Default-Scan-32x32
   :subx 0 :suby 0 :plane-key :luma-plane
   :txb-skip-cdf-key :txb-skip-cdfs :txb-skip-table tables/Default-Txb-Skip-Cdf-32x32
   :eob-pt-cdf-key :eob-pt-1024-cdf :eob-pt-table tables/Default-Eob-Pt-1024-Cdf-Luma
   :eob-extra-cdf-key :eob-extra-cdfs :eob-extra-table tables/Default-Eob-Extra-Cdf-32x32-Luma
   :coeff-base-eob-cdf-key :coeff-base-eob-cdfs :coeff-base-eob-table tables/Default-Coeff-Base-Eob-Cdf-32x32-Luma
   :coeff-base-cdf-key :coeff-base-cdfs :coeff-base-table tables/Default-Coeff-Base-Cdf-32x32-Luma
   :coeff-br-cdf-key :coeff-br-cdfs :coeff-br-table tables/Default-Coeff-Br-Cdf-32x32-Luma
   :dc-sign-cdf-key :dc-sign-cdfs :dc-sign-table tables/Default-Dc-Sign-Cdf-Luma})

;; chroma-spec-base: the cdf-key family here (:txb-skip-cdfs-chroma etc.)
;; is SHARED between the U (plane 1) and V (plane 2) specs below -- see
;; namespace docstring's cross-block-context section for why this is
;; correct per spec (ptype, not literal plane).
(def ^:private chroma-spec-base
  {:bwl 4 :w 16 :seg-eob 256 :scan tables/Default-Scan-16x16
   :subx 1 :suby 1
   :txb-skip-cdf-key :txb-skip-cdfs-chroma :txb-skip-table tables/Default-Txb-Skip-Cdf-16x16-Chroma
   :eob-pt-cdf-key :eob-pt-256-cdf-chroma :eob-pt-table tables/Default-Eob-Pt-256-Cdf-Chroma
   :eob-extra-cdf-key :eob-extra-cdfs-chroma :eob-extra-table tables/Default-Eob-Extra-Cdf-16x16-Chroma
   :coeff-base-eob-cdf-key :coeff-base-eob-cdfs-chroma :coeff-base-eob-table tables/Default-Coeff-Base-Eob-Cdf-16x16-Chroma
   :coeff-base-cdf-key :coeff-base-cdfs-chroma :coeff-base-table tables/Default-Coeff-Base-Cdf-16x16-Chroma
   :coeff-br-cdf-key :coeff-br-cdfs-chroma :coeff-br-table tables/Default-Coeff-Br-Cdf-16x16-Chroma
   :dc-sign-cdf-key :dc-sign-cdfs-chroma :dc-sign-table tables/Default-Dc-Sign-Cdf-Chroma})

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
        allow-intrabc? (pos? (:allow-intrabc frame-hdr))
        color? (= 3 (:num-planes frame-hdr))
        quant-params (:quantization-params frame-hdr)
        u-spec (merge chroma-spec-base
                      {:plane 1 :plane-key :u-plane
                       :delta-q-dc (:delta-q-u-dc quant-params) :delta-q-ac (:delta-q-u-ac quant-params)})
        v-spec (merge chroma-spec-base
                      {:plane 2 :plane-key :v-plane
                       :delta-q-dc (:delta-q-v-dc quant-params) :delta-q-ac (:delta-q-v-ac quant-params)})
        luma-spec (merge luma-spec-base {:delta-q-dc (:delta-q-y-dc quant-params) :delta-q-ac 0})]
    (fn [state row col mi-size avail-u? avail-l?]
      ;; SCOPE (see namespace docstring's cross-block-context section):
      ;; chroma residual is only validated for a single whole-frame leaf
      ;; (no real cross-leaf AboveLevelContext/AboveDcContext tracking
      ;; between TWO chroma leaves yet) -- throw rather than silently
      ;; mis-decode a second leaf's chroma.
      (when (and color? (or avail-u? avail-l?))
        (throw (ex-info "av1.decode-block: out of scope: chroma residual only supported for a single whole-frame BLOCK_32X32 leaf (no cross-leaf chroma AboveLevelContext/AboveDcContext tracking yet)"
                         {:reason :unsupported-multi-leaf-chroma})))
      (let [[skip state1] (read-skip state row col avail-u? avail-l?)
            state1 (read-cdef state1 row col (pos? skip) coded-lossless? enable-cdef? allow-intrabc?)
            ;; intra_segment_id(): segmentation_enabled forced 0 by
            ;; guard-frame-scope!, so segment_id=0 (assignment only, no read).
            [y-mode state2] (read-y-mode state1 row col avail-u? avail-l?)
            ;; intra_angle_info_y(): unconditional (no enable_angle_delta
            ;; gate, see read-angle-delta-y's docstring) -- must run right
            ;; after intra_frame_y_mode, before uv_mode/read_block_tx_size()/
            ;; residual(), else every subsequent bit desyncs.
            [_angle-delta-y state2] (read-angle-delta-y state2 y-mode)
            ;; uv_mode / intra_angle_info_uv(): spec order is
            ;; intra_angle_info_y() THEN (if HasChroma) uv_mode THEN
            ;; intra_angle_info_uv() THEN palette/filter_intra -- HasChroma
            ;; is always true here when color? (bw4=bh4=8 != 1, see
            ;; namespace docstring), and intra_angle_info_uv() is a
            ;; zero-bit no-op since UVMode is always UV_DC_PRED (not
            ;; directional, see read-uv-mode's docstring), so only the real
            ;; uv_mode symbol read is modeled.
            [uv-mode state2] (if color? (read-uv-mode state2 y-mode) [nil state2])]
        (guard-no-filter-intra! enable-filter-intra? mi-size y-mode)
        (let [tx-sz (tx-size-for mi-size)
              ;; spec #Decode block syntax: YModes[r+y][c+x]=YMode and
              ;; Skips[r+y][c+x]=skip are both written across the block's
              ;; WHOLE bw4xbh4 mi footprint (record-footprint!), not just
              ;; (row,col).
              state3 (-> state2
                         (record-footprint! :skips row col mi-size skip)
                         (record-footprint! :y-modes row col mi-size y-mode))]
          (if (pos? skip)
            ;; reset_block_context(): no residual coded for this leaf --
            ;; per spec, AboveLevelContext/AboveDcContext/LeftLevelContext/
            ;; LeftDcContext are reset to 0 across the block's bw4/bh4 span
            ;; (shifted by subsampling_x/y for plane>0) for EVERY plane
            ;; (1+2*HasChroma), so a LATER leaf's ctx correctly sees "no
            ;; coefficient" here, not stale/missing state. predict_intra
            ;; still runs for every plane (compute_prediction()/
            ;; transform_block() always predicts, regardless of skip --
            ;; only coeffs()/reconstruct are skipped).
            (let [frame-w (* 4 (:mi-cols frame-hdr)) frame-h (* 4 (:mi-rows frame-hdr))
                  x (* 4 col) y (* 4 row)
                  plane0 (or (:luma-plane state3) (vec (repeat (* frame-w frame-h) 0)))
                  pred (predict-intra y-mode plane0 frame-w frame-h x y avail-l? avail-u? 5 5 8)
                  plane1 (write-block plane0 frame-w x y 32 32 pred)
                  state4 (-> state3
                             (assoc :luma-plane plane1)
                             (record-above! 0 col 8 0 0)
                             (record-left! 0 row 8 0 0))
                  state5 (if color?
                           (reduce
                            (fn [st spec]
                              (let [{:keys [subx suby plane plane-key]} spec
                                    mi-cols (:mi-cols frame-hdr) mi-rows (:mi-rows frame-hdr)
                                    plane-mi-cols (bit-shift-right mi-cols subx) plane-mi-rows (bit-shift-right mi-rows suby)
                                    cframe-w (* 4 plane-mi-cols) cframe-h (* 4 plane-mi-rows)
                                    pcol (bit-shift-right col subx) prow (bit-shift-right row suby)
                                    cx (* 4 pcol) cy (* 4 prow)
                                    cplane0 (or (get st plane-key) (vec (repeat (* cframe-w cframe-h) 0)))
                                    cpred (pred/dc-predict cplane0 cframe-w cframe-h cx cy avail-l? avail-u? 4 4 8)
                                    cplane1 (write-block cplane0 cframe-w cx cy 16 16 cpred)]
                                (-> st
                                    (assoc plane-key cplane1)
                                    (record-above! plane pcol 4 0 0)
                                    (record-left! plane prow 4 0 0))))
                            state4 [u-spec v-spec])
                           state4)]
              [{:skip true :tx-size tx-sz :y-mode y-mode :uv-mode uv-mode} state5])
            (let [[luma-result state4] (decode-transform-block state3 frame-hdr row col avail-u? avail-l? q-idx y-mode luma-spec)
                  luma-result (assoc luma-result :skip false :tx-size tx-sz :y-mode y-mode :uv-mode uv-mode)]
              (if color?
                (let [[u-result state5] (decode-transform-block state4 frame-hdr row col avail-u? avail-l? q-idx UV_DC_PRED u-spec)
                      [v-result state6] (decode-transform-block state5 frame-hdr row col avail-u? avail-l? q-idx UV_DC_PRED v-spec)]
                  [(assoc luma-result :u-eob (:eob u-result) :v-eob (:eob v-result)) state6])
                [luma-result state4]))))))))
