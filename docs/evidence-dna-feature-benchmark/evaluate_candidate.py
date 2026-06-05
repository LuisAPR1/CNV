#!/usr/bin/env python3
import csv
from pathlib import Path

csv_path = Path(__file__).with_name("dna-feature-benchmark.csv")
rows = []
with csv_path.open(newline="", encoding="utf-8") as f:
    for row in csv.DictReader(f):
        rows.append(
            {
                k: float(v) if k not in ("label", "category") else v
                for k, v in row.items()
            }
        )


def r2(y, pred):
    mean = sum(y) / len(y)
    ss_tot = sum((v - mean) ** 2 for v in y)
    ss_res = sum((v - pred[i]) ** 2 for i, v in enumerate(y))
    return 1 - ss_res / ss_tot


y_instr = [r["instructions"] for r in rows]
y_alloc = [r["allocatedBytes"] for r in rows]
pred_instr = [
    max(1000.0, 17.0 * r["seedPresenceScan"] + 60.0 * r["sumSeq"]) for r in rows
]
pred_alloc = [
    max(0.0, 52.0 * r["seedPresenceScan"] + 480.0 * r["sumSeq"]) for r in rows
]
print(f"candidate_instruction_r2={r2(y_instr, pred_instr):.4f}")
print(f"candidate_alloc_r2={r2(y_alloc, pred_alloc):.4f}")
print("label,actual_instr,pred_instr,actual_alloc,pred_alloc")
for r, pi, pa in zip(rows, pred_instr, pred_alloc):
    print(
        f"{r['label']},{int(r['instructions'])},{int(pi)},{int(r['allocatedBytes'])},{int(pa)}"
    )
