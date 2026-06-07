"""Local web service for the InstaDown PWA.

Runs a small FastAPI app on your PC that the phone's PWA talks to over
your local WiFi. Two jobs:

  1. Serve the PWA files (HTML, manifest, service worker) so the user
     can install the PWA on their phone's home screen.
  2. Expose ``POST /api/download`` that accepts an Instagram URL,
     downloads the image(s) using the configured cookies, and returns
     the file bytes for the phone to save.

Auth is read from the same env var / CLI arg as the CLI tool. Pass it
via env so it doesn't appear in process listings:

    export INSTADOWN_COOKIES=/path/to/cookies.txt
    instadown serve --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import gallery_dl

from instadown import __version__, downloader, fmt


log = logging.getLogger("instadown.web")


WEB_DIR = Path(__file__).parent.parent.parent / "web"


@dataclass
class Settings:
    cookies: Path | None = None

    @classmethod
    def from_env(cls) -> "Settings":
        cookies_env = os.environ.get("INSTADOWN_COOKIES")
        return cls(
            cookies=Path(cookies_env).expanduser().resolve() if cookies_env else None,
        )


class DownloadRequest(BaseModel):
    url: str = Field(..., description="An Instagram post, carousel, or reel URL")


class DownloadResponse(BaseModel):
    ok: bool
    files: list[dict]
    error: str | None = None


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    app = FastAPI(title="InstaDown", version=__version__)
    app.state.settings = settings

    @app.get("/api/health")
    def health() -> dict:
        cfg = app.state.settings
        return {
            "version": __version__,
            "gallery_dl": gallery_dl.__version__,
            "auth": "cookies" if cfg.cookies else "none",
        }

    @app.post("/api/download", response_model=DownloadResponse)
    def download(req: DownloadRequest) -> DownloadResponse:
        if not downloader.supports(req.url):
            raise HTTPException(400, f"not an Instagram URL: {req.url}")

        cfg = app.state.settings
        if not cfg.cookies:
            raise HTTPException(
                401,
                "server has no auth configured. Set INSTADOWN_COOKIES in the server's env.",
            )

        out_dir = Path("/tmp") / "instadown-pwa" / _safe_name(req.url)
        result = downloader.download(
            req.url,
            out_dir,
            cookies=cfg.cookies,
            quiet=True,
        )

        if result.failed:
            raise HTTPException(502, f"download failed: {result.error}")

        if not result.files:
            raise HTTPException(
                404,
                "no media found (post may be private or require re-auth)",
            )

        return DownloadResponse(
            ok=True,
            files=[
                {
                    "name": f.name,
                    "size": f.stat().st_size,
                    "size_human": fmt.human_size(f.stat().st_size),
                    # served via /api/file/{url_safe}/{name} below
                    "url": f"/api/file/{_safe_name(req.url)}/{f.name}",
                }
                for f in result.files
            ],
        )

    @app.get("/api/file/{job_id}/{name}")
    def serve_file(job_id: str, name: str) -> FileResponse:
        path = Path("/tmp") / "instadown-pwa" / job_id / name
        if not path.is_file() or not str(path.resolve()).startswith(
            str(Path("/tmp/instadown-pwa").resolve())
        ):
            raise HTTPException(404, "file not found")
        return FileResponse(path, filename=name)

    @app.get("/", response_class=HTMLResponse)
    def index() -> FileResponse:
        return FileResponse(WEB_DIR / "index.html")

    if WEB_DIR.is_dir():
        app.mount(
            "/static",
            StaticFiles(directory=WEB_DIR),
            name="static",
        )

    return app


def _safe_name(url: str) -> str:
    """Filename-safe slug for a URL — used as a temp job id."""
    import hashlib

    return hashlib.sha1(url.encode()).hexdigest()[:16]


# Convenience: `uvicorn instadown.web:app` works directly
app = create_app()
