#!/usr/bin/env python3
"""
Fine-tune XTTS-v2 with extracted voice samples for voice cloning.

Pipeline:
1. Load voice samples + transcripts from extract_voice_samples.py output
2. Prepare training data (wav + text pairs)
3. Fine-tune XTTS-v2 using Coqui trainer
4. Export custom speaker model
5. Deploy: copy model files to TTS service speakers directory

Usage:
    python finetune_xtts.py --samples-dir /path/to/samples --output-dir /path/to/model

Requirements:
    pip install TTS torch torchaudio
    Runs on VD GPU VM (CUDA required for training).
"""

import argparse
import json
import logging
import os
import shutil
from pathlib import Path

logger = logging.getLogger(__name__)


def prepare_training_data(samples_dir: str, output_dir: str) -> tuple[str, str]:
    """Prepare training data in XTTS-compatible format.

    Returns (train_csv_path, eval_csv_path).
    """
    samples_path = Path(samples_dir)
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    # Load metadata from extract_voice_samples.py
    metadata_path = samples_path / "metadata.json"
    if not metadata_path.exists():
        raise FileNotFoundError(f"metadata.json not found in {samples_dir}")

    with open(metadata_path, encoding="utf-8") as f:
        metadata = json.load(f)

    if len(metadata) < 10:
        raise ValueError(f"Need at least 10 samples, found {len(metadata)}")

    logger.info(f"Found {len(metadata)} voice samples")

    # Copy WAV files to output and create CSV
    wavs_dir = output_path / "wavs"
    wavs_dir.mkdir(exist_ok=True)

    entries = []
    for entry in metadata:
        src = samples_path / entry["file"]
        if not src.exists():
            logger.warning(f"Missing WAV: {src}")
            continue

        dst = wavs_dir / entry["file"]
        shutil.copy2(src, dst)

        entries.append({
            "audio_file": str(dst),
            "text": entry["text"],
            "speaker_name": "jan-damek",
        })

    # Split 90/10 train/eval
    split_idx = max(1, int(len(entries) * 0.9))
    train_entries = entries[:split_idx]
    eval_entries = entries[split_idx:]

    # Write CSV files (XTTS format: audio_file|text|speaker_name)
    train_csv = output_path / "train.csv"
    eval_csv = output_path / "eval.csv"

    for csv_path, csv_entries in [(train_csv, train_entries), (eval_csv, eval_entries)]:
        with open(csv_path, "w", encoding="utf-8") as f:
            f.write("audio_file|text|speaker_name\n")
            for e in csv_entries:
                f.write(f"{e['audio_file']}|{e['text']}|{e['speaker_name']}\n")

    logger.info(f"Training data: {len(train_entries)} train, {len(eval_entries)} eval")
    return str(train_csv), str(eval_csv)


