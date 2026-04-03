#!/usr/bin/env python3
"""
Extract voice samples from meeting recordings for XTTS fine-tuning.

Pipeline:
1. Load meeting recordings from KB/storage
2. Run Whisper diarization to identify user's voice segments
3. Extract clean segments (no overlap, no noise)
4. Output: directory of wav files + transcripts

Usage:
    python extract_voice_samples.py --input-dir /path/to/recordings --output-dir /path/to/samples

Requirements:
    pip install whisperx soundfile numpy torch
    Runs on VD GPU VM for Whisper acceleration.
"""

import argparse
import json
import logging
import os
from pathlib import Path

logger = logging.getLogger(__name__)


def extract_samples(
    input_dir: str,
    output_dir: str,
    target_speaker: str = "SPEAKER_00",
    min_duration: float = 3.0,
    max_duration: float = 15.0,
    min_segments: int = 50,
):
    """Extract voice samples from recordings using Whisper diarization."""
    import soundfile as sf
    import numpy as np

    input_path = Path(input_dir)
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    # Find audio files
    audio_files = sorted(
        input_path.glob("*.wav")
    ) + sorted(
        input_path.glob("*.m4a")
    ) + sorted(
        input_path.glob("*.mp3")
    )

    if not audio_files:
        logger.error(f"No audio files found in {input_dir}")
        return

    logger.info(f"Found {len(audio_files)} audio files")

    try:
        import whisperx
    except ImportError:
        logger.error("whisperx not installed. Run: pip install whisperx")
        return

    # Load WhisperX model
    import torch
    device = "cuda" if torch.cuda.is_available() else "cpu"
    compute_type = "float16" if device == "cuda" else "int8"

    logger.info(f"Loading WhisperX model on {device}")
    model = whisperx.load_model("large-v3", device, compute_type=compute_type)

    segments_extracted = 0
    metadata = []

    for audio_file in audio_files:
        logger.info(f"Processing: {audio_file.name}")

        try:
            # Load and transcribe
            audio = whisperx.load_audio(str(audio_file))
            result = model.transcribe(audio, batch_size=16, language="cs")

            # Align timestamps
            model_a, metadata_a = whisperx.load_align_model(language_code="cs", device=device)
            result = whisperx.align(result["segments"], model_a, metadata_a, audio, device)

            # Diarize
            diarize_model = whisperx.DiarizationPipeline(device=device)
            diarize_segments = diarize_model(audio)
            result = whisperx.assign_word_speakers(diarize_segments, result)

            # Extract target speaker segments
            for seg in result.get("segments", []):
                speaker = seg.get("speaker", "")
                if speaker != target_speaker:
                    continue

                start = seg["start"]
                end = seg["end"]
                duration = end - start

                if duration < min_duration or duration > max_duration:
                    continue

                text = seg.get("text", "").strip()
                if not text or len(text) < 10:
                    continue

                # Extract audio segment
                sr = 16000  # WhisperX default sample rate
                start_sample = int(start * sr)
                end_sample = int(end * sr)
                segment_audio = audio[start_sample:end_sample]

                # Save
                segment_name = f"sample_{segments_extracted:04d}.wav"
                segment_path = output_path / segment_name
                sf.write(str(segment_path), segment_audio, sr)

                metadata.append({
                    "file": segment_name,
                    "text": text,
                    "duration": round(duration, 2),
                    "source": audio_file.name,
                    "speaker": speaker,
                })

                segments_extracted += 1
                if segments_extracted >= min_segments * 2:  # Extract extra for filtering
                    break

        except Exception as e:
            logger.error(f"Failed to process {audio_file.name}: {e}")
            continue

    # Save metadata
    metadata_path = output_path / "metadata.json"
    with open(metadata_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, ensure_ascii=False, indent=2)

    # Save transcript list for XTTS
    transcript_path = output_path / "transcripts.txt"
    with open(transcript_path, "w", encoding="utf-8") as f:
        for entry in metadata:
            f.write(f"{entry['file']}|{entry['text']}\n")

    logger.info(f"Extracted {segments_extracted} voice samples to {output_dir}")
    logger.info(f"Total duration: {sum(m['duration'] for m in metadata):.1f}s")


def main():
    parser = argparse.ArgumentParser(description="Extract voice samples from meeting recordings")
    parser.add_argument("--input-dir", required=True, help="Directory with meeting recordings")
    parser.add_argument("--output-dir", required=True, help="Output directory for voice samples")
    parser.add_argument("--target-speaker", default="SPEAKER_00", help="Speaker ID to extract")
    parser.add_argument("--min-duration", type=float, default=3.0, help="Min segment duration (seconds)")
    parser.add_argument("--max-duration", type=float, default=15.0, help="Max segment duration (seconds)")
    parser.add_argument("--min-segments", type=int, default=50, help="Minimum number of segments")

    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)

    extract_samples(
        input_dir=args.input_dir,
        output_dir=args.output_dir,
        target_speaker=args.target_speaker,
        min_duration=args.min_duration,
        max_duration=args.max_duration,
        min_segments=args.min_segments,
    )


if __name__ == "__main__":
    main()
