"""RateLimiter charts: admission against offered load, and the bucket's stored tokens that absorb bursts."""

from .common import CAPACITY_COLOR, new_figure, read_samples, save

TOKENS_COLOR = "#2980b9"


def render(entry, data_dir, out_path):
    samples = read_samples(data_dir / entry["samples_file"])
    elapsed = samples["elapsed_s"]

    fig, ax_rate = new_figure()

    # Left axis: rates. Offered is what the client attempted; admitted is what the limiter let through; the dashed
    # refill rate is the long-run ceiling on admission.
    refill_line, = ax_rate.step(
        elapsed, samples["refill_rps"], where="post", color=CAPACITY_COLOR, linewidth=1.5, linestyle="--",
        label="refill rate"
    )
    offered_line, = ax_rate.plot(
        elapsed, samples["offered_rps"], color="#95a5a6", linewidth=1.0, alpha=0.7, label="offered"
    )
    admitted_line, = ax_rate.plot(elapsed, samples["admitted_rps"], color="#2c3e50", linewidth=2.0, label="admitted")

    # Right axis: tokens. Available tokens are the burst the limiter would admit right now, capped by the capacity.
    ax_tokens = ax_rate.twinx()
    capacity_line, = ax_tokens.step(
        elapsed, samples["capacity"], where="post", color=TOKENS_COLOR, linewidth=1.0, linestyle="--", alpha=0.5,
        label="bucket capacity"
    )
    tokens_line, = ax_tokens.plot(
        elapsed, samples["available_tokens"], color=TOKENS_COLOR, linewidth=1.5, alpha=0.85, label="available tokens"
    )
    ax_tokens.set_ylabel("tokens")
    ax_tokens.set_ylim(0, max(samples["capacity"]) * 1.1)

    handles = [admitted_line, offered_line, refill_line, tokens_line, capacity_line]
    save(fig, ax_rate, entry, handles, out_path)
