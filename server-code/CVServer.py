"""YOLOE Segmentation Server for Object Detection.

This Flask server provides an HTTP endpoint for performing segmentation-based
object detection using a YOLOv8E model. Input images are provided as base64-
encoded strings, and results are returned as JSON.
"""

import base64
import io
import numpy as np
from PIL import Image
from flask import Flask, request, jsonify
from ultralytics import YOLO

# -----------------------------------------------------------------------------
# Flask App Initialization
# -----------------------------------------------------------------------------

app = Flask(__name__)
model = YOLO("YOUR_MODEL_HERE")  # Load YOLOE segmentation model (prompt-free)

# -----------------------------------------------------------------------------
# Segmentation Endpoint
# -----------------------------------------------------------------------------

@app.route("/segment", methods=["POST"])
def segment():
    """Handles POST requests for image segmentation."""
    data = request.get_json()
    if "image" not in data:
        return jsonify({"error": "No image provided"}), 400

    # Decode base64 image and convert to RGB NumPy array
    image_data = base64.b64decode(data["image"])
    image = Image.open(io.BytesIO(image_data)).convert("RGB")
    image_np = np.array(image)

    # Optional label filter (e.g., "fire extinguisher")
    target_label = data.get("target", "").strip().lower()

    results = model(image_np)
    output = {"objects": []}

    for result in results:
        num_detections = len(result.boxes)

        for i in range(num_detections):
            box = result.boxes[i].xyxy[0].tolist()
            cls = int(result.boxes[i].cls[0])
            conf = float(result.boxes[i].conf[0])

            if conf < 0.65:
                continue

            label = model.names[cls]
            if target_label and label.lower() != target_label:
                continue

            obj = {
                "label": label,
                "score": round(conf, 2),
                "box": {
                    "x": round(box[0], 2),
                    "y": round(box[1], 2),
                    "width": round(box[2] - box[0], 2),
                    "height": round(box[3] - box[1], 2)
                }
            }

            # Add polygon mask if available
            if hasattr(result, "masks") and result.masks is not None and i < len(result.masks.xy):
                segment = result.masks.xy[i]
                segment_points = [[round(float(p[0]), 2), round(float(p[1]), 2)] for p in segment]
                obj["mask"] = {
                    "points": segment_points
                }

            output["objects"].append(obj)

    return jsonify(output)

# -----------------------------------------------------------------------------
# Entry Point
# -----------------------------------------------------------------------------

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
