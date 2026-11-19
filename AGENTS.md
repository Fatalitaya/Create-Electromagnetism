# Repository Guidelines

## Project Structure & Module Organization

- `src/main/java/com/electromagnetism/`: NeoForge mod code, device implementations, client UI, CEE integration, and simulation coordination.
- `src/main/resources/`: assets, translations, data, and mixin configuration; `src/main/templates/` contains mod metadata templates.
- `src/verification/java/`: executable Java verification classes, generally named `*Checks`.
- `native/`: Rust workspace containing `em-core` (models and solvers), `em-protocol` (contracts and wire encoding), and `em-ffi` (JNI/native bridge). Integration tests live in each crate’s `tests/` directory.
- `docs/`: architecture and audit material. Recheck audit claims against current code.

## Build, Test, and Development Commands

UNLESS SPECIFICALLY MENTIONED TO BUILD, ONLY COMPLETE SOURCE CHANGE.

Use JDK 21 and a Rust toolchain supporting edition 2024. Set `JAVA_HOME` as needed; put Cargo on `PATH` or set `CARGO` to its executable path. Native packaging currently targets Windows x86-64 DLLs.

- `./gradlew.bat build`: build the mod and run checks.
- `./gradlew.bat check`: run Rust contracts and Java verification, including JNI and packaged-library checks.
- `./gradlew.bat runClient` / `runServer`: launch the development client or server.
- `./gradlew.bat runData`: generate resources under `src/generated/resources/`.
- From `native/`, run `cargo test --workspace --locked` for all Rust tests, or `cargo test -p em-core --test core_contracts` for model contracts.

## Coding Style & Naming Conventions

Use four-space indentation. Follow nearby Java conventions: `UpperCamelCase` types, `lowerCamelCase` methods, and lowercase packages. Rust uses `snake_case` functions/modules and `UpperCamelCase` types; use `cargo fmt --all -- --check` to inspect formatting. Avoid unrelated mass reformatting of existing compact code. Include physical units in numerical names and document model parameter ordering.

## Testing Guidelines

Perform functional validation directly in the running game using scripts with AutoJS. Build success or standalone checks do not replace in-game validation. Record the script, scenario, expected behavior, and observed result.

Use spark for in-game performance profiling and standalone Rust native runs for native performance measurements. Report workload, configuration, and results separately for each.

Do not add new tests. Delete existing tests that conflict with these requirements; retain nonconflicting tests. Keep build task wiring consistent when removing tests. Use existing scripts and benchmark entry points for validation.

UNLESS SPECIFICALLY MENTIONED, DO NOT LEAVE ANY TEMPORARY OR LONG-TERM DOCUMENTATION TO TELL ACCEPTANCE STATES.

## Commit & Pull Request Guidelines

Use Conventional Commits: `type(scope): description`, for example `fix(solver): reject time-varying AC sources` or `docs: clarify validation requirements`. Use `!` for breaking changes. Keep commits focused. PRs should describe behavior changes, AutoJS validation results, relevant spark/native measurements, and remaining limitations; link related issues and include screenshots for visible UI changes.

## Architecture Boundaries

Java owns world access and lifecycle; Rust owns numerical state and physics. Preserve transaction revision checks, candidate/accepted-state separation, and explicit world-effect acknowledgement. Keep Java and Rust wire implementations synchronized.

Compatibility must rely exclusively on mod metadata. Do not introduce internal version numbers in any form, including schema versions, protocol versions, snapshot format versions, or equivalent internal compatibility counters.
