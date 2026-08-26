FROM python:3.12-slim

# ffmpeg is required: faster-whisper decodes audio through it.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

ENV MODEL_CACHE_DIR=/models

# glibc allocator tuning, so the model's memory is actually given back.
#
# The reaper calls malloc_trim, but trim can only release pages that are
# wholly free, and by default the weights land on the heap in a way that
# leaves fragments pinned: 537MB before the trim, 250MB after, against a
# 102MB baseline. Two defaults are responsible.
#
# ARENA_MAX: glibc gives each thread its own arena, up to 8 x cores, and
# every arena that ever held an allocation keeps a heap. That is the whole
# reason this differed by host — the LXC and the VM do not have the same
# core count. Transcription is big matmuls, not a malloc storm, so funnelling
# every thread into two arenas costs nothing measurable.
#
# MMAP_THRESHOLD_: blocks above the threshold are mmap'd and are returned to
# the OS the instant they are freed, no trim required — which is exactly the
# shape of a weight tensor. Left alone, glibc raises the threshold up to 32MB
# as it observes such blocks being freed, so the large allocations migrate
# onto the heap over time. Pinning it keeps them mmap'd. Setting it also
# disables that dynamic adjustment for TRIM_THRESHOLD_, hence both.
ENV MALLOC_ARENA_MAX=2 \
    MALLOC_MMAP_THRESHOLD_=131072 \
    MALLOC_TRIM_THRESHOLD_=131072
EXPOSE 9000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "9000"]
