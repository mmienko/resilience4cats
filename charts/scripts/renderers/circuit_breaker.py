"""CircuitBreaker charts: calls served, failed, and rejected, with the breaker's open and half-open periods shaded."""

from matplotlib.patches import Patch

from .common import new_figure, read_events, read_samples, save

# Non-closed states, shaded behind the curves: (color, alpha, legend label).
STATE_SHADES = {
    "open": ("#e74c3c", 0.12, "open (failing fast)"),
    "half_open": ("#f39c12", 0.5, "half-open (probing)"),
}
THRESHOLD_COLOR = "#c0392b"


def render(entry, data_dir, out_path):
    samples = read_samples(data_dir / entry["samples_file"])
    elapsed = samples["elapsed_s"]
    events = read_events(entry, data_dir)

    fig, ax_rate = new_figure()

    # Left axis: rates. Offered is constant (open-loop clients); while the breaker is open nearly all of it is
    # rejected instead of reaching the backend.
    offered_line, = ax_rate.plot(
        elapsed, samples["offered_rps"], color="#95a5a6", linewidth=1.0, alpha=0.7, label="offered"
    )
    succeeded_line, = ax_rate.plot(
        elapsed, samples["succeeded_rps"], color="#16a085", linewidth=1.5, label="succeeded"
    )
    failed_line, = ax_rate.plot(elapsed, samples["failed_rps"], color="#c0392b", linewidth=1.5, label="failed")
    rejected_line, = ax_rate.plot(
        elapsed, samples["rejected_rps"], color="#8e44ad", linewidth=1.5, alpha=0.8, label="rejected"
    )

    # Right axis: the backend's configured error rate, the windowed failure rate the breaker acts on, and the
    # threshold at which it opens.
    ax_ratio = ax_rate.twinx()
    error_line, = ax_ratio.step(
        elapsed, samples["backend_error_rate"], where="post", color="#2c3e50", linewidth=1.0, linestyle="--",
        label="backend error rate"
    )
    observed_line, = ax_ratio.plot(
        elapsed, samples["observed_failure_rate"], color="#2980b9", linewidth=1.5, alpha=0.85,
        label="windowed failure rate"
    )
    threshold_line = ax_ratio.axhline(
        samples["failure_rate_threshold"][0], color=THRESHOLD_COLOR, linewidth=1.0, linestyle=":",
        label="failure rate threshold"
    )
    ax_ratio.set_ylabel("error / failure rate")
    ax_ratio.set_ylim(-0.02, 1.02)

    for start, end, state, reset_timeout in state_spans(events, end=elapsed[-1]):
        color, alpha, _ = STATE_SHADES[state]
        ax_rate.axvspan(start, end, color=color, alpha=alpha, linewidth=0, zorder=0)
        if reset_timeout:
            # Rotated along the band's left edge, so even the shortest open period has room for its label, nudged right
            # to clear the half-open probe that precedes it, and backed so it stays legible over the curves.
            ax_rate.annotate(
                format_millis(reset_timeout), xy=(start, 0.97), xycoords=ax_rate.get_xaxis_transform(),
                xytext=(2, 0), textcoords="offset points", rotation=90, ha="left", va="top", fontsize=8,
                color=THRESHOLD_COLOR, zorder=5,
                bbox={"boxstyle": "square,pad=0.15", "facecolor": "white", "alpha": 0.85, "linewidth": 0},
            )
    state_handles = [Patch(color=color, alpha=alpha, label=label) for color, alpha, label in STATE_SHADES.values()]

    handles = [
        offered_line, succeeded_line, failed_line, rejected_line, *state_handles,
        error_line, observed_line, threshold_line,
    ]
    save(fig, ax_rate, entry, handles, out_path, ncol=5)


def state_spans(events, end):
    """(start, end, state, reset timeout ms) for every non-closed period, from the breaker's transition events. Open
    events carry the reset timeout the breaker waits before probing."""
    spans = []
    state, since, detail = "closed", 0.0, ""
    for time, kind, event_detail in events:
        if state != "closed":
            spans.append((since, time, state, detail))
        state, since, detail = kind, time, event_detail
    if state != "closed":
        spans.append((since, end, state, detail))
    return spans


def format_millis(millis):
    millis = int(millis)
    return f"{millis // 1000}s" if millis % 1000 == 0 else f"{millis}ms"
