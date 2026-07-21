# Pyronaut VS Code

This extension adds Pyronaut-aware Test Explorer support for projects that must run tests through `pyronaut test`.

The first version is distributed manually as a VSIX:

```bash
./gradlew :micronaut-pyronaut-vscode:packageVsix
code --install-extension pyronaut-vscode/build/vsix/pyronaut-vscode-0.1.0.vsix
```

Open a Pyronaut project and use VS Code's Testing view. The extension discovers pytest-style tests from the configured Pyronaut Python test source directory and runs them through `pyronaut test`.
