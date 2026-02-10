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
        "progress_file": null,      # Path to write progress JSON (optional)
        "extraction_ranges": null   # [{start, end, segment_index}, ...] for re-transcription
    }

    Backward-compatible: can also be called as:
        python3 whisper_runner.py <audio_path> <task> <model>

Output:
    JSON to stdout: {"text": "...", "segments": [...], "error": null}
    Progress to progress_file (if specified): {"percent": 45.2, "segments_done": 128}

    With extraction_ranges, output includes range_mapping:
    {"text": "...", "segments": [...], "range_mapping": [{range_index, segment_index, ...}]}
"""
import json
import os
import subprocess
import sys
import tempfile
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


def extract_ranges(audio_path, ranges, output_dir):
    """Extract audio ranges using ffmpeg, concatenate into single file.

    Each range is extracted with ±padding from the source audio,
    then concatenated with 0.5s silence gaps between them.

    Returns (concat_file_path, offsets_list).
    """
    range_files = []
    offsets = []
    current_offset = 0.0

    for i, r in enumerate(ranges):
        out = os.path.join(output_dir, f"range_{i}.wav")
        start = r["start"]
        end = r["end"]
        duration = end - start
        subprocess.run(
            [
                "ffmpeg", "-y", "-i", audio_path,
                "-ss", str(start), "-t", str(duration),
                "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", out,
            ],
            check=True, capture_output=True,
        )
        range_files.append(out)
        offsets.append({
            "range_index": i,
            "segment_index": r.get("segment_index", i),
            "offset": current_offset,
            "original_start": start,
            "original_end": end,
        })
        current_offset += duration + 0.5  # 0.5s gap

        print(
            f"Extracted range {i}: {start:.1f}s-{end:.1f}s ({duration:.1f}s) -> {out}",
            file=sys.stderr,
        )

    if len(range_files) == 1:
        # Single range — no need to concatenate
        return range_files[0], offsets

    # Generate 0.5s silence file
    silence = os.path.join(output_dir, "silence.wav")
    subprocess.run(
        [
            "ffmpeg", "-y", "-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono",
            "-t", "0.5", "-c:a", "pcm_s16le", silence,
        ],
        check=True, capture_output=True,
    )

    # Build concat file list
    list_file = os.path.join(output_dir, "filelist.txt")
    with open(list_file, "w") as f:
        for i, rf in enumerate(range_files):
            f.write(f"file '{rf}'\n")
            if i < len(range_files) - 1:
                f.write(f"file '{silence}'\n")

    # Concatenate using ffmpeg concat
    concat_file = os.path.join(output_dir, "concat.wav")
    subprocess.run(
        [
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", list_file, "-c", "copy", concat_file,
        ],
        check=True, capture_output=True,
    )

    print(
        f"Concatenated {len(range_files)} ranges into {concat_file} "
        f"(total offset: {current_offset:.1f}s)",
        file=sys.stderr,
    )
    return concat_file, offsets


def map_segments_to_ranges(whisper_segments, offsets):
    """Map Whisper output segments back to original range indices.

    Each Whisper segment's time position determines which extraction range
    it belongs to (based on offset table from concatenation).

    Returns list of mapped segments with original timing + range metadata.
    """
    mapped = []
    for seg in whisper_segments:
        seg_mid = (seg["start"] + seg["end"]) / 2
        best_offset = offsets[0]
        for off in offsets:
            if seg_mid >= off["offset"]:
                best_offset = off
            else:
                break

        # Adjust timestamps back to original audio timeline
        time_in_range = seg["start"] - best_offset["offset"]
        mapped.append({
            "start": best_offset["original_start"] + time_in_range,
            "end": best_offset["original_start"] + (seg["end"] - best_offset["offset"]),
            "text": seg["text"],
            "range_index": best_offset["range_index"],
            "segment_index": best_offset["segment_index"],
        })

    return mapped


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
    extraction_ranges = opts.get("extraction_ranges")

    # Handle extraction_ranges mode: extract + concat → transcribe concatenated file
    range_mapping = None
    cleanup_dir = None
    transcribe_path = audio_path

    if extraction_ranges:
        print(
            f"Extraction mode: {len(extraction_ranges)} ranges from {audio_path}",
            file=sys.stderr,
        )
        cleanup_dir = tempfile.mkdtemp(prefix="whisper_extract_")
        try:
            transcribe_path, range_mapping = extract_ranges(
                audio_path, extraction_ranges, cleanup_dir,
            )
        except subprocess.CalledProcessError as e:
            print(f"ffmpeg extraction failed: {e.stderr.decode()}", file=sys.stderr)
            result = {
                "text": "",
                "segments": [],
                "error": f"ffmpeg extraction failed: {e.stderr.decode()[:500]}",
            }
            print(json.dumps(result, ensure_ascii=False))
            return

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

    segments_iter, info = model.transcribe(transcribe_path, **transcribe_kwargs)

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

    # If extraction mode, map segments back to original ranges
    if range_mapping is not None:
        mapped_segments = map_segments_to_ranges(out_segments, range_mapping)
        # Group text by segment_index for the result
        text_by_segment = {}
        for ms in mapped_segments:
            si = ms["segment_index"]
            text_by_segment.setdefault(si, []).append(ms["text"])

        result = {
            "text": "".join(all_text),
            "segments": mapped_segments,
            "range_mapping": range_mapping,
            "text_by_segment": {str(k): " ".join(v).strip() for k, v in text_by_segment.items()},
            "language": info.language,
            "language_probability": round(info.language_probability, 3),
            "duration": info.duration,
        }
    else:
        # Standard transcription output
        result = {
            "text": "".join(all_text),
            "segments": out_segments,
            "language": info.language,
            "language_probability": round(info.language_probability, 3),
            "duration": info.duration,
        }

    print(json.dumps(result, ensure_ascii=False))

    # Cleanup extraction temp files
    if cleanup_dir:
        import shutil
        try:
            shutil.rmtree(cleanup_dir)
        except Exception:
            pass


if __name__ == "__main__":
    main()
