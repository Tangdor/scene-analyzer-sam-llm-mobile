# 📱 Scene Analyzer – SAM & LLM on Mobile

**Prototype for mobile, multimodal scene analysis using lightweight segmentation and language models.**  
This app enables local image/video analysis and scene interpretation using modular, offline-capable components.

---

## 📘 Thesis Context

This repository accompanies the Master's thesis:

> **"Entwicklung einer mobilen Anwendung zur interaktiven visuellen Szenenanalyse mithilfe von Segment-Anything-Modellen und Large Language Modellen"**  
> Technische Hochschule Köln, 2025 – *Jan Kolodziejski*

---

## 🧠 Key Features

- **Fully modular CV → NLP pipeline**
- **Runs fully offline on Android (YOLO-seg + quantized Gemma)**
- Support for:
  - Image & video input
  - Replay from previous JSON scene analyses
- **Three analysis modes**:
  - `NARRATIVE` (auto scene summary)
  - `DETAILED` (interactive Q&A)
  - `COUNTS_ONLY` (count-only, TTS optional)
- **Object tracking** with Kalman + Hungarian matcher
- **Sensor fusion** (gyroscope integration)
- **Hybrid fallback** for server-based CV and LLM (YOLOE, Gemma full precision)

---

## 🏗️ Architecture

```text
[Image/Video] 
→ [YOLO-seg (local) | YOLOE (remote)] 
→ JSON (SceneAnalysisResult) 
→ [PromptBuilder + Limiter] 
→ [Gemma LLM (local or remote)] 
→ Text output (UI + optional TTS)
```

---

## 📂 JSON Format Example

```json
{
  "counts": { "chair": 2, "table": 1 },
  "positions": { "chair": "left", "table": "center" },
  "relations": [["chair", "left of", "table"]],
  "objects": [
    {
      "label": "chair",
      "score": 0.94,
      "box": { "x": 120, "y": 80, "width": 60, "height": 100 }
    }
  ],
  "total": 3
}
```

---

## 🛠 Requirements

- Android Studio (SDK ≥ 34)
- Kotlin ≥ 1.8
- TFLite runtime for YOLOv8-seg
- GGUF-compatible LLM runtime for quantized Gemma 3B
- Optional: remote server w/ REST API for YOLOE or full-precision LLM

---

Of course, here is the requested section in markdown format.

---

## 🚀 Einrichtung & Modelle

**Wichtiger Hinweis:** Die für die App benötigten KI-Modelle sind aus Lizenz- und Größengründen **nicht** in diesem Repository enthalten.

Um die App nutzen zu können, müssen die Modelle manuell heruntergeladen und in das `assets`-Verzeichnis des Android-Projekts (`app/src/main/assets/`) kopiert werden.

1.  **YOLOv8-seg (TFLite):** Das Modell zur Objekterkennung.
2.  **Gemma (GGUF):** Das quantisierte Sprachmodell (z.B. Gemma 3B) für die Textgenerierung.

Ohne diese Dateien im `assets`-Ordner kann die App nicht ausgeführt werden.

## 📜 License

This repository is licensed under the **MIT License**.  
You are free to use, modify, and distribute this software under the terms of the MIT license (see LICENSE file).

---

## 📎 Third-Party Licenses and Terms

### 🔷 YOLO-seg (e.g., YOLOv8-seg)
- License: **AGPL-3.0**
- Source: [https://github.com/ultralytics/ultralytics](https://github.com/ultralytics/ultralytics)
- ⚠️ You **must** open-source your full codebase if you deploy this component in a public/remote service.

### 🔷 YOLOE
- License: **AGPL-3.0**
- Notes: Server-only in this project; same requirements as above apply.

### 🔷 Gemma 3B (Google)
- License: **Gemma Terms of Use** (non-OSI, custom Google license)
- Use restricted by the [Prohibited Use Policy](https://ai.google.dev/gemma/terms)
- Redistribution or modification requires passing through the same terms
- ⚠️ NOT permitted for illegal, exploitative, or harmful content generation

---

## ✅ License Compatibility Summary

| Component  | License      | Use in this Project         |
|------------|--------------|-----------------------------|
| YOLO-seg   | AGPL-3.0     | Local object segmentation   |
| YOLOE      | AGPL-3.0     | Server-based CV fallback    |
| Gemma 3B   | Custom (Google) | Quantized LLM for text output |
| App Code   | MIT          | You can reuse/adapt freely, with attribution |

---

## 📄 LICENSE (MIT)

See `LICENSE` file in the repo root. Attribution required.

---

## 🧾 Acknowledgements

This work builds on the efforts of:
- [Ultralytics](https://github.com/ultralytics) (YOLOv8)
- Google DeepMind / Brain (Gemma LLM)
- The open-source AI community

