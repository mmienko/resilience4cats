"""AdaptiveRateLimiter charts: the AIMD estimate discovering a backend's capacity, with control-loop events."""

from matplotlib.lines import Line2D

from .common import CAPACITY_COLOR, new_figure, read_events, read_samples, save

# Failure-gradient events, drawn as vertical lines; rate_change events are already visible in the AIMD step line.
GRADIENT_STYLES = {
    "worsening": "#c0392b",
    "recovering": "#e67e22",
    "recovered": "#27ae60",
}


def render(entry, data_dir, out_path):
    samples = read_samples(data_dir / entry["samples_file"])
    elapsed = samples["elapsed_s"]

    fig, ax_rate = new_figure()

    # Left axis: rates. The AIMD estimate is the controlled variable; the dashed line is the backend's true capacity
    # (the bottleneck the AIMD is discovering); admitted is the measured throughput.
    capacity_line, = ax_rate.plot(
        elapsed, samples["backend_capacity_rps"], color=CAPACITY_COLOR, linewidth=1.5, linestyle="--",
        label="backend capacity"
    )
    admitted_line, = ax_rate.plot(
        elapsed, samples["admitted_rps"], color="#16a085", linewidth=1.0, alpha=0.6, label="admitted (measured)"
    )
    rate_line, = ax_rate.step(
        elapsed, samples["aimd_rps"], where="post", color="#2c3e50", linewidth=2.0, label="AIMD rate estimate"
    )

    # Right axis: the limiter's sampled failure ratio in [0, 1].
    ax_ratio = ax_rate.twinx()
    observed_line, = ax_ratio.plot(
        elapsed, samples["observed_failure_ratio"], color="#2980b9", linewidth=1.5, alpha=0.85,
        label="failure ratio (observed)"
    )
    ax_ratio.set_ylabel("failure ratio")
    ax_ratio.set_ylim(-0.02, 1.02)

    kinds = set()
    for time, kind, _ in read_events(entry, data_dir):
        if kind in GRADIENT_STYLES:
            ax_rate.axvline(time, color=GRADIENT_STYLES[kind], linewidth=1.0, linestyle=":", alpha=0.8, zorder=1)
            kinds.add(kind)
    event_handles = [
        Line2D([], [], color=color, linewidth=1.0, linestyle=":", label=kind)
        for kind, color in GRADIENT_STYLES.items()
        if kind in kinds
    ]

    save(fig, ax_rate, entry, [rate_line, admitted_line, capacity_line, observed_line, *event_handles], out_path)
