(ns av1.encode
  "Top-level AV1 ENCODE orchestration (2026-07 AV1 encode task,
   ADR-2607122000 Migration step 9 continuation -- \"AV1 encode side\",
   mirroring kotoba-lang/org-iso-h264's own decode->encode task strategy:
   start from the simplest real, independently-decodable case). Ties
   together every encode-side namespace this task added
   (av1.bitwriter/av1.bool-encoder/av1.transform's forward-transform-
   2d+quantize/av1.encode-block/av1.sequence-header's `write`/
   av1.frame-header's `write`/av1.obu's `write-obu`) into ONE function,
   `encode-keyframe`, that turns a 32x32 8-bit luma plane into a complete,
   legal, standalone AV1 OBU stream (the same 'low overhead bitstream
   format' av1.obu/parse-all consumes, and the same format `ffmpeg -f obu`/
   `dav1d`/`aomdec` read).

   SCOPE (see av1.encode-block's namespace docstring for the full
   boundary): a single monochrome 32x32 KEY_FRAME, ONE BLOCK_32X32/
   PARTITION_NONE leaf covering the whole frame, DC_PRED, TX_32X32/
   DCT_DCT. This is exactly the mirror image of av1.decode-block's very
   FIRST pixel-reconstruction milestone (`keyframe-32x32-gradient.obu`'s
   scope, before that namespace's V_PRED/PAETH/SMOOTH/chroma/ADST/inter
   extensions) -- deliberately, since that's this org's own established
   practice for a first encode pass (narrowest real, validated slice
   first, extend later).

   SEQUENCE HEADER CHOICE: `reduced_still_picture_header=1` (see
   av1.sequence-header/write's docstring for why this is the simplest
   legal AV1 profile, not a shortcut outside the spec -- AVIF still images
   commonly use exactly this profile, and dav1d/aomdec fully support it)."
  (:require [av1.bitwriter :as bw]
            [av1.bool-encoder :as be]
            [av1.transform :as xform]
            [av1.tables :as tables]
            [av1.tile-group :as tg]
            [av1.encode-block :as eb]
            [av1.decode-block :as db]
            [av1.sequence-header :as sh]
            [av1.frame-header :as fh]
            [av1.obu :as obu]))

(def FRAME-SIZE 32)
(def MI-SIZE 8) ;; FRAME-SIZE / 4

