#!/usr/bin/env python3
"""
Convert research_summary_table.csv into a LaTeX booktabs table body.
Uses Python 3 standard library only. Contains no hardcoded or baked-in data.
"""

import argparse
import csv
import os
import sys

LATEX_ESCAPES = [
    ("&", r"\&"),
    ("%", r"\%"),
    ("_", r"\_"),
    ("#", r"\#"),
]


def escape_latex(text: str) -> str:
    """Escapes special LaTeX characters in plain text."""
    for char, replacement in LATEX_ESCAPES:
        text = text.replace(char, replacement)
    return text


def generate_latex_table(csv_path: str, raw_headers: bool = False) -> str:
    if not os.path.isfile(csv_path):
        raise FileNotFoundError(f"CSV file not found: {csv_path}")

    with open(csv_path, mode="r", encoding="utf-8") as f:
        reader = csv.reader(f)
        rows = [row for row in reader if row and any(cell.strip() for cell in row)]

    if not rows:
        return "% Empty summary table\n"

    header_row = rows[0]
    data_rows = rows[1:]

    # Map CSV headers to clean, publication-ready column titles
    column_title_map = {
        "Ablation_Mode": r"\textbf{Ablation Mode}",
        "Mode_Code": r"\textbf{Mode Code}",
        "Tasks_Evaluated": r"\textbf{Tasks}",
        "Tasks_Succeeded": r"\textbf{Succ.}",
        "Task_Success_Rate_Pct": r"\textbf{Success (\%)}",
        "Mean_End_to_End_Latency_Sec": r"\textbf{Latency (s)}",
        "Std_Latency_Sec": r"\textbf{$\sigma$ (s)}",
        "Mean_Latency_Per_Step_Sec": r"\textbf{Lat./Step (s)}",
        "Mean_LLM_API_Calls_Per_Task": r"\textbf{LLM Calls}",
        "Mean_UI_Steps_Per_Task": r"\textbf{UI Steps}",
        "Mean_Screenshots_Per_Task": r"\textbf{Screenshots}",
        "Mean_Prompt_Tokens_Per_Call": r"\textbf{Tokens/Call}",
        "Speedup_vs_Baseline": r"\textbf{Speedup}",
    }

    if raw_headers:
        formatted_headers = [f"\\textbf{{{escape_latex(h)}}}" for h in header_row]
    else:
        formatted_headers = [column_title_map.get(h, f"\\textbf{{{escape_latex(h)}}}") for h in header_row]

    lines = [
        r"\toprule",
        " & ".join(formatted_headers) + r" \\",
        r"\midrule",
    ]

    for row in data_rows:
        escaped_cells = [escape_latex(cell.strip()) for cell in row]
        # Pad cells if row has fewer columns than header
        if len(escaped_cells) < len(header_row):
            escaped_cells.extend([""] * (len(header_row) - len(escaped_cells)))
        lines.append(" & ".join(escaped_cells) + r" \\")

    lines.append(r"\bottomrule")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(
        description="Convert research_summary_table.csv into LaTeX booktabs format."
    )
    parser.add_argument(
        "csv_path",
        nargs="?",
        default="research_summary_table.csv",
        help="Path to research_summary_table.csv (default: research_summary_table.csv)",
    )
    parser.add_argument(
        "--raw-headers",
        action="store_true",
        help="Use raw sanitized CSV column names as headers instead of paper titles.",
    )
    args = parser.parse_args()

    try:
        latex_output = generate_latex_table(args.csv_path, raw_headers=args.raw_headers)
        print(latex_output)
    except Exception as e:
        sys.stderr.write(f"Error: {e}\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
