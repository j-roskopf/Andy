# Screenshot action

When asked to regenerate Andy's desktop visual baselines, run:

```sh
./gradlew recordRoborazziDesktop
```

This records macOS baselines under `src/screenshotTest/roborazzi/macos/`.
Review and commit only the intentional PNG changes. PR CI verifies screenshots
on macOS only; do not introduce Linux/Windows baseline directories.
