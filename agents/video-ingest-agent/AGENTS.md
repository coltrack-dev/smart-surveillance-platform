# Instructions for video-ingest-agent

## Preserve educational comments

- Preserve all existing explanatory comments in Rust source files.
- Russian comments are intentional and serve as learning documentation.
- Do not delete, shorten, translate, rewrite, relocate, or consolidate comments
  unless the user explicitly requests it.
- When changing code, update nearby comments if behavior changes.
- Do not replace detailed comments with shorter summaries.

## Minimal code changes

- Make the smallest possible change required by the task.
- Do not reformat unrelated code.
- Preserve the existing layout, line breaks, ordering, and naming where possible.
- Do not perform opportunistic cleanup or broad refactoring.
- Before finishing, inspect the diff and revert unrelated formatting changes.

## Formatting

- Do not run `cargo fmt`, `rustfmt`, IDE auto-formatting, or another
  whole-file formatter unless the user explicitly requests formatting.
- If formatting is necessary for newly changed code, limit it to the changed
  block and preserve all surrounding comments.
- `cargo fmt --check` may be used only as a read-only validation command.
- Do not modify code merely to satisfy formatting differences outside the
  requested change.

## Cargo files

- Do not regenerate `Cargo.lock` unless dependencies were changed.
- Do not reorder or reformat `Cargo.toml` unless required by the task.

## Validation

- Prefer these validation commands:

  ```bash
  cargo check
  cargo test
  cargo clippy --all-targets
  