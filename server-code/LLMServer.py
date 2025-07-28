"""Gemma API Server for visual scene analysis prompts.

This server provides an endpoint to process prompts using a quantized Gemma
language model. Responses are returned in JSON format.
"""

import os
import torch
import torch._dynamo
from flask import Flask, request, jsonify
from transformers import AutoTokenizer, AutoModelForCausalLM

# Disable TorchInductor and related JIT compilation tools for compatibility.
os.environ["TORCHINDUCTOR_DISABLE"] = "1"
os.environ["DISABLE_TORCHINDUCTOR"] = "1"
os.environ["TORCH_COMPILE_DISABLE"] = "1"
torch._dynamo.disable()

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

MODEL_NAME = "google/gemma-3-1b-it"
HF_TOKEN = "YOUR_HF_TOKEN_HERE"  # Replace with environment variable or config
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

# -----------------------------------------------------------------------------
# Model Loading
# -----------------------------------------------------------------------------

tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, token=HF_TOKEN)
model = AutoModelForCausalLM.from_pretrained(
    MODEL_NAME,
    token=HF_TOKEN,
    torch_dtype=torch.float16 if DEVICE == "cuda" else torch.float32,
    device_map="auto"
).to(DEVICE).eval()

# -----------------------------------------------------------------------------
# Prompt Construction
# -----------------------------------------------------------------------------

def format_prompt(user_prompt: str) -> str:
    """Formats the user prompt in a multi-turn template format."""
    return (
        "<start_of_turn>system\n"
        "You are a precise assistant for visual scene analysis. "
        "Follow instructions exactly and respond concisely.\n"
        "<end_of_turn>\n"
        "<start_of_turn>user\n"
        f"{user_prompt}\n"
        "<end_of_turn>\n"
        "<start_of_turn>assistant\n"
    )

# -----------------------------------------------------------------------------
# Assistant Response Extraction
# -----------------------------------------------------------------------------

def extract_assistant_response(full_response: str) -> str:
    """Extracts the assistant's reply from the full model output."""
    if "<start_of_turn>assistant" in full_response:
        parts = full_response.split("<start_of_turn>assistant")
        assistant_part = parts[-1].lstrip("\n ")
        assistant_part = assistant_part.split("<end_of_turn>")[0]
        assistant_part = assistant_part.split("<start_of_turn>")[0]
        return assistant_part.strip()
    return full_response.strip()

# -----------------------------------------------------------------------------
# Flask Application
# -----------------------------------------------------------------------------

app = Flask(__name__)

@app.route("/llm", methods=["POST"])
def llm():
    """Handles POST requests for prompt completion."""
    data = request.get_json()
    prompt = data.get("prompt", "").strip()
    if not prompt:
        return jsonify({"error": "No prompt provided"}), 400

    formatted_prompt = format_prompt(prompt)
    inputs = tokenizer(formatted_prompt, return_tensors="pt").to(DEVICE)

    with torch.no_grad():
        outputs = model.generate(
            **inputs,
            do_sample=True,
            temperature=0.8,
            top_p=0.85,
            repetition_penalty=1.2,
            max_new_tokens=160,
            eos_token_id=tokenizer.convert_tokens_to_ids("<end_of_turn>"),
            pad_token_id=tokenizer.eos_token_id
        )

    input_length = inputs['input_ids'].shape[1]
    new_tokens = outputs[0][input_length:]
    response = tokenizer.decode(new_tokens, skip_special_tokens=True).strip()
    return jsonify({"response": response})

# -----------------------------------------------------------------------------
# Entry Point
# -----------------------------------------------------------------------------

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5001)
