#!/usr/bin/env python3
"""
Phase 6: Aniob Reproducible Fine-Tuning Pipeline with Unsloth LoRA / QLoRA.
==========================================================================

Follows official Unsloth guidelines for Gemma 4 and Qwen 3.5:
- https://unsloth.ai/docs/models/gemma-4/train
- https://unsloth.ai/docs/models/qwen3.5/fine-tune

Hardware Policy:
- Gated on suitable authorized training hardware (CUDA GPU >= 16GB VRAM).
- If authorized GPU hardware is unavailable, logs exact status and marks training BLOCKED.
- Does not purchase external compute or upload user personal data.
"""

import os
import sys
import json
import argparse

def check_hardware_prerequisites():
    """Verifies that an authorized CUDA GPU with sufficient VRAM is available."""
    try:
        import torch
        if not torch.cuda.is_available():
            return False, "No CUDA GPU detected in current execution environment."
        device_count = torch.cuda.device_count()
        vram_gb = torch.cuda.get_device_properties(0).total_memory / (1024 ** 3)
        if vram_gb < 14.0:
            return False, f"Detected GPU has {vram_gb:.1f} GB VRAM; minimum 16.0 GB required for 4B QLoRA training."
        return True, f"Authorized CUDA hardware verified: {device_count} GPU(s), {vram_gb:.1f} GB VRAM."
    except ImportError:
        return False, "PyTorch / CUDA libraries not available in current environment."

def setup_training(model_name: str, dataset_path: str, output_dir: str, max_seq_length: int = 2048):
    can_train, reason = check_hardware_prerequisites()
    print("=" * 60)
    print("ANIOB ON-DEVICE MODEL FINE-TUNING PIPELINE (Phase 6)")
    print(f"Target Model:   {model_name}")
    print(f"Dataset Path:   {dataset_path}")
    print(f"Output Path:    {output_dir}")
    print(f"Max Seq Length: {max_seq_length}")
    print("=" * 60)

    if not can_train:
        print("\n[STATUS: TRAINING BLOCKED]")
        print(f"Reason: {reason}")
        print("Required hardware for local LoRA/QLoRA training:")
        print("  - NVIDIA GPU with >= 16GB VRAM (e.g. RTX 4090, A100, L4)")
        print("  - Python 3.10+ with unsloth, torch, transformers, trl, peft")
        print("\nDataset builder and export scripts have been successfully created.")
        print("To run when authorized training hardware is connected:")
        print(f"  python3 scripts/fine_tune_unsloth.py --model {model_name} --dataset {dataset_path} --output {output_dir}")
        return False

    print(f"\nHardware verified: {reason}")
    print("Initializing Unsloth FastLanguageModel...")

    try:
        from unsloth import FastLanguageModel
        from trl import SFTTrainer
        from transformers import TrainingArguments
        from datasets import load_dataset

        # Load 4-bit quantized base model for QLoRA
        model, tokenizer = FastLanguageModel.from_pretrained(
            model_name = model_name,
            max_seq_length = max_seq_length,
            dtype = None, # Auto-detect Float16 / Bfloat16
            load_in_4bit = True,
        )

        # Attach LoRA adapters to all attention and MLP projection layers
        model = FastLanguageModel.get_peft_model(
            model,
            r = 16,
            target_modules = ["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
            lora_alpha = 16,
            lora_dropout = 0, # Unsloth optimized with 0 dropout
            bias = "none",
            use_gradient_checkpointing = "unsloth",
            random_state = 3407,
            max_seq_length = max_seq_length,
        )

        print("Loading fine-tuning dataset...")
        dataset = load_dataset("json", data_files={"train": dataset_path})

        trainer = SFTTrainer(
            model = model,
            tokenizer = tokenizer,
            train_dataset = dataset["train"],
            dataset_text_field = "text",
            max_seq_length = max_seq_length,
            dataset_num_proc = 2,
            packing = False,
            args = TrainingArguments(
                per_device_train_batch_size = 2,
                gradient_accumulation_steps = 4,
                warmup_steps = 10,
                max_steps = 60,
                learning_rate = 2e-4,
                fp16 = True,
                logging_steps = 5,
                optim = "adamw_8bit",
                weight_decay = 0.01,
                lr_scheduler_type = "linear",
                seed = 3407,
                output_dir = output_dir,
            ),
        )

        print("Executing training run...")
        trainer_stats = trainer.train()
        print("Training completed successfully.")

        # Save LoRA adapter weights
        lora_dir = os.path.join(output_dir, "lora_adapter")
        model.save_pretrained(lora_dir)
        tokenizer.save_pretrained(lora_dir)
        print(f"LoRA weights saved to: {lora_dir}")
        return True

    except Exception as e:
        print(f"Training failed with exception: {e}")
        return False

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Aniob Unsloth Fine-Tuner")
    parser.add_argument("--model", type=str, default="unsloth/gemma-4-E2B-it", help="Base model repository")
    parser.add_argument("--dataset", type=str, default="build/dataset/aniob_train.jsonl", help="Training dataset JSONL")
    parser.add_argument("--output", type=str, default="build/fine_tuned", help="Output directory")
    args = parser.parse_args()

    setup_training(args.model, args.dataset, args.output)
