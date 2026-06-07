"""Python entry point invoked from the Android Kotlin layer.

Kotlin calls :func:`download` with an Instagram URL and a path to a
Netscape cookies file (captured from the in-app login WebView). We
return a JSON-serialisable dict describing the saved files.

Chaquopy bundles our pip deps (gallery-dl) and the standard library,
but not our own ``instadown`` package. So we talk to ``gallery-dl``
directly via its public Python API. This is the same approach our
desktop ``downloader.py`` uses internally, just inlined here to avoid
a package import that Chaquopy can't see.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any

# gallery-dl writes to stderr at INFO level by default, which on
# Android goes to logcat. We quiet it down to WARNING so logcat
# stays readable; the user sees the result via the returned dict.
logging.basicConfig(
    level=logging.WARNING,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)


def _human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024  # type: ignore[assignment]
    return f"{n:.1f} TB"


class _CaptureJob:
    """Subclass of gallery-dl's DownloadJob that records saved files.

    gallery-dl invokes ``handle_url`` for each URL the extractor
    produces. A path of ``"-"`` means the URL was skipped because it
    was already in the archive; otherwise the path is where the file
    was saved.
    """

    def __init__(self, url: str) -> None:
        import gallery_dl.job  # type: ignore[import-not-found]

        self._job = gallery_dl.job.DownloadJob(url)
        self.saved: list[Path] = []
        self.skipped = 0
        self.failed: str | None = None

    def handle_url(self, url, filename, kwdict):  # noqa: ANN001
        pathfmt = self._job.handle_url(url, filename, kwdict)
        if pathfmt is None:
            return None
        s = str(pathfmt)
        if s == "-":
            self.skipped += 1
        else:
            self.saved.append(Path(s))
        return pathfmt

    def run(self) -> None:
        import types

        # Bind our override onto the inner gallery-dl job instance.
        # ``handle_url`` is the only hook we need — gallery-dl calls
        # it once per resolved media URL.
        self._job.handle_url = types.MethodType(  # type: ignore[method-assign]
            _CaptureJob.handle_url, self._job
        )
        try:
            self._job.run()
        except Exception as exc:  # noqa: BLE001
            self.failed = str(exc)


def _configure(cookies_path: str, out_dir: Path) -> None:
    """Push our options into gallery-dl's global config."""
    import gallery_dl.config  # type: ignore[import-not-found]

    gallery_dl.config.set(("extractor",), "base-directory", str(out_dir))
    gallery_dl.config.set(("extractor",), "archive", str(out_dir / "archive.txt"))
    gallery_dl.config.set(("extractor",), "metadata", "none")
    gallery_dl.config.set(("downloader",), "skip", "true")
    gallery_dl.config.set(("output",), "quiet", "true")
    gallery_dl.config.set(("output",), "logfile", str(out_dir / ".instadown.log"))

    filename = "{category}_{owner_username}_{shortcode}_{num:>02}_{filename}.{extension}"
    gallery_dl.config.set(("extractor", "instagram"), "filename", filename)
    gallery_dl.config.set(("extractor", "instagram"), "format", "jpg:best")
    # Reels are out of scope for v0.1 — we only download images.
    gallery_dl.config.set(("extractor", "instagram"), "videos", "false")
    gallery_dl.config.set(("extractor", "instagram"), "cookies", cookies_path)


def download(url: str, cookies_path: str, out_dir: str) -> dict[str, Any]:
    """Download an Instagram post / carousel as image(s). Reels skipped.

    Args:
        url: The Instagram URL pasted by the user or received via
            the share intent.
        cookies_path: Absolute path to a Netscape-format cookies.txt
            file (captured from the in-app login WebView).
        out_dir: Directory to write downloaded files into. Created
            if missing.

    Returns:
        ``{"ok": True, "files": [...]}`` on success, or
        ``{"ok": False, "error": "..."}`` on failure. Each file
        entry is ``{"name", "size", "size_human", "path"}``.
    """

    if not url:
        return {"ok": False, "error": "empty URL"}
    if not cookies_path or not Path(cookies_path).is_file():
        return {
            "ok": False,
            "error": (
                "not signed in. Open the app, tap Settings, then "
                "'Sign in to Instagram' and complete the login once."
            ),
        }

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    (out / "archive.txt").touch(exist_ok=True)

    _configure(cookies_path, out)

    job = _CaptureJob(url)
    job.run()

    if job.failed:
        return {"ok": False, "error": f"download crashed: {job.failed}"}

    if not job.saved:
        # gallery-dl silently bails on login redirects, deleted posts,
        # etc. without raising. Detect by checking if any file appeared.
        return {
            "ok": False,
            "error": (
                "no media downloaded — the post may be private, the link "
                "may be wrong, or your login cookies have expired. "
                "Re-sign in from Settings and try again."
            ),
        }

    return {
        "ok": True,
        "files": [
            {
                "name": f.name,
                "size": f.stat().st_size,
                "size_human": _human(f.stat().st_size),
                "path": str(f),
            }
            for f in job.saved
        ],
    }
