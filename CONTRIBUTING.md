# Contributing to Ferrux

Thanks for helping. Some ground rules:

- **License.** Contributions are Apache-2.0. **Do not copy code from termux-app**
  or other GPL projects. Reading Apache- or MIT-licensed projects such as
  termux-exec and alacritty is fine; credit them where an idea came from.
  New source files start with `SPDX-License-Identifier: Apache-2.0`.
- **Where code goes.** Logic belongs in the Rust crates. Kotlin should only do
  what needs an Android framework API: Views and IME, services, notifications
  and intents. `ferrux-term`, `ferrux-pty` and `ferrux-session` must stay free
  of Android dependencies, because Ferride reuses them.
- **Before you push**, run:
  ```sh
  cargo fmt --all
  cargo clippy --workspace --all-targets -- -D warnings
  cargo test --workspace
  ```
  CI runs these checks too, along with the Android build and emulator screenshots.
- **Commits** use [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat(term): …`, `fix(pty): …`, `ci: …`). Keep pull requests focused.
- **Design changes** that touch architecture (engine, renderer, IPC, package
  layout) should get an issue first. Reference
  [docs/RESEARCH.md](docs/RESEARCH.md) where it applies.
