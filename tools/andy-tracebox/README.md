# Andy tracebox

Andy distributes a pinned Perfetto tracebox bridge for the browser build. The
launcher always adds the exact production origin `https://andy.joetr.com` while
retaining Perfetto's localhost origins. It never uses a wildcard origin.

The launcher and platform binaries are published with each Andy GitHub Release.
Install and start it with:

```sh
adb start-server
curl -fL https://github.com/j-roskopf/Andy/releases/latest/download/andy-tracebox -o andy-tracebox
chmod +x andy-tracebox
./andy-tracebox
```

The launcher selects the matching macOS or Linux binary, verifies it against
`andy-tracebox-SHA256SUMS`, caches it under `~/.cache/andy/tracebox`, and starts:

```sh
tracebox websocket_bridge --http-additional-cors-origins https://andy.joetr.com
```

Set `ANDY_TRACEBOX_BINARY` to a local Perfetto tracebox binary when developing
or testing the launcher without downloading an Andy Release asset.

## Upstream and licensing

The pinned upstream version, commit, artifact URLs, sizes, and SHA-256 digests
live in `manifest.json`. `package_release.py` refuses to package an upstream
artifact when its size or checksum differs. GitHub Release assets include
Perfetto's Apache 2.0 license as `andy-tracebox-LICENSE.txt`.
