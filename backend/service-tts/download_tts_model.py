"""Download Czech TTS model for Piper during Docker build."""
import os
import glob
from piper.download import ensure_voice_exists, get_voices

MODEL = "cs_CZ-jirka-low"
model_dir = "/opt/jervis/data/tts/models"
os.makedirs(model_dir, exist_ok=True)

voices = get_voices(model_dir, update_voices=True)
ensure_voice_exists(MODEL, [model_dir], model_dir, voices)

# Remove any non-Czech models
for f in glob.glob(os.path.join(model_dir, "en_*")):
    os.remove(f)
    print(f"Removed: {f}")

# Verify
model_path = os.path.join(model_dir, f"{MODEL}.onnx")
assert os.path.exists(model_path), f"Model not found: {model_path}"
print(f"Czech TTS model ready: {model_path}")
