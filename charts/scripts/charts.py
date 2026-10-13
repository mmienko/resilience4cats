#!/usr/bin/env python3
"""Render, compare, and promote the behavior charts.

Pipeline:
    sbt "charts/run [component ...]"                         # simulate: CSVs into the staging dir
    python charts/scripts/charts.py render [component ...]   # PNGs into the staging dir
    python charts/scripts/charts.py compare [component ...]  # what changed vs docs/, plus a side-by-side HTML page
    python charts/scripts/charts.py promote [component ...]  # mirror the staged CSVs and PNGs into docs/

The staging dir (charts/target/staging/) mirrors docs/: data under charts/<component>/ and images under
images/<component>/. Commands default to every component with staged data.
"""

import argparse
import filecmp
import html
import os
import shutil
from pathlib import Path

import numpy
from matplotlib import image

from renderers import RENDERERS
from renderers.common import read_rows

REPO_ROOT = Path(__file__).resolve().parents[2]
DOCS_DIR = REPO_ROOT / "docs"
STAGING_DIR = REPO_ROOT / "charts" / "target" / "staging"
COMPARE_PAGE = STAGING_DIR / "compare.html"

STATUSES = ("changed", "added", "removed", "unchanged")


def data_dir(root, component):
    return root / "charts" / component


def image_dir(root, component):
    return root / "images" / component


def manifest_path(root, component):
    return data_dir(root, component) / "manifest.csv"


def relative(path):
    return path.relative_to(REPO_ROOT)


def clear(target, pattern):
    target.mkdir(parents=True, exist_ok=True)
    for stale in target.glob(pattern):
        stale.unlink()


def replace_files(source, target, pattern):
    """Make `target`'s `pattern` files an exact copy of `source`'s, deleting any `source` doesn't have."""
    clear(target, pattern)
    for path in source.glob(pattern):
        shutil.copy2(path, target / path.name)


def require_staged(component):
    if not manifest_path(STAGING_DIR, component).exists():
        raise SystemExit(
            f'{component}: no staged data in {relative(data_dir(STAGING_DIR, component))} (run '
            f'`sbt "charts/run {component}"`, or `charts.py render --from-docs` to re-render the data in docs/)'
        )


def render(components, from_docs):
    for component in components:
        staged_data = data_dir(STAGING_DIR, component)
        if from_docs:
            replace_files(source=data_dir(DOCS_DIR, component), target=staged_data, pattern="*.csv")
        require_staged(component)

        out_dir = image_dir(STAGING_DIR, component)
        clear(out_dir, "*.png")
        for entry in read_rows(manifest_path(STAGING_DIR, component)):
            out_path = out_dir / f"{entry['scenario']}.png"
            RENDERERS[component](entry, staged_data, out_path)
            print(f"wrote {relative(out_path)}")
    print("Next: python charts/scripts/charts.py compare")


def same_csv(left, right):
    return filecmp.cmp(left, right, shallow=False)


def same_png(left, right):
    """Compare decoded pixels, so a PNG that only differs in metadata or compression still counts as unchanged."""
    left_pixels, right_pixels = image.imread(left), image.imread(right)
    return left_pixels.shape == right_pixels.shape and numpy.array_equal(left_pixels, right_pixels)


def diff(current_dir, staged_dir, pattern, same):
    """Classify every file name in either directory as changed, added, removed, or unchanged."""
    current = {path.name: path for path in current_dir.glob(pattern)}
    staged = {path.name: path for path in staged_dir.glob(pattern)}
    changes = {status: [] for status in STATUSES}
    for name in sorted(current.keys() | staged.keys()):
        if name not in current:
            changes["added"].append(name)
        elif name not in staged:
            changes["removed"].append(name)
        elif same(current[name], staged[name]):
            changes["unchanged"].append(name)
        else:
            changes["changed"].append(name)
    return changes


def summarize(changes):
    return ", ".join(f"{len(changes[status])} {status}" for status in STATUSES)


def compare(components):
    sections = []
    for component in components:
        require_staged(component)
        data_changes = diff(data_dir(DOCS_DIR, component), data_dir(STAGING_DIR, component), "*.csv", same_csv)
        image_changes = diff(image_dir(DOCS_DIR, component), image_dir(STAGING_DIR, component), "*.png", same_png)
        print(f"{component}")
        print(f"  images: {summarize(image_changes)}")
        for status in ("changed", "added", "removed"):
            for name in image_changes[status]:
                print(f"    {status:<8} {name}")
        print(f"  data:   {summarize(data_changes)}")
        sections.append(compare_section(component, image_changes, data_changes))

    COMPARE_PAGE.parent.mkdir(parents=True, exist_ok=True)
    COMPARE_PAGE.write_text(compare_page(sections), encoding="utf-8")
    print(f"Side by side: {COMPARE_PAGE.as_uri()}")
    print("Next: python charts/scripts/charts.py promote [component ...]")


def image_src(path):
    """A path relative to the compare page, with the file's mtime appended so a reload shows fresh renders."""
    src = Path(os.path.relpath(path, COMPARE_PAGE.parent)).as_posix()
    return html.escape(f"{src}?v={path.stat().st_mtime_ns}")


