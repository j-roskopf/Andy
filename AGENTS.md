# Screenshot action

When asked to regenerate Andy's desktop visual baselines, run:

```sh
./gradlew recordRoborazziDesktop
```

This records only the current operating system's renderer-specific baseline
directory under `src/screenshotTest/roborazzi/`. Review and commit only the
intentional PNG changes.
