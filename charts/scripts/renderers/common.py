"""Shared reading, layout, and saving for the per-component chart renderers."""

import csv
import math
import textwrap

import matplotlib

matplotlib.use("Agg")  # headless: render straight to files
import matplotlib.pyplot as plt  # noqa: E402

CAPTION_COLOR = "#555555"
CAPACITY_COLOR = "#7f8c8d"


def read_rows(path):
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


def read_samples(path):
    """Read a samples CSV into {column: [float, ...]} plus `elapsed_s`. Empty cells become NaN, which matplotlib
    leaves as a gap in the line."""
    with path.open(newline="") as handle:
        reader = csv.DictReader(handle)
        columns = {name: [] for name in reader.fieldnames}
        for row in reader:
            for name, value in row.items():
                columns[name].append(float(value) if value else math.nan)
    columns["elapsed_s"] = [ms / 1000.0 for ms in columns["elapsed_ms"]]
    return columns


def read_events(entry, data_dir):
    """The scenario's events as (seconds, kind, detail) tuples, or [] if it recorded none."""
    events_file = entry.get("events_file")
    if not events_file:
        return []
    return [
        (float(row["elapsed_ms"]) / 1000.0, row["kind"], row["detail"]) for row in read_rows(data_dir / events_file)
    ]


def new_figure():
    """A figure whose left axis plots rates; renderers add a right axis with `ax.twinx()` as needed."""
    fig, ax = plt.subplots(figsize=(11, 5))
    ax.set_xlabel("time (s)")
    ax.set_ylabel("rate (requests / second)")
    ax.grid(True, alpha=0.3)
    return fig, ax


def save(fig, ax, entry, handles, out_path, ncol=None):
    """Lay out the legend, title, and wrapped caption above the plot, then write the PNG."""
    ax.set_ylim(bottom=0)
    ncol = ncol or len(handles)
    legend_rows = math.ceil(len(handles) / ncol)
    # Legend above the plot so it never covers the curves.
    ax.legend(handles=handles, loc="lower center", bbox_to_anchor=(0.5, 1.02), ncol=ncol, frameon=False, fontsize=9)

    fig.suptitle(entry["scenario"], fontsize=14, fontweight="bold")
    caption = wrap_to_axes(ax, entry["description"], fontsize=10)
    # Lift the caption clear of the legend, with extra room per wrapped line and per extra legend row.
    pad = 30 + 13 * caption.count("\n") + 18 * (legend_rows - 1)
    ax.set_title(caption, fontsize=10, color=CAPTION_COLOR, pad=pad)
    fig.tight_layout()

    # Without the "Software: Matplotlib <version>" chunk, re-rendering unchanged data yields an identical file.
    fig.savefig(out_path, dpi=130, bbox_inches="tight", metadata={"Software": None})
    plt.close(fig)


def wrap_to_axes(ax, text, fontsize):
    """Wrap text so its rendered width matches the axes width (not the full figure)."""
    fig = ax.figure
    fig.canvas.draw()  # realize a renderer so we can measure text/axes extents
    renderer = fig.canvas.get_renderer()
    axes_width = ax.get_window_extent(renderer=renderer).width
    sample = "the quick brown fox jumps over the lazy dog"
    probe = ax.text(0.0, 0.0, sample, fontsize=fontsize, transform=ax.transAxes)
    char_width = probe.get_window_extent(renderer=renderer).width / len(sample)
    probe.remove()
    max_chars = max(20, int(axes_width / char_width))
    return textwrap.fill(text, width=max_chars)