def figure(caption, path):
    body = f'<img src="{image_src(path)}" loading="lazy">' if path.exists() else '<div class="none">none</div>'
    return f"<figure><figcaption>{caption}</figcaption>{body}</figure>"


def compare_section(component, image_changes, data_changes):
    def article(name, status):
        current = image_dir(DOCS_DIR, component) / name
        staged = image_dir(STAGING_DIR, component) / name
        return (
            f'<article><h3>{html.escape(Path(name).stem)} <span class="status {status}">{status}</span></h3>'
            f'<div class="pair">{figure("docs/ (current)", current)}{figure("staging (new)", staged)}</div></article>'
        )

    differing = [article(name, status) for status in STATUSES[:3] for name in image_changes[status]]
    unchanged = [article(name, "unchanged") for name in image_changes["unchanged"]]
    data_details = "".join(
        f"<li>{status}: {html.escape(', '.join(data_changes[status]))}</li>"
        for status in STATUSES[:3]
        if data_changes[status]
    )
    parts = [
        f"<section><h2>{html.escape(component)}</h2>",
        f"<p>Images: {summarize(image_changes)}. Data: {summarize(data_changes)}.</p>",
        f"<ul>{data_details}</ul>" if data_details else "",
        *differing,
        f"<details><summary>{len(unchanged)} unchanged images</summary>{''.join(unchanged)}</details>"
        if unchanged else "",
        "</section>",
    ]
    return "".join(parts)


def compare_page(sections):
    return f"""<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>Staged charts vs docs/</title>
<style>
  body {{ font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 2rem; color: #2c3e50; }}
  h2 {{ border-bottom: 1px solid #ddd; padding-bottom: 0.3rem; margin-top: 2.5rem; }}
  .pair {{ display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }}
  figure {{ margin: 0; }}
  figcaption {{ font-size: 0.8rem; color: #777; margin-bottom: 0.3rem; }}
  img {{ width: 100%; border: 1px solid #ddd; }}
  .none {{ border: 1px dashed #ccc; color: #aaa; padding: 3rem; text-align: center; }}
  .status {{ font-size: 0.75rem; font-weight: normal; padding: 0.1rem 0.4rem; border-radius: 0.3rem; }}
  .changed {{ background: #fdebd0; }} .added {{ background: #d5f5e3; }}
  .removed {{ background: #fadbd8; }} .unchanged {{ background: #eee; }}
  summary {{ cursor: pointer; margin: 1rem 0; }}
</style>
</head>
<body>
<h1>Staged charts vs docs/</h1>
{"".join(sections)}
</body>
</html>
"""


def promote(components):
    for component in components:
        require_staged(component)
        staged_images = image_dir(STAGING_DIR, component)
        scenarios = [entry["scenario"] for entry in read_rows(manifest_path(STAGING_DIR, component))]
        missing = [name for name in scenarios if not (staged_images / f"{name}.png").exists()]
        if missing:
            raise SystemExit(f"{component}: no staged image for {', '.join(missing)} (run `charts.py render`)")
        oldest_image = min((staged_images / f"{name}.png").stat().st_mtime for name in scenarios)
        if manifest_path(STAGING_DIR, component).stat().st_mtime > oldest_image:
            raise SystemExit(f"{component}: staged data is newer than its images (run `charts.py render`)")

    for component in components:
        replace_files(source=data_dir(STAGING_DIR, component), target=data_dir(DOCS_DIR, component), pattern="*.csv")
        replace_files(source=image_dir(STAGING_DIR, component), target=image_dir(DOCS_DIR, component), pattern="*.png")
        print(f"promoted {component} -> {relative(data_dir(DOCS_DIR, component))}, "
              f"{relative(image_dir(DOCS_DIR, component))}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name, help_text in [
        ("render", "render staged CSVs to staged PNGs"),
        ("compare", "summarize staged vs docs/ and write a side-by-side HTML page"),
        ("promote", "mirror staged CSVs and PNGs into docs/"),
    ]:
        command = commands.add_parser(name, help=help_text)
        command.add_argument("components", nargs="*", metavar="component",
                             help=f"any of: {', '.join(RENDERERS)} (default: all with staged data)")
        if name == "render":
            command.add_argument("--from-docs", action="store_true",
                                 help="first replace the staged CSVs with the ones in docs/, to re-render them as-is")
    args = parser.parse_args()
    unknown = [name for name in args.components if name not in RENDERERS]
    if unknown:
        parser.error(f"unknown component(s) {', '.join(unknown)}; expected any of: {', '.join(RENDERERS)}")

    from_docs = getattr(args, "from_docs", False)
    source_root = DOCS_DIR if from_docs else STAGING_DIR
    components = args.components or [name for name in RENDERERS if manifest_path(source_root, name).exists()]
    if not components:
        raise SystemExit(f'nothing staged in {relative(STAGING_DIR)} (run `sbt "charts/run"` first)')

    if args.command == "render":
        render(components, from_docs=from_docs)
    elif args.command == "compare":
        compare(components)
    else:
        promote(components)


if __name__ == "__main__":
    main()
