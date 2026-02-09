"""
Whisper transcription runner for Jervis.

Usage:
    python3 whisper_runner.py <audio_path> [options_json]

    options_json is a JSON string with transcription parameters:
    {
        "task": "transcribe",       # transcribe | translate
        "model": "base",            # tiny | base | small | medium | large-v3
        "language": null,           # ISO 639-1 code or null for auto-detect
        "beam_size": 5,             # 1-10
        "vad_filter": true,         # Silero VAD to skip silence
        "word_timestamps": false,
        "initial_prompt": null,     # Vocabulary hints
        "condition_on_previous_text": true,
        "no_speech_threshold": 0.6,
        "progress_file": null       # Path to write progress JSON (optional)
    }

    Backward-compatible: can also be called as:
        python3 whisper_runner.py <audio_path> <task> <model>

Output:
    JSON to stdout: {"text": "...", "segments": [...], "error": null}
    Progress to progress_file (if specified): {"percent": 45.2, "segments_done": 128}
"""
import json
import os
import sys
import time

from faster_whisper import WhisperModel


def parse_options():
    """Parse CLI arguments. Supports both new JSON format and legacy positional args."""
    audio_path = sys.argv[1]

    if len(sys.argv) > 2:
        # Try JSON format first
        try:
            opts = json.loads(sys.argv[2])
            return audio_path, opts
        except (json.JSONDecodeError, ValueError):
            pass

        # Legacy positional: whisper_runner.py <audio> <task> <model>
        task = sys.argv[2] if len(sys.argv) > 2 else "transcribe"
        model = sys.argv[3] if len(sys.argv) > 3 else "base"
        return audio_path, {"task": task, "model": model}

    return audio_path, {}


def write_progress(progress_file, percent, segments_done, elapsed_sec):
    """Write progress JSON to file for server-side monitoring."""
    if not progress_file:
        return
    try:
        progress = {
            "percent": round(percent, 1),
            "segments_done": segments_done,
            "elapsed_seconds": round(elapsed_sec, 1),
            "updated_at": time.time(),
        }
        # Write atomically: write to tmp file, then rename
        tmp_file = progress_file + ".tmp"
        with open(tmp_file, "w") as f:
            json.dump(progress, f)
        os.replace(tmp_file, progress_file)
    except Exception as e:
        print(f"Warning: failed to write progress: {e}", file=sys.stderr)


def main():
    audio_path, opts = parse_options()

    task = opts.get("task", "transcribe")
    model_name = opts.get("model", "base")
    language = opts.get("language")
    beam_size = opts.get("beam_size", 5)
    vad_filter = opts.get("vad_filter", True)
    word_timestamps = opts.get("word_timestamps", False)
    initial_prompt = opts.get("initial_prompt")
    condition_on_previous_text = opts.get("condition_on_previous_text", True)
    no_speech_threshold = opts.get("no_speech_threshold", 0.6)
    progress_file = opts.get("progress_file")

    print(f"Loading model: {model_name} (device=cpu)", file=sys.stderr)
    model = WhisperModel(model_name, device="cpu")
    print(f"Model loaded, starting transcription: task={task}, lang={language or 'auto'}", file=sys.stderr)

    # Build transcribe kwargs
    transcribe_kwargs = {
        "task": task,
        "beam_size": beam_size,
        "vad_filter": vad_filter,
        "word_timestamps": word_timestamps,
        "condition_on_previous_text": condition_on_previous_text,
        "no_speech_threshold": no_speech_threshold,
        "log_progress": True,
    }
    if language:
        transcribe_kwargs["language"] = language
    if initial_prompt:
        transcribe_kwargs["initial_prompt"] = initial_prompt

    segments_iter, info = model.transcribe(audio_path, **transcribe_kwargs)

    # Compute total audio duration for progress calculation
    total_duration = info.duration if info.duration and info.duration > 0 else None
    print(f"Audio duration: {total_duration:.1f}s, detected language: {info.language} (prob={info.language_probability:.2f})", file=sys.stderr)

    out_segments = []
    all_text = []
    start_time = time.time()
    last_progress_time = 0

    for seg in segments_iter:
        out_segments.append({
            "start": float(seg.start),
            "end": float(seg.end),
            "text": seg.text,
        })
        all_text.append(seg.text)

        # Update progress at most every 5 seconds
        now = time.time()
        if progress_file and total_duration and (now - last_progress_time) >= 5:
            percent = min(99.9, (seg.end / total_duration) * 100)
            elapsed = now - start_time
            write_progress(progress_file, percent, len(out_segments), elapsed)
            last_progress_time = now
            print(f"Progress: {percent:.1f}% ({len(out_segments)} segments, {elapsed:.0f}s elapsed)", file=sys.stderr)

    # Final progress = 100%
    elapsed = time.time() - start_time
    if progress_file:
        write_progress(progress_file, 100.0, len(out_segments), elapsed)

    print(f"Transcription complete: {len(out_segments)} segments, {len(''.join(all_text))} chars, {elapsed:.1f}s", file=sys.stderr)

    # Output result JSON to stdout
    result = {
        "text": "".join(all_text),
        "segments": out_segments,
        "language": info.language,
        "language_probability": round(info.language_probability, 3),
        "duration": info.duration,
    }
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
