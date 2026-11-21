"""Check open-bridge observations against full Radau refinement and measure native tick latency.

Run from the repository with: python scripts/compare-baked.py
Each process restores the same 1 RPM checkpoint, then runs at 256 RPM. Timings
include native interval setup, advance_tick and observations. Builds, startup,
snapshot restore and output formatting are outside the timer.

Reference replays use full Radau equations and quarter all six tolerances per
level. Two consecutive changes below 10% of every observation tolerance are
required. The default timing comparison alternates five full and reduced runs.
An unconverged reference, a replay failure or a missed threshold yields a
nonzero exit status after the comparisons. The archived solver is reported
separately as a compatibility measurement.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
from statistics import median
import subprocess
import tempfile
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
CLASSIC = "9e8697466d96b2340ee546e5ba631c07c44cefe1"
EXAMPLES = Path("native/crates/em-core/examples")
FIELDS = (
    ("mean V", .01, .01), ("RMS V", .01, .01), ("peak V", .01, .05),
    ("mean A", 1e-6, .01), ("RMS A", 1e-6, .01), ("peak A", 1e-6, .05),
    ("absorbed J", 1e-9, .01), ("returned J", 1e-9, .01), ("dissipated J", 1e-9, .01),
    ("torque Nm", 4e-6, .01),  # fixture shaft constant is 4 Nm/A
)


def positive(value):
    n = int(value)
    if n < 1:
        raise argparse.ArgumentTypeError("expected a positive integer")
    return n


def build(root, target, env):
    command = [os.environ.get("CARGO", "cargo"), "build", "--release", "--locked", "-p", "em-core",
               "--example", "scale_benchmark", "--target-dir", str(target), "--message-format=json-render-diagnostics"]
    output = subprocess.check_output(command, cwd=root / "native", env=env, text=True)
    artifacts = [json.loads(line) for line in output.splitlines()]
    return next(a["executable"] for a in artifacts if a.get("executable")
                and a["target"]["name"] == "scale_benchmark")


def measure(binary, seed, ticks, env, level=0):
    output = subprocess.check_output([binary, "--bridge-comparison", "measure", seed, str(ticks), str(level)],
                                     env=env, text=True)
    return parse_replay(output, ticks)


def parse_replay(output, ticks):
    times = []
    branches = {}
    for line in output.splitlines():
        kind, tick, data = line.split(" ", 2)
        tick = int(tick)
        if kind == "window":
            if tick != len(times) or int(data) <= 0:
                raise ValueError(f"invalid timing row: {line}")
            times.append(int(data) / 1e6)
        elif kind == "branch":
            branch, values = data.split(" ", 1)
            row = json.loads(values)
            if tick != len(times) - 1 or (tick, branch) in branches or len(row) != len(FIELDS):
                raise ValueError(f"invalid observation row: {line}")
            branches[tick, branch] = row
        else:
            raise ValueError(f"unknown output: {line}")
    if len(times) != ticks or {tick for tick, branch in branches} != set(range(ticks)):
        raise ValueError("incomplete replay")
    return times, branches


def differences(reference, actual):
    for key in sorted(reference.keys() | actual.keys()):
        if key not in reference or key not in actual:
            for field, _, _ in FIELDS:
                yield math.inf, math.inf, *key, field
            continue
        for (field, floor, relative), a, b in zip(FIELDS, reference[key], actual[key]):
            delta = abs(a - b) if math.isfinite(a) and math.isfinite(b) else math.inf
            ratio = delta / (floor + relative * abs(a)) if math.isfinite(delta) else math.inf
            yield ratio, delta, *key, field


def summary(label, rows, limit):
    failed = [r for r in rows if r[0] > limit]
    ratio, delta, tick, branch, field = max(rows)
    print(f"{label} failures={len(failed)} worst_tick={tick} branch={branch} field={field} "
          f"absolute_error={delta:.9g} tolerance_multiple={ratio:.9g}", flush=True)
    return failed


def fallback_fixtures(binary, env):
    success = True
    for fixture in ("loaded", "pretrip"):
        path = ROOT / EXAMPLES / "fixtures" / f"three-phase-bridge-{fixture}.bin"
        rows = []
        for settings in (env, dict(env, EM_BRIDGE_REDUCED="1")):
            try:
                output = subprocess.check_output([binary, "--replay", path, "1"], env=settings, text=True)
                rows.append(json.loads(output))
            except (ValueError, subprocess.CalledProcessError) as exc:
                print(f"{fixture} incomplete: {exc}", flush=True)
                success = False
        if len(rows) != 2:
            continue
        a, b = rows
        if fixture == "pretrip":
            ok = bool(a.get("event_branches") and a.get("owners")) and all(a.get(k) == b.get(k) for k in ("event_branches", "owners"))
            ok = ok and abs(a["event_time"] - b["event_time"]) <= max(1e-10, abs(a["event_time"])*1e-12)
            print(f"{fixture} pass={ok} event={b}", flush=True)
        else:
            # The load joins the two passive trees, making reduction ineligible.
            ok = "steps" in a and all(a[k] == b.get(k) for k in ("steps", "volts", "amps", "work"))
            print(f"{fixture} pass={ok} steps={a.get('steps')}", flush=True)
        success &= ok
    return success


def prepare_replays(temp):
    classic_root = temp / "classic"
    archive = temp / "classic.zip"
    subprocess.run(["git", "archive", "--format=zip", "-o", archive, CLASSIC, "native", "gradle.properties"],
                   cwd=ROOT, check=True)
    with ZipFile(archive) as source:
        source.extractall(classic_root)
    driver = Path("comparison/bridge.rs")
    (classic_root / EXAMPLES / driver.parent).mkdir()
    shutil.copyfile(ROOT / EXAMPLES / driver, classic_root / EXAMPLES / driver)
    # Only replace the classic example's CLI. Both executables use the identical replay driver.
    (classic_root / EXAMPLES / "scale_benchmark.rs").write_text(
        '#[path="comparison/bridge.rs"] mod bridge;\nfn main() {\n'
        '    let mut args = std::env::args_os().skip(1);\n'
        '    assert_eq!(args.next().unwrap(), "--bridge-comparison");\n'
        '    em_core::solver::with_helper(false, || bridge::run(args, |bytes, _| em_core::session::circuit::CircuitSession::from_snapshot(bytes).unwrap(), || 0));\n}\n', encoding="utf-8")
    env = {k: v for k, v in os.environ.items() if not k.startswith("EM_")}
    # Both dev executables must accept the archived fixture's original mod metadata.
    metadata = (classic_root / "gradle.properties").read_text(encoding="utf-8").splitlines()
    env["ELECTROMAGNETISM_MOD_VERSION"] = next(s.split("=", 1)[1] for s in metadata if s.startswith("mod_version="))
    binaries = {name: build(root, temp / f"target-{name}", env)
                for name, root in (("classic", classic_root), ("baked", ROOT))}
    seed = temp / "one-rpm.bin"
    subprocess.run([binaries["classic"], "--bridge-comparison", "prepare", seed], env=env, check=True)
    return binaries, seed, env


def refine_reference(binary, seed, ticks, env):
    reference_env = dict(env, EM_BRIDGE_REFERENCE="1")
    reference = None
    previous = None
    previous_failed = set()
    previous_changes = {}
    stable = False
    unstable = {}
    for level in range(7):
        try:
            _, reference = measure(binary, seed, ticks, reference_env, level)
        except (ValueError, subprocess.CalledProcessError) as exc:
            print(f"reference unconverged at level={level}: {exc}", flush=True)
            break
        if previous is not None:
            rows = list(differences(reference, previous))
            failed = summary(f"reference level={level}", rows, math.nextafter(.1, 0.0))
            keys = {r[2:] for r in failed}
            if level >= 2 and not keys and not previous_failed:
                stable = True
                break
            changes = {row[2:]: row[:2] for row in rows}
            if level == 6:
                unstable = {key: (previous_changes.get(key), changes.get(key))
                            for key in keys | previous_failed}
            previous_failed, previous_changes = keys, changes
        previous = reference
    return reference, stable, unstable


def paired_replays(binary, seed, args, env, reference):
    modes = {"generic": env, "reduced": dict(env, EM_BRIDGE_REDUCED="1")}
    samples = {name: [] for name in modes}
    worst_by_field = {name: {} for name in modes}
    replay_ok = True
    for repetition in range(args.repeats):
        for name in (list(modes) if repetition % 2 == 0 else reversed(modes)):
            try:
                times, rows = measure(binary, seed, args.ticks, modes[name])
            except (ValueError, subprocess.CalledProcessError) as exc:
                print(f"run={repetition+1} {name} incomplete: {exc}", flush=True)
                replay_ok = False
                continue
            samples[name].append(times)
            print(f"run={repetition+1} {name} native_tick_ms={times}", flush=True)
            if reference is not None:
                worst = worst_by_field[name]
                for row in differences(reference, rows):
                    key = row[2:]
                    if key not in worst or row[0] > worst[key][0]:
                        worst[key] = row
    return samples, worst_by_field, replay_ok


def run(args, temp):
    binaries, seed, env = prepare_replays(temp)
    print(f"classic={CLASSIC} baked=working-tree seed_sha256={hashlib.sha256(seed.read_bytes()).hexdigest()}"
          f" ticks={args.ticks} repeats={args.repeats} helper=false", flush=True)
    fallback_ok = fallback_fixtures(binaries["baked"], env)
    stage_codes = [
        subprocess.run([binaries["baked"], "--bridge-stages", seed, step], env=env).returncode
        for step in ("2e-9", "3e-9")
    ]
    stages_ok = all(code == 0 for code in stage_codes)
    reference, stable, unstable = refine_reference(binaries["baked"], seed, args.ticks, env)
    samples, worst_by_field, replay_ok = paired_replays(binaries["baked"], seed, args, env, reference)
    try:
        times, rows = measure(binaries["classic"], seed, args.ticks, env)
        print(f"classic compatibility native_tick_ms={times}; historical following=4.29 ms")
        if reference is not None:
            summary("classic versus finest reference", list(differences(reference, rows)), 1)
    except (ValueError, subprocess.CalledProcessError) as exc:
        print(f"classic compatibility replay incomplete: {exc}")
        replay_ok = False
    if unstable:
        print(f"reference unconverged quantities={len(unstable)}")
        for (tick, branch, field), (fifth, sixth) in sorted(unstable.items()):
            print(f"tick={tick} branch={branch} field={field} (tolerance_multiple, absolute_error) "
                  f"level5={fifth} level6={sixth}")
    accuracy_ok = stable and stages_ok and fallback_ok and replay_ok
    for name, rows in worst_by_field.items():
        if rows:
            comparison = "converged reference" if stable else "finest available level (diagnostic)"
            failed = summary(f"{name} versus {comparison}", list(rows.values()), 1)
            accuracy_ok &= not failed
    performance_ok = replay_ok
    for label, select in (("first", slice(0, 1)), ("following", slice(1, None))):
        groups = {name: [ms for run in runs for ms in run[select]] for name, runs in samples.items()}
        if all(groups.values()):
            for name, values in groups.items():
                print(f"{label} {name} median/max ms={median(values):.3f}/{max(values):.3f}")
            generic, reduced = (median(groups[name]) for name in ("generic", "reduced"))
            performance_ok &= reduced < generic if label == "first" else reduced <= 1.10 * generic
    accuracy = ("passed" if accuracy_ok else "failed") if stable else "paused (reference unconverged)"
    print(f"accuracy={accuracy} timing_thresholds_met={performance_ok} "
          f"eligible={accuracy_ok and performance_ok}")
    return 0 if accuracy_ok and performance_ok else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ticks", type=positive, default=4, help="50 ms windows per measured replay")
    parser.add_argument("--repeats", type=positive, default=5, help="paired runs in alternating order")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="em-baked-comparison-") as directory:
        raise SystemExit(run(args, Path(directory)))
