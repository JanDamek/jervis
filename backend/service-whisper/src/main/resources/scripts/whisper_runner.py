import json
import sys
from faster_whisper import WhisperModel

if __name__ == "__main__":
    audio_path = sys.argv[1]
    task = sys.argv[2]  # transcribe|translate
    model_name = "base"
    model = WhisperModel(model_name, device="cpu")
    segments, info = model.transcribe(audio_path, language=language, task=task)
    out_segments = []
    all_text = []
    for seg in segments:
        out_segments.append({"start": float(seg.start), "end": float(seg.end), "text": seg.text})
        all_text.append(seg.text)
    print(json.dumps({"text": "".join(all_text), "segments": out_segments}, ensure_ascii=False))
