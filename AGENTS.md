# Repository Guidelines

## Project Structure & Module Organization

- `src/main/java/com/electromagnetism/`: NeoForge mod code, device implementations, client UI, CEE integration, and simulation coordination.
- `src/main/resources/`: assets, translations, data, and mixin configuration; `src/main/templates/` contains mod metadata templates.
- `src/verification/java/`: executable Java verification classes, generally named `*Checks`.
- `native/`: Rust workspace containing `em-core` (models and solvers), `em-protocol` (contracts and wire encoding), and `em-ffi` (JNI/native bridge). Integration tests live in each crate’s `tests/` directory.
- `docs/`: architecture and audit material. Recheck audit claims against current code.

## Build, Test, and Development Commands

Run builds only when the user requests a build. Otherwise make the requested source or documentation changes.

Use JDK 21 and a Rust toolchain supporting edition 2024. Set `JAVA_HOME` as needed; put Cargo on `PATH` or set `CARGO` to its executable path. Native packaging currently targets Windows x86-64 DLLs.

- `./gradlew.bat build`: build the mod and run checks.
- `./gradlew.bat check`: run Rust contracts and Java verification, including JNI and packaged-library checks.
- `./gradlew.bat runClient` / `runServer`: launch the development client or server.
- `./gradlew.bat runData`: generate resources under `src/generated/resources/`.
- From `native/`, run `cargo test --workspace --locked` for all Rust tests, or `cargo test -p em-core --test core_contracts` for model contracts.

## Coding Style & Naming Conventions

Use four-space indentation. Follow nearby Java conventions: `UpperCamelCase` types, `lowerCamelCase` methods, and lowercase packages. Rust uses `snake_case` functions/modules and `UpperCamelCase` types; use `cargo fmt --all -- --check` to inspect formatting. Avoid unrelated mass reformatting of existing compact code. Include physical units in numerical names and document model parameter ordering.

## Testing Guidelines

Validate player-visible behaviour through the normal interaction in a running game using AutoJS. Report the script, scenario, expected behaviour and observation in the task response or PR. Identify which build was running. If a change received only source review, describe it as such.

Use spark for in-game performance profiling and standalone Rust native runs for native performance measurements. Report workload, configuration, and results separately for each.

Do not add new tests. Delete existing tests that conflict with these requirements; retain nonconflicting tests. Keep build task wiring consistent when removing tests. Use existing scripts and benchmark entry points for validation.

Keep reusable AutoJS scenes and benchmark inputs in the repository. Keep one-off probes, transcripts, profiles and per-run acceptance records out of the source tree. Clean up temporary scripts and measurement outputs when the task ends. Retain a run report only when the user requests it. Preserve game saves and reusable benchmark fixtures during cleanup.

For the component and machine scenes, run `scripts/cee-basic-autojs.ps1` with `-Script cee-devices-scene.js` or `-Script cee-machines-scene.js`. Start with `-Action observe` for an existing scene. For a new scene, choose an empty loaded site, run `setup`, then `connect` on a later tick and observe after each change. The creative plant benchmark runs through `scripts/cee-creative-power-plant.py`. Standalone Rust scale measurements use `native/crates/em-core/examples/scale_benchmark.rs`.

## Commit & Pull Request Guidelines

Use Conventional Commits: `type(scope): description`, for example `fix(solver): reject time-varying AC sources` or `docs: clarify validation requirements`. Use `!` for breaking changes. Keep commits focused. PRs should describe behavior changes, AutoJS validation results, relevant spark/native measurements, and remaining limitations; link related issues and include screenshots for visible UI changes.

## Architecture Boundaries

Java owns the Minecraft world, Create and CEE integration, and device lifecycle. Capture device identities, terminal bindings, topology and mechanical inputs on the server thread. Rust owns the electrical and thermal state, numerical integration, protection decisions and calculated mechanical outputs. Create owns train movement. Java passes train motion to the solver and delivers accepted traction to Create.

Independent electrical domains can solve on workers using captured inputs. The server thread waits for participating domains and delivers accepted results within the same tick. A slow solve can extend the tick. Keep candidate state separate from accepted state. Check the transaction revision and current world bindings before delivery. Apply world effects to their intended owners and explicitly acknowledge the effects actually delivered. Preserve this sequence across retries, topology changes and save/load.

Keep Java and Rust wire layouts synchronized, including field order, units, parameter order and bounds. Mod metadata is the sole compatibility authority. Schema, protocol and snapshot version fields and equivalent internal compatibility counters are prohibited. Transaction revisions identify the state a request applies to and retain that role.
