"""Thin wrapper around gallery-dl that picks the highest-quality media source.

gallery-dl is the battle-tested extractor for Instagram: it parses the
post page, walks the GraphQL response, and resolves every <img>/<video>
candidate to its largest variant (e.g. 1080-wide JPEG, original MP4 for
reels). We just feed it a URL and forward options.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path

import gallery_dl.config
import gallery_dl.job


log = logging.getLogger("instadown")


@dataclass
class Result:
    url: str
    files: list[Path] = field(default_factory=list)
    skipped: int = 0
    failed: bool = False
    error: str | None = None


def _apply_config(
    output_dir: Path,
    cookies: Path | None,
    archive: Path | None,
    metadata: bool,
    quiet: bool,
) -> None:
    """Push our options into gallery-dl's global config.

    gallery-dl exposes a flat key-value config addressed as
    (section, key). Setting it once per call is enough because the job
    reads from the same config object.
    """

    gallery_dl.config.set(("extractor",), "base-directory", str(output_dir))
    gallery_dl.config.set(("extractor",), "archive", str(archive) if archive else "")
    gallery_dl.config.set(("extractor",), "metadata", "json" if metadata else "none")
    gallery_dl.config.set(("downloader",), "skip", "true")

    filename = "{category}_{owner_username}_{shortcode}_{num:>02}_{filename}.{extension}"
    gallery_dl.config.set(("extractor", "instagram"), "filename", filename)
    gallery_dl.config.set(("extractor", "instagram"), "format", "jpg:best mp4:best")
    gallery_dl.config.set(("extractor", "instagram"), "videos", "true")

    if cookies:
        gallery_dl.config.set(("extractor", "instagram"), "cookies", str(cookies))

    if quiet:
        gallery_dl.config.set(("output",), "quiet", "true")
        gallery_dl.config.set(("downloader",), "quiet", "true")


class _CaptureJob(gallery_dl.job.DownloadJob):
    """DownloadJob that records every file it writes.

    gallery-dl invokes ``handle_url`` for each URL the extractor produces.
    A path of ``"-"`` means the URL was skipped because it was already
    in the archive; otherwise the path is where the file was saved.
    """

    def __init__(self, url: str) -> None:
        super().__init__(url)
        self.saved: list[Path] = []
        self.skipped = 0

    def handle_url(self, url, filename, kwdict):
        pathfmt = super().handle_url(url, filename, kwdict)
        if pathfmt is None:
            return None
        if str(pathfmt) == "-":
            self.skipped += 1
        else:
            self.saved.append(Path(str(pathfmt)))
        return pathfmt


def download(
    url: str,
    output_dir: Path,
    *,
    cookies: Path | None = None,
    archive: Path | None = None,
    metadata: bool = False,
    quiet: bool = False,
) -> Result:
    """Download a single Instagram post / carousel / reel.

    Returns a :class:`Result` describing what was saved. Never raises for
    network or extractor errors — those are captured in ``error``.

    Auth: ``cookies`` is required — a Netscape-format cookies.txt exported
    from a browser that's logged into Instagram (e.g. a throwaway account).
    """

    output_dir.mkdir(parents=True, exist_ok=True)
    if archive:
        archive.parent.mkdir(parents=True, exist_ok=True)
        archive.touch(exist_ok=True)

    _apply_config(output_dir, cookies, archive, metadata, quiet)
    gallery_dl.config.set(("output",), "logfile", f"{output_dir}/.instadown.log")

    if quiet:
        logging.getLogger("gallery_dl").setLevel(logging.ERROR)

    try:
        job = _CaptureJob(url)
        job.run()
    except Exception as exc:  # noqa: BLE001 - gallery-dl raises bare Exception
        log.debug("gallery-dl raised", exc_info=True)
        return Result(
            url=url,
            files=job.saved if "job" in locals() else [],
            failed=True,
            error=str(exc),
        )

    return Result(url=url, files=job.saved, skipped=job.skipped)


def supports(url: str) -> bool:
    """Return True if the URL looks like something gallery-dl can handle."""

    lowered = url.lower()
    return any(
        host in lowered
        for host in ("instagram.com", "instagr.am", "ddinstagram.com")
    )
