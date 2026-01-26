# pycodec2 Android Wheels

Pre-built pycodec2 wheels for each supported CPU architecture. Chaquopy's pip
uses `--find-links` to auto-select the matching platform wheel during build.

## Updating

Download new wheels from
[android-python-wheels](https://github.com/torlando-tech/android-python-wheels/releases)
and replace the files here. Update the version pin in `app/build.gradle.kts`.

## Source

- **arm64-v8a**: [v1.1.0](https://github.com/torlando-tech/android-python-wheels/releases/tag/v1.1.0)
- **x86_64**: [v4.1.1-cp311](https://github.com/torlando-tech/android-python-wheels/releases/tag/v4.1.1-cp311)