;; ---------------------------------------------------------------------
;; Partition tree -- this repo's encode scope only ever produces the exact
;; shape av1.tile-group/decode-partition documents for an 8x8-mi (32x32px)
;; frame under a 64x64 superblock: top-level BLOCK_64X64 call has
;; hasRows=hasCols=false (mi-rows=mi-cols=8, half4x4=8, `0+8 < 8` is false)
;; -> PARTITION_SPLIT with ZERO bits (spec's own structural forcing, see
;; av1.tile-group's `decode-partition` `:else` branch); of its 4
;; BLOCK_32X32 children, only the top-left (0,0) is in-bounds (the other 3
;; start at row/col 8, `>= mi-rows/mi-cols`, spec's `return 0` with ZERO
;; reads); the one real child has hasRows=hasCols=true (half4x4=4 for
;; BLOCK_32X32, `0+4 < 8` true) -> a REAL `partition` symbol read against
;; `Default_Partition_W32_Cdf[ctx=0]` (avail-u?/avail-l? both false at the
;; frame origin) -- this fn writes exactly that one real symbol
;; (PARTITION_NONE) and nothing else, matching the zero real reads
;; everywhere else in this tree.

(defn write-partition-none
  "Writes the ONE real `partition` symbol this frame's fixed partition-tree
   shape needs (see above) -- PARTITION_NONE at BLOCK_32X32, ctx=0."
  [state]
  (let [cdf (nth tg/Default_Partition_W32_Cdf 0)
        adapt? (:cdf-adapt? state true)
        [_cdf' enc'] (be/encode-symbol (:enc state) cdf tg/PARTITION_NONE adapt?)]
    (assoc state :enc enc')))

;; ---------------------------------------------------------------------
;; Prediction -- DC_PRED with NO available neighbors (this frame's only
;; leaf covers the whole frame, so avail-u?/avail-l? are both false, spec
;; 7.11.2.4's \"neither available\" case): predicted value is the fixed
;; `1 << (BitDepth-1)` == 128 constant, matching av1.intra-pred/dc-predict
;; exactly for this shape (not re-derived here -- see luma-spec-32's
;; :plane-key / av1.decode-block/decode-transform-block for the real
;; decode-side computation this mirrors).

(def DC-PRED-NO-NEIGHBORS 128)

;; ---------------------------------------------------------------------
;; luma-spec-32 -- the SAME map shape av1.decode-block's own
;; `luma-spec-base` uses (deliberately duplicated rather than requiring
;; av1.decode-block's private var: `luma-spec-base` isn't public, and this
;; is a small, static, well-documented literal -- see av1.decode-block's
;; own docstring for what each key means).

(def luma-spec-32
  {:plane 0 :bwl 5 :w 32 :seg-eob 1024 :scan tables/Default-Scan-32x32
   :subx 0 :suby 0 :tx-sz tables/TX_32X32
   :txb-skip-cdf-key :txb-skip-cdfs :txb-skip-table tables/Default-Txb-Skip-Cdf-32x32
   :eob-pt-cdf-key :eob-pt-1024-cdf :eob-pt-table tables/Default-Eob-Pt-1024-Cdf-Luma
   :eob-extra-cdf-key :eob-extra-cdfs :eob-extra-table tables/Default-Eob-Extra-Cdf-32x32-Luma
   :coeff-base-eob-cdf-key :coeff-base-eob-cdfs :coeff-base-eob-table tables/Default-Coeff-Base-Eob-Cdf-32x32-Luma
   :coeff-base-cdf-key :coeff-base-cdfs :coeff-base-table tables/Default-Coeff-Base-Cdf-32x32-Luma
   :coeff-br-cdf-key :coeff-br-cdfs :coeff-br-table tables/Default-Coeff-Br-Cdf-32x32-Luma
   :dc-sign-cdf-key :dc-sign-cdfs :dc-sign-table tables/Default-Dc-Sign-Cdf-Luma})

;; ---------------------------------------------------------------------
;; encode-tile-payload -- the tile_group_obu()'s worth of bits: partition
;; tree (write-partition-none, plus the zero-bit SPLIT/out-of-bounds
;; structure, which needs no explicit writes at all) + decode_block()
;; (read_skip/read_cdef/intra_frame_y_mode/intra_angle_info_y/
;; read_block_tx_size/coeffs(), all mirrored via av1.encode-block, except
;; the several that are zero-bit-forced for this scope and so need no
;; write call at all -- read_cdef (enable_cdef=0), intra_angle_info_y
;; (DC_PRED isn't directional), read_block_tx_size (TX_MODE_LARGEST forces
;; TxSize directly)).

(defn encode-tile-payload
  "Returns a flat byte vector: the fully bool-encoded, byte-aligned tile
   data for this frame's ONE tile (`init_symbol`/writes/`exit_symbol`,
   mirroring av1.tile-group/parse-tile-group-obu's own per-tile
   init_symbol/decode-partition-over-superblocks/exit_symbol loop, but
   for exactly the one real leaf this frame's fixed shape has -- see
   `write-partition-none`'s docstring). `quant` is the target luma
   TX_32X32 coefficient array (already-quantized, raster position, from
   av1.transform/forward-transform-2d + quantize) -- pass an all-zero
   vector for a `skip=1` (zero-residual) block.

   `q-idx` is `av1.tables/q-ctx-idx`'s mapping of base_q_idx to this
   repo's CDF-table q-context index (matching how av1.decode-block's
   `make-decode-block-fn` computes it once per frame)."
  [q-idx quant cdf-adapt?]
  (let [skip (if (every? zero? quant) 1 0)
        state0 {:enc (be/init) :cdf-adapt? cdf-adapt? :skips {} :y-modes {}}
        state1 (write-partition-none state0)
        state2 (eb/write-skip state1 0 0 false false skip)
        ;; read_cdef(): zero-bit no-op for this scope (enable_cdef=0 from
        ;; the sequence header, see av1.decode-block/read-cdef's own
        ;; `(not enable-cdef?)` short-circuit) -- no write needed.
        state3 (eb/write-y-mode state2 0 0 false false db/DC_PRED)
        ;; intra_angle_info_y(): zero-bit no-op (DC_PRED isn't
        ;; is_directional_mode) -- no write needed.
        state4 (-> state3
                   (update :skips assoc [0 0] skip)
                   (update :y-modes assoc [0 0] db/DC_PRED))
        ;; read_block_tx_size(): zero-bit no-op (TX_MODE_LARGEST forces
        ;; TxSize=Max_Tx_Size_Rect[BLOCK_32X32]=TX_32X32 directly) -- no
        ;; write needed. residual(): only when skip=0 (mirrors
        ;; av1.decode-block's own skip-branch, which skips coeffs()
        ;; entirely and only predicts).
        state5 (if (zero? skip)
                 (second (eb/write-coeffs state4 q-idx 0 0 MI-SIZE MI-SIZE 0 luma-spec-32 quant))
                 state4)]
    (be/done (:enc state5))))

;; ---------------------------------------------------------------------
;; Top-level: pixels -> forward transform/quantize -> tile payload ->
;; frame_header+tile_group (one OBU_FRAME) -> full OBU stream (temporal
;; delimiter + sequence header + frame).

(defn encode-keyframe
  "`pixels`: flat row-major 32x32 vector of 8-bit luma samples (the target
   reconstructed image -- what THIS repo's own decode-block, and a real
   independent decoder like dav1d, should reconstruct when decoding this
   fn's output, up to the expected lossy-transform-coding error for
   non-flat content, see av1.transform's forward-transform-2d/quantize
   docstrings). `base-q-idx` (1..255) is this frame's quantizer.

   Returns a flat byte vector: OBU_TEMPORAL_DELIMITER (empty) ++
   OBU_SEQUENCE_HEADER ++ OBU_FRAME (frame_header_obu() + tile_group_obu()
   combined, spec #Frame OBU syntax) -- the same 'low overhead bitstream
   format' av1.obu/parse-all / av1.tile-group/parse-frame-obu / real
   `dav1d`/`aomdec`/`ffmpeg -f obu` all consume."
  ([pixels base-q-idx] (encode-keyframe pixels base-q-idx {}))
  ([pixels base-q-idx {:keys [cdf-adapt? skip?] :or {cdf-adapt? true skip? false}}]
   (when (not= (count pixels) (* FRAME-SIZE FRAME-SIZE))
     (throw (ex-info "av1.encode/encode-keyframe: pixels must be a flat 32x32 vector"
                      {:count (count pixels)})))
   (let [pred (vec (repeat (* FRAME-SIZE FRAME-SIZE) DC-PRED-NO-NEIGHBORS))
         residual (mapv - pixels pred)
         q-idx (tables/q-ctx-idx base-q-idx)
         dc-quant (nth tables/Dc-Qlookup-8bit base-q-idx) ;; delta_q_y_dc=0 -> dc-q-index==base-q-idx
         ac-quant (nth tables/Ac-Qlookup-8bit base-q-idx)
         quant (if skip?
                 (vec (repeat (* FRAME-SIZE FRAME-SIZE) 0))
                 (let [coeff (xform/forward-transform-2d residual 5 5)]
                   (xform/quantize coeff FRAME-SIZE FRAME-SIZE (xform/dq-denom tables/TX_32X32) dc-quant ac-quant)))
         tile-bytes (encode-tile-payload q-idx quant cdf-adapt?)
         ;; -- OBU_SEQUENCE_HEADER -- needs spec 5.3.4 trailing_bits()
         ;; padding (a '1' bit then zero-pad), NOT plain zero-padding --
         ;; see av1.bitwriter/trailing-bits's docstring: real decoders
         ;; (ffmpeg/dav1d) reject an OBU_SEQUENCE_HEADER (or standalone
         ;; OBU_FRAME_HEADER/OBU_METADATA) payload padded with plain
         ;; zeros instead.
         seq-payload (bw/to-bytes (bw/trailing-bits (sh/write (bw/make-writer) {:max-frame-width FRAME-SIZE :max-frame-height FRAME-SIZE})))
         seq-obu (bw/to-bytes (obu/write-obu (bw/make-writer) 1 seq-payload))
         ;; -- OBU_FRAME: frame_header_obu() + byte_alignment() + tile_group_obu() --
         frame-payload-writer (-> (bw/make-writer)
                                   (fh/write {:base-q-idx base-q-idx})
                                   (bw/byte-alignment))
         frame-payload-writer2 (reduce (fn [w byte] (bw/f w 8 byte)) frame-payload-writer tile-bytes)
         frame-payload (bw/to-bytes frame-payload-writer2)
         frame-obu (bw/to-bytes (obu/write-obu (bw/make-writer) 6 frame-payload))
         ;; -- OBU_TEMPORAL_DELIMITER (empty payload) --
         td-obu (bw/to-bytes (obu/write-obu (bw/make-writer) 2 []))]
     (vec (concat td-obu seq-obu frame-obu)))))