def finetune(
    samples_dir: str,
    output_dir: str,
    epochs: int = 10,
    batch_size: int = 2,
    learning_rate: float = 5e-6,
    max_audio_length: int = 255995,  # ~11s at 24kHz
):
    """Fine-tune XTTS-v2 with voice samples."""
    import torch
    from TTS.tts.configs.xtts_config import XttsConfig
    from TTS.tts.models.xtts import XttsArgs, Xtts
    from TTS.config.shared_configs import BaseDatasetConfig
    from trainer import Trainer, TrainerArgs

    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    device = "cuda" if torch.cuda.is_available() else "cpu"
    if device != "cuda":
        logger.warning("CUDA not available — training will be extremely slow!")

    # Prepare training data
    train_csv, eval_csv = prepare_training_data(samples_dir, str(output_path / "data"))

    # Load base XTTS-v2 config
    logger.info("Loading XTTS-v2 base model config...")

    config = XttsConfig()
    config.load_json(os.path.join(
        torch.hub.get_dir(), "tts_models--multilingual--multi-dataset--xtts_v2",
        "config.json"
    ))

    # Training configuration
    config.batch_size = batch_size
    config.eval_batch_size = batch_size
    config.num_loader_workers = 2
    config.num_eval_loader_workers = 1
    config.run_eval = True
    config.test_delay_epochs = -1
    config.epochs = epochs
    config.lr = learning_rate
    config.output_path = str(output_path / "training")

    # Dataset config
    config.datasets = [
        BaseDatasetConfig(
            formatter="",
            dataset_name="jan-damek",
            path=str(output_path / "data"),
            meta_file_train="train.csv",
            meta_file_val="eval.csv",
            language="cs",
        )
    ]

    # Audio config
    config.audio.max_audio_len_in_seconds = max_audio_length / 24000

    # Initialize model from base checkpoint
    logger.info("Loading XTTS-v2 base model for fine-tuning...")
    model = Xtts.init_from_config(config)

    # Load pre-trained weights
    checkpoint_dir = os.path.join(
        torch.hub.get_dir(), "tts_models--multilingual--multi-dataset--xtts_v2"
    )
    model.load_checkpoint(
        config,
        checkpoint_dir=checkpoint_dir,
        eval=False,
    )

    model.to(device)

    # Trainer
    trainer_args = TrainerArgs(
        restore_path=None,
        skip_train_epoch=False,
        start_with_eval=True,
    )

    trainer = Trainer(
        trainer_args,
        config,
        output_path=str(output_path / "training"),
        model=model,
    )

    logger.info(f"Starting fine-tuning: {epochs} epochs, batch_size={batch_size}, lr={learning_rate}")
    trainer.fit()

    # Export best model
    logger.info("Exporting fine-tuned model...")
    export_dir = output_path / "export"
    export_dir.mkdir(exist_ok=True)

    # Copy best checkpoint
    best_model_path = output_path / "training" / "best_model.pth"
    if best_model_path.exists():
        shutil.copy2(best_model_path, export_dir / "model.pth")
        shutil.copy2(
            os.path.join(checkpoint_dir, "config.json"),
            export_dir / "config.json",
        )
        shutil.copy2(
            os.path.join(checkpoint_dir, "vocab.json"),
            export_dir / "vocab.json",
        )
        logger.info(f"Fine-tuned model exported to {export_dir}")
    else:
        logger.warning("best_model.pth not found — check training output")

    # Generate reference speaker embedding
    _export_speaker_embedding(model, samples_dir, export_dir, device)

    logger.info("Fine-tuning complete!")
    logger.info(f"To deploy: copy {export_dir}/* to TTS service data dir")
    logger.info("Then restart TTS service or POST /set_speaker")


def _export_speaker_embedding(model, samples_dir: str, export_dir: Path, device: str):
    """Pre-compute and save speaker conditioning latents for inference."""
    import torch

    samples_path = Path(samples_dir)
    # Use first 3 clean samples for speaker reference
    wavs = sorted(samples_path.glob("*.wav"))[:3]
    if not wavs:
        logger.warning("No WAV files found for speaker embedding")
        return

    logger.info(f"Computing speaker embedding from {len(wavs)} reference files...")

    gpt_cond_latent, speaker_embedding = model.get_conditioning_latents(
        audio_path=[str(w) for w in wavs]
    )

    # Save for fast loading at inference
    torch.save(
        {
            "gpt_cond_latent": gpt_cond_latent,
            "speaker_embedding": speaker_embedding,
        },
        export_dir / "speaker_embedding.pt",
    )
    logger.info(f"Speaker embedding saved to {export_dir / 'speaker_embedding.pt'}")

    # Also copy reference WAVs
    ref_dir = export_dir / "reference"
    ref_dir.mkdir(exist_ok=True)
    for wav in wavs:
        shutil.copy2(wav, ref_dir / wav.name)


def main():
    parser = argparse.ArgumentParser(description="Fine-tune XTTS-v2 with voice samples")
    parser.add_argument("--samples-dir", required=True, help="Directory with voice samples (from extract_voice_samples.py)")
    parser.add_argument("--output-dir", required=True, help="Output directory for fine-tuned model")
    parser.add_argument("--epochs", type=int, default=10, help="Number of training epochs")
    parser.add_argument("--batch-size", type=int, default=2, help="Training batch size")
    parser.add_argument("--learning-rate", type=float, default=5e-6, help="Learning rate")

    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)

    finetune(
        samples_dir=args.samples_dir,
        output_dir=args.output_dir,
        epochs=args.epochs,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
    )


if __name__ == "__main__":
    main()
