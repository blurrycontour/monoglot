"""KB-Whisper transcription worker.

Swedish-finetuned Whisper from KBLab (National Library of Sweden), not vanilla
OpenAI Whisper: kb-whisper-small outperforms whisper-large-v3 on Swedish.

Speaks float seconds; the Go service converts to integer milliseconds at its
boundary and stores nothing as a float.
"""

import gc
import logging
import os
import threading
import time

from fastapi import FastAPI, HTTPException
from faster_whisper import WhisperModel
from pydantic import BaseModel

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("worker")


class _DropHealthChecks(logging.Filter):
    """Keep the container health check out of the log.

    Compose polls /health continuously, and uvicorn logs an access line for
    every one of them. Nothing that matters here — a transcription starting,
    a model load, a failure — survives that volume.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        return "/health" not in record.getMessage()


logging.getLogger("uvicorn.access").addFilter(_DropHealthChecks())

MODEL_NAME = os.getenv("WHISPER_MODEL", "KBLab/kb-whisper-small")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
BEAM_SIZE = int(os.getenv("WHISPER_BEAM_SIZE", "5"))

app = FastAPI(title="monoglot-whisper-worker")
_model = None
_model_lock = threading.Lock()
_last_used = 0.0

# Whisper weights are the bulk of this container's memory. Between runs there
# is no reason to hold them: unloading returns the container to roughly its
# baseline, and reloading costs ~45s on the next transcription, which is
# negligible against a nightly batch.
IDLE_UNLOAD_SECONDS = int(os.getenv("WHISPER_IDLE_UNLOAD_SECONDS", "600"))
# Transcription is CPU-bound. Serialise it so two concurrent requests cannot
# thrash the box; the Go side already runs items one at a time, this is a belt.
_transcribe_lock = threading.Lock()


def get_model() -> WhisperModel:
    """Load the model once, lazily, on first use."""
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                log.info(
                    "loading %s (device=%s compute_type=%s)",
                    MODEL_NAME, DEVICE, COMPUTE_TYPE,
                )
                t0 = time.time()
                # KBLab publishes CTranslate2 weights under the "main" revision
                # of the same repo, which faster-whisper resolves directly from
                # the model id. No special checkpoint path is needed.
                _model = WhisperModel(
                    MODEL_NAME,
                    device=DEVICE,
                    compute_type=COMPUTE_TYPE,
                    download_root=os.getenv("MODEL_CACHE_DIR", "/models"),
                )
                log.info("model loaded in %.1fs", time.time() - t0)
    return _model


class TranscribeRequest(BaseModel):
    audio_path: str
    # ASR language hint, supplied per item by the API. Defaults to Swedish
    # since that is what this instance ships with.
    language: str = "sv"


# Where the current transcription has got to, so the app can show a bar rather
# than a spinner. faster-whisper hands back a generator, so this costs one
# assignment per segment: the loop that builds the response is already walking
# it, and nothing extra is computed.
_progress: dict = {"path": None, "fraction": 0.0, "started": 0.0, "duration": 0.0}


@app.get("/progress")
def progress():
    """What is being transcribed right now, and how far in.

    Empty between jobs. Deliberately not per-item: the worker runs one
    transcription at a time behind a lock, so there is only ever one answer.
    """
    p = dict(_progress)
    if p["path"] and p["started"]:
        p["elapsed"] = round(time.time() - p["started"], 1)
    return p


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "device": DEVICE,
        "compute_type": COMPUTE_TYPE,
        "loaded": _model is not None,
        "idle_unload_seconds": IDLE_UNLOAD_SECONDS,
        "idle_seconds": round(time.time() - _last_used, 1) if _last_used else None,
    }


@app.post("/warm")
def warm():
    """Force model load, so the first real transcription is not also a download."""
    get_model()
    return {"status": "ok", "model": MODEL_NAME}


@app.post("/transcribe")
def transcribe(req: TranscribeRequest):
    if not os.path.exists(req.audio_path):
        raise HTTPException(status_code=400, detail=f"no such file: {req.audio_path}")

    global _last_used
    model = get_model()
    _last_used = time.time()
    t0 = time.time()

    with _transcribe_lock:
        _progress.update({
            "path": req.audio_path, "fraction": 0.0,
            "started": time.time(), "duration": 0.0,
        })
        segments, info = model.transcribe(
            req.audio_path,
            language=req.language or "sv",
            beam_size=BEAM_SIZE,
            word_timestamps=True,
            # Klartext is clean studio speech with pauses between news items.
            # VAD filtering keeps silence out of the word timings, which is
            # what makes the highlight track the voice rather than drift.
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 500},
            condition_on_previous_text=False,
        )

        _progress["duration"] = float(info.duration or 0)

        out_segments = []
        for seg in segments:
            # The generator is consumed lazily, so seg.end is genuinely how far
            # through the audio the model has got.
            if _progress["duration"]:
                _progress["fraction"] = min(1.0, float(seg.end) / _progress["duration"])
            words = []
            for w in (seg.words or []):
                text = w.word.strip()
                if not text:
                    continue
                words.append({
                    "word": text,
                    "start": round(float(w.start), 3),
                    "end": round(float(w.end), 3),
                })
            out_segments.append({
                "start": round(float(seg.start), 3),
                "end": round(float(seg.end), 3),
                "text": seg.text.strip(),
                "words": words,
            })

    _progress.update({"path": None, "fraction": 0.0, "started": 0.0, "duration": 0.0})
    _last_used = time.time()
    elapsed = time.time() - t0
    n_words = sum(len(s["words"]) for s in out_segments)
    log.info(
        "transcribed %s [%s]: %d segments, %d words, %.1fs audio in %.1fs (%.1fx)",
        req.audio_path, req.language, len(out_segments), n_words,
        info.duration, elapsed, info.duration / elapsed if elapsed else 0,
    )

    if not out_segments:
        raise HTTPException(status_code=500, detail="whisper produced no segments")


    return {
        "language": info.language,
        "duration": round(float(info.duration), 3),
        "model": MODEL_NAME,
        "segments": out_segments,
    }


def _idle_reaper() -> None:
    """Drops the model after a period with no transcriptions."""
    global _model
    while True:
        time.sleep(30)
        if IDLE_UNLOAD_SECONDS <= 0 or _model is None:
            continue
        idle = time.time() - _last_used
        if idle < IDLE_UNLOAD_SECONDS:
            continue
        with _model_lock:
            if _model is None:
                continue
            # Only unload when nothing is mid-transcription.
            if not _transcribe_lock.acquire(blocking=False):
                continue
            try:
                log.info("idle for %.0fs, unloading %s", idle, MODEL_NAME)
                _model = None
                gc.collect()
            finally:
                _transcribe_lock.release()


# Started last, so every module-level name the reaper reads already exists.
threading.Thread(target=_idle_reaper, daemon=True, name="idle-reaper").start()
