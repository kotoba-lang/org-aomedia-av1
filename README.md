# kotoba-lang/org-aomedia-av1

Zero-dep portable `.cljc` AV1 (AOMedia Video 1, AV1 Bitstream & Decoding
Process Specification) bitstream **framing + symbol decoder primitives**.
Named `org-aomedia-av1` (not `org-iso-*`/`org-ietf-*`) because AV1 is
published by the Alliance for Open Media, not ISO/IEC or the IETF -- see
`kotoba-lang/org-iso-h264`'s README for the sibling naming rationale.

## Scope (Phase 0)

This is **Phase 0** of AV1 support per `com-junkawasaki/root` ADR-2607122000
(`90-docs/adr/2607122000-utsushi-pixel-codec-r05-cljc-datomic.md`) Migration
step 9: the foundation layer *before* pixel reconstruction, mirroring how
`org-iso-h264` started with NAL/SPS/PPS framing before intra pixel decode
was added. AV1's spec is far larger than H.264's, so per the ADR this repo
does **not** attempt broad common-code sharing with H.264 -- only
`codec-primitives`'s narrow generic shapes (`BlockTransform`/`QuantScale`
protocols, `scan`/`unscan`) are candidates for reuse, and Phase 0 doesn't
need any of them yet (no transform/quantization-table work has started).

Implemented, transcribed field-for-field from the spec (not reconstructed
from memory -- see each namespace's docstring for the exact spec section
and source snapshot used: `AOMediaCodec/av1-spec` master,
`06.bitstream.syntax.md`/`07.bitstream.semantics.md`/`09.parsing.process.md`/
`04.conventions.md`, fetched 2026-07-12):

| ns | role |
|---|---|
| `av1.bitreader` | MSB-first bit reader + descriptor primitives: `f(n)`, `uvlc()`, `le(n)`, `leb128()`, `su(n)`, `ns(n)`, `byte_alignment()` |
| `av1.obu` | `obu_header()` / `obu_extension_header()` / the top-level OBU loop -- every OBU is self-delimiting via `leb128 obu_size`, so unparsed payload can always be skipped byte-exactly to the next OBU |
| `av1.sequence-header` | full `sequence_header_obu()`: profile/still-picture/timing-info/decoder-model-info/operating-points/frame dimensions/`color_config()` |
| `av1.frame-header` | `uncompressed_header()` **through `quantization_params()`**, intra frames only (`KEY_FRAME`/`INTRA_ONLY_FRAME`): frame_type, show_frame, frame size (`frame_size()`/`superres_params()`/`render_size()`), `tile_info()`, and `base_q_idx`/per-plane delta-Q/quant-matrix fields |
| `av1.bool-decoder` | the AV1 "Symbol decoder" (daala-derived non-binary arithmetic coder, spec 8.2): `init_symbol`/`read_bool`/`read_literal`/`read_symbol` with CDF adaptation. Wholly separate implementation from H.264's CAVLC/CABAC (`h264.cavlc`) -- different coding scheme entirely |

**Explicitly NOT implemented (next phase)**: `segmentation_params()`
onward inside `uncompressed_header()` (loop filter, CDEF, loop
restoration, tx mode, skip mode, global motion, film grain), inter frames
(`frame_type == INTER_FRAME`) and `show_existing_frame == 1` (both need
cross-frame reference-frame state this phase doesn't track -- `av1.frame-
header/parse` throws `ex-info` rather than silently mis-parsing them), the
~60 CDF tables the real decoder initializes (`Default_Intra_Frame_Y_Mode_
Cdf` etc.) and all coefficient/mode/partition-tree syntax that reads
through them (`tile_group_obu`/`decode_partition`/`decode_block`/
`coeffs()`), DCT/ADST transform kernels, intra prediction, and film grain
synthesis. All of this is real pixel reconstruction, deliberately out of
scope for Phase 0 (OBU framing + bool decoder primitives only).

## Validation

`av1.obu-test`/`av1.sequence-header-test`/`av1.frame-header-test` validate
against a **real SVT-AV1-encoded stream** (`resources/av1/fixtures/
keyframe-64x48.obu`, checked in), generated with:

```sh
ffmpeg -y -f lavfi -i "testsrc=size=64x48:rate=1" -frames:v 1 \
  -pix_fmt yuv420p -c:v libsvtav1 -f obu keyframe-64x48.obu
```

(ffmpeg 8.1.1, `libsvtav1` encoder -- `-f obu` emits the spec's "low
overhead bitstream format" directly, no container/length-stripping
needed). The decoded `max_frame_width`/`max_frame_height` (sequence
header) and `frame_width`/`frame_height` (frame header, via a completely
different syntax path: `frame_size()`/`superres_params()`/
`compute_image_size()`) both independently come out to exactly 64x48 --
the same "real encoder output, cross-checked dimensions" validation level
as `org-iso-h264`'s `h264.sps-test` against real libx264 output.

`av1.bool-decoder-test` validates the symbol decoder against **hand-
derived bit vectors**: the spec's `init_symbol`/`read_symbol` arithmetic is
executed by hand (documented step by step in the test namespace's
docstring) for two contrasting inputs (all-zero bits, all-one bits) with
`sz=2` bytes, since a real encoded tile requires the full pixel-
reconstruction pipeline this phase doesn't build. Full real-data
end-to-end verification of the bool decoder is deferred to the pixel-
reconstruction phase.

## Usage

```clojure
(require '[av1.bitreader :as br] '[av1.obu :as obu]
         '[av1.sequence-header :as sh] '[av1.frame-header :as fh]
         '[av1.bool-decoder :as bd])

(def r (br/make-reader av1-bytes))
(def o (obu/parse-obu r))                      ; => {:header {...} :obu-size N ...}
(def seq-hdr (sh/parse (:reader-at-payload seq-obu)))
(def frame-hdr (fh/parse (:reader-at-payload frame-obu) seq-hdr))
;; => {:frame-type ... :show-frame ... :frame-width :frame-height
;;     :tile-info {...} :base-q-idx ... :quantization-params {...}}

(def bd-state (bd/init-symbol reader tile-size-bytes))
(def [bit bd-state'] (bd/read-bool bd-state))
```

## Test

```sh
clojure -M:test
```

## Lint

```sh
clojure -M:lint
```
