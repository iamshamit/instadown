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
import threading
from pathlib import Path
from typing import Any

# gallery-dl uses global config and generator state — only one call at a time.
_gdl_lock = threading.Lock()

logging.basicConfig(
    level=logging.WARNING,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)

_IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif"}


def _human(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024  # type: ignore[assignment]
    return f"{n:.1f} TB"


class _CaptureJob:
    """Thin wrapper around gallery-dl's DownloadJob.

    We no longer monkey-patch handle_url (the signature changed across
    gallery-dl versions and caused a TypeError). Instead we let the job
    run normally and collect results by scanning the output directory
    afterwards.
    """

    def __init__(self, url: str) -> None:
        import gallery_dl.job  # type: ignore[import-not-found]

        self._job = gallery_dl.job.DownloadJob(url)
        self.failed: str | None = None

    def run(self) -> None:
        try:
            self._job.run()
        except Exception as exc:  # noqa: BLE001
            self.failed = str(exc)


def _configure(cookies_path: str, out_dir: Path, log_path: str = "") -> None:
    """Push our options into gallery-dl's global config."""
    import gallery_dl.config  # type: ignore[import-not-found]

    gallery_dl.config.set(("extractor",), "base-directory", str(out_dir))
    gallery_dl.config.set(("extractor",), "archive", None)
    gallery_dl.config.set(("extractor",), "metadata", "none")
    gallery_dl.config.set(("downloader",), "skip", "true")
    gallery_dl.config.set(("downloader",), "retries", 1)
    gallery_dl.config.set(("downloader",), "timeout", 15)
    gallery_dl.config.set(("downloader",), "rate", "500k")
    gallery_dl.config.set(("output",), "quiet", True)
    gallery_dl.config.set(("output",), "logfile", log_path or str(out_dir / ".instadown.log"))

    filename = "{category}_{owner_username}_{shortcode}_{num:>02}_{filename}.{extension}"
    gallery_dl.config.set(("extractor", "instagram"), "filename", filename)
    gallery_dl.config.set(("extractor", "instagram"), "format", "jpg:best")
    # Reels are out of scope for v0.1 — we only download images.
    gallery_dl.config.set(("extractor", "instagram"), "videos", False)
    gallery_dl.config.set(("extractor", "instagram"), "cookies", cookies_path)


def download(url: str, cookies_path: str, out_dir: str, log_path: str = "") -> dict[str, Any]:
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

    import shutil
    out = Path(out_dir)
    if out.exists():
        shutil.rmtree(str(out))
    out.mkdir(parents=True, exist_ok=True)

    with _gdl_lock:
        _configure(cookies_path, out, log_path)

        job = _CaptureJob(url)
        job.run()

    # Collect every image file gallery-dl wrote anywhere under out_dir.
    # gallery-dl may create subdirectories (e.g. out_dir/instagram/user/).
    # Scan regardless of job.failed — internal gallery-dl errors (e.g. archive
    # write failures) must not discard files that were already downloaded.
    saved = sorted(
        [
            f
            for f in out.rglob("*")
            if f.is_file()
            and f.suffix.lower() in _IMAGE_SUFFIXES
            and not f.name.startswith(".")
        ],
        key=lambda f: f.stat().st_mtime,
    )

    if not saved:
        return {
            "ok": False,
            "error": job.failed or (
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
            for f in saved
        ],
    }


def warmup() -> None:
    """Pre-import gallery-dl modules to eliminate first-call import latency."""
    import gallery_dl.extractor  # noqa: F401
    import gallery_dl.config     # noqa: F401


def fetch_metadata(url: str, cookies_path: str) -> dict:
    """Collect image metadata without downloading files.

    Uses gallery-dl's extractor iterator — only fetches post JSON from
    Instagram, never writes image files to disk.

    Returns:
        {"ok": True,  "count": N, "thumbnails": [...], "type": "single"|"carousel"}
        {"ok": False, "error": "..."}
    """
    if not url:
        return {"ok": False, "error": "empty URL"}
    if not cookies_path or not Path(cookies_path).is_file():
        return {"ok": False, "error": "not signed in"}

    try:
        import gallery_dl.extractor as gdl_ext
        import gallery_dl.config as gdl_cfg

        with _gdl_lock:
            gdl_cfg.set(("extractor", "instagram"), "cookies", cookies_path)
            gdl_cfg.set(("extractor", "instagram"), "videos", False)
            gdl_cfg.set(("output",), "quiet", "true")

            extractor = gdl_ext.find(url)
            if extractor is None:
                return {"ok": False, "error": "unsupported URL — only instagram.com links work"}

            extractor.initialize()

            thumbnails: list = []
            for msg in extractor:
                if msg[0] == 3:  # Message.Url
                    _, item_url, keywords = msg
                    thumb = (
                        keywords.get("thumbnail")
                        or keywords.get("display_url")
                        or str(item_url)
                    )
                    thumbnails.append(str(thumb))

        if not thumbnails:
            return {
                "ok": False,
                "error": "no media found — the post may be private, a Reel, or the link is wrong",
            }

        return {
            "ok": True,
            "count": len(thumbnails),
            "thumbnails": thumbnails,
            "type": "carousel" if len(thumbnails) > 1 else "single",
        }
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": str(exc)}
