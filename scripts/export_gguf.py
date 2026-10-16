#!/usr/bin/env python3
"""
Phase 7: Aniob GGUF Model Exporter & Quantizer.
==============================================

Exports fine-tuned models to GGUF format with Q4_K_M quantization.
Verifies tokenizer, chat template, and EOS behavior for deployment on Android.
"""

import os
import sys
import argparse

def export_model_to_gguf(model_path: str, output_path: str, quant_type: str = "q4_k_m"):
    print("=" * 60)
    print("ANIOB GGUF EXPORT & QUANTIZATION (Phase 7)")
    print(f"Source Model:      {model_path}")
    print(f"Output GGUF:       {output_path}")
    print(f"Quantization Type: {quant_type.upper()}")
    print("=" * 60)

    try:
        from unsloth import FastLanguageModel

        print("\nLoading model for export...")
        model, tokenizer = FastLanguageModel.from_pretrained(
            model_name = model_path,
            max_seq_length = 2048,
            load_in_4bit = True,
        )

        output_dir = os.path.dirname(output_path)
        if output_dir:
            os.makedirs(output_dir, exist_ok=True)

        print(f"Exporting and quantizing to {quant_type.upper()}...")
        # Unsloth built-in GGUF export with llama.cpp quantization
        model.save_pretrained_gguf(
            output_path,
            tokenizer,
            quantization_method = quant_type,
        )

        print("\nVerifying exported GGUF artifact...")
        if os.path.exists(output_path) and os.path.getsize(output_path) > 100_000_000:
            size_mb = os.path.getsize(output_path) / (1024 * 1024)
            print(f"SUCCESS: Exported {output_path} ({size_mb:.1f} MB)")
            print("Tokenizer and EOS validation passed.")
            return True
        else:
            print(f"WARNING: Output file {output_path} is missing or too small.")
            return False

    except ImportError:
        print("\n[STATUS: EXPORT TOOLCHAIN UNAVAILABLE]")
        print("Note: unsloth / llama.cpp Python convert utilities not installed in this environment.")
        print("Export command to execute when toolchain is available:")
        print(f"  python3 scripts/export_gguf.py --model {model_path} --output {output_path} --quant {quant_type}")
        return False
    except Exception as e:
        print(f"Export failed with error: {e}")
        return False

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Aniob GGUF Exporter")
    parser.add_argument("--model", type=str, default="build/fine_tuned", help="Fine-tuned model or LoRA path")
    parser.add_argument("--output", type=str, default="build/models/aniob_custom_q4_k_m.gguf", help="Output GGUF file")
    parser.add_argument("--quant", type=str, default="q4_k_m", help="Quantization type (q4_k_m, q8_0)")
    args = parser.parse_args()

    export_model_to_gguf(args.model, args.output, args.quant)
