"""AdmissionController charts: probabilistic shedding that holds goodput near a degraded backend's capacity."""

from .common import CAPACITY_COLOR, new_figure, read_samples, save


def render(entry, data_dir, out_path):
    samples = read_samples(data_dir / entry["samples_file"])
    elapsed = samples["elapsed_s"]

    fig, ax_rate = new_figure()

    # Left axis: rates. Client input rate is the load before the gate; client allowed rate is what the controller lets
    # through; backend actual rate is the goodput (allowed minus throttled); capacity (dashed) is the bottleneck.
    capacity_line, = ax_rate.plot(
        elapsed, samples["capacity_rps"], color=CAPACITY_COLOR, linewidth=1.5, linestyle="--", label="backend capacity"
    )
    offered_line, = ax_rate.plot(
        elapsed, samples["offered_rps"], color="#95a5a6", linewidth=1.0, alpha=0.6, label="client input rate"
    )
    admitted_line, = ax_rate.plot(
        elapsed, samples["admitted_rps"], color="#2c3e50", linewidth=2.0, label="client allowed rate"
    )
    accepted_line, = ax_rate.plot(
        elapsed, samples["accepted_rps"], color="#16a085", linewidth=1.0, alpha=0.7, label="backend actual rate"
    )

    # Right axis: the controller's failure ratio and rejection probability in [0, 1]. The failure ratio keeps rising
    # with throttling; the rejection probability stays clamped at zero inside the dead zone and only lifts off once the
    # failure ratio crosses 1 - 1/k. The gap between the two lines is the dead zone.
    ax_prob = ax_rate.twinx()
    failure_line, = ax_prob.plot(
        elapsed, samples["failure_ratio"], color="#8e44ad", linewidth=1.0, alpha=0.6, linestyle=":",
        label="failure ratio"
    )
    rejection_line, = ax_prob.plot(
        elapsed, samples["rejection_probability"], color="#c0392b", linewidth=1.5, alpha=0.85,
        label="rejection probability"
    )
    ax_prob.set_ylabel("failure ratio / rejection probability")
    ax_prob.set_ylim(-0.02, 1.02)

    handles = [admitted_line, accepted_line, offered_line, capacity_line, failure_line, rejection_line]
    save(fig, ax_rate, entry, handles, out_path)
