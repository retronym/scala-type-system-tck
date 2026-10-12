# asSeenFrom, formalized: moved

The Lean model of how scalac and the IntelliJ Scala plugin compute a selected member's type now lives in [retronym/jvm-lang-models](https://github.com/retronym/jvm-lang-models), package `packages/intellij` (library `IntelliJ`), with its history. There it builds on the shared `ScalaTypes.AsSeenFrom` instead of a vendored copy, and `IntelliJ.Port` proves this model's `Scalac.asf` is that map. Its README has the theorem table and the map from theorems to the plugin's checks.

This directory pins that package at a commit (`lakefile.toml`) and builds it, so the TCK's CI still checks the model it relies on:

```bash
cd lean && lake update && lake build
```
