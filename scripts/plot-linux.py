#!/usr/bin/env python3
"""Standalone plots of observed medians; checkpoints are explicitly labeled as restore."""
import argparse
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--suite', choices=['original', 'generic'], default='original')
args = parser.parse_args()
generic = args.suite == 'generic'
report = json.loads((ROOT / ('crema-runner/target/linux-generic/results.json' if generic else 'crema-runner/target/linux/results.json')).read_text())
modes = [
    ('interpreter', 'Crema Interpreter', '#df7846'),
    ('ristretto', 'Crema Ristretto', '#b24e27'),
    ('jdk25_bytecode', 'Java 25', '#607082'),
    ('jdk27_aot_bytecode', 'Java 27 + AOT', '#238895'),
    ('jdk27_aotserial_bytecode', 'Java 27 + AOT / Serial', '#69a1a0'),
    ('jdk28_aot_bytecode', 'Java 28 EA + AOT-Code', '#477ab7'),
    ('crac_restore_bytecode', 'CRaC Restore', '#6d509f'),
]
if generic:
    modes = [item for item in modes if item[0] != 'jdk27_aotserial_bytecode' and item[0] != 'crac_restore_bytecode'] + [
        ('crac_cold_bytecode', 'CRaC-JVM kalt', '#9b91a5'),
        ('crac_restore_bytecode', 'CRaC jobbezogen', '#6d509f'),
        ('crac_generic_bytecode', 'CRaC generisch', '#bf5a99'),
    ]
fig, axes = plt.subplots(2, 2, figsize=(13, 9), layout='constrained')
fig.patch.set_facecolor('#faf9f6')
for column, (job, title) in enumerate([('h2', 'H2-Demo'), ('hotloop', 'Groovy: 100 000 Closure-Aufrufe')]):
    summaries = report['workloads'][job]['summary']
    for row, (metric, unit, divisor) in enumerate([('process_seconds', 'Prozesslaufzeit (ms)', .001), ('peak_rss_bytes', 'Peak-RSS (MiB)', 1048576)]):
        ax = axes[row, column]
        values = [summaries[mode][metric]['median'] / divisor for mode, _, _ in modes]
        upper = [summaries[mode][metric]['max'] / divisor for mode, _, _ in modes]
        errors = [[value - summaries[mode][metric]['min'] / divisor for value, (mode, _, _) in zip(values, modes)],
                  [maximum - value for value, maximum in zip(values, upper)]] if generic and row == 0 else None
        bars = ax.barh(range(len(modes)), values, color=[color for _, _, color in modes], height=.64,
                       xerr=errors, error_kw={'elinewidth': 1, 'capsize': 2, 'ecolor': '#333333'})
        ax.set_yticks(range(len(modes)), [label for _, label, _ in modes])
        ax.invert_yaxis()
        ax.set_xlabel(unit)
        ax.spines[['top', 'right', 'left']].set_visible(False)
        ax.grid(axis='x', alpha=.16)
        ax.set_axisbelow(True)
        ax.set_xlim(0, max(upper if generic else values) * 1.2)
        for bar, value, maximum in zip(bars, values, upper):
            ax.text((maximum if errors else value) + max(values) * .015, bar.get_y() + bar.get_height() / 2,
                    f'{value:.0f}' if row == 0 else f'{value:.1f}', va='center', fontsize=9)
        if row == 0:
            ax.set_title(title, loc='left', fontsize=13, fontweight='bold', pad=14)
fig.suptitle('Linux ARM64 · frischer Container pro Lauf\n2 CPUs · 1 GiB RAM · Heap bis 512 MiB · Median aus 10 Läufen',
             fontsize=14, fontweight='bold')
fig.supxlabel(('Vorkompilierte Jobs. Ein generischer Checkpoint für beide Jobs; Linien: Minimum–Maximum.\n' if generic else
               'Vorkompilierte Jobs. AOT-/Checkpoint-Erzeugung separat; CRaC startet aus vorbereitetem Zustand.\n') +
               'RSS umfasst geteilte Mappings und ist nicht gleich cgroup-Speicher oder Heap.', fontsize=9)
directory = ROOT / 'docs/results'
directory.mkdir(exist_ok=True)
stem = 'linux-generic-time-memory' if generic else 'linux-time-memory'
fig.savefig(directory / (stem + '.png'), dpi=170, facecolor=fig.get_facecolor())
fig.savefig(directory / (stem + '.svg'), facecolor=fig.get_facecolor())
