"""Command-line interface for InstaDown."""

from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

import gallery_dl

from instadown import __version__, downloader, fmt
from instadown import web as _web


COOKIE_INSTRUCTIONS = """
InstaDown — authenticating with Instagram
=========================================

Instagram blocks anonymous downloads. You need a cookies file
exported from a logged-in browser session:

1. Open instagram.com in your browser and log in.
2. Install a "cookies.txt" exporter extension:
     • Firefox: "cookies.txt" by Chris B.
     • Chrome:  "Get cookies.txt LOCALLY" by Mosh facilities
3. Click the extension icon while on instagram.com and choose
   "Export" / "Current site".
4. Save the file (e.g. ~/Downloads/instagram-cookies.txt) and run:

     instadown -c ~/Downloads/instagram-cookies.txt <url>

Or set the path via env var:

     export INSTADOWN_COOKIES=~/Downloads/instagram-cookies.txt
     instadown <url>

Notes
-----
• Use a throwaway Instagram account if you create one for InstaDown —
  personal accounts may be flagged for unusual activity.
• Cookies expire in a few weeks; re-export when downloads start
  failing with login errors.
• If Instagram sends a 2FA / suspicious-login challenge, complete it
  in the IG app first — InstaDown can't solve CAPTCHAs.
• Respect Instagram's Terms of Service and copyright law. Only download
  content you have the right to save.
"""


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="instadown",
        description=(
            "Download Instagram posts, carousels and reels at the highest "
            "available quality. Picks the largest JPEG (or MP4 for reels) "
            "that Instagram serves."
        ),
        epilog=(
            "NOTE: Instagram requires authentication for almost all content. "
            "Run 'instadown setup' for instructions on exporting cookies."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("url", nargs="*", help="One or more Instagram post / reel URLs (or 'setup' / 'serve')")
    p.add_argument(
        "-o",
        "--output",
        type=Path,
        default=Path("downloads"),
        help="Directory to save files into (default: ./downloads)",
    )
    p.add_argument(
        "-c",
        "--cookies",
        type=Path,
        help="Path to a Netscape-format cookies.txt from a logged-in Instagram session",
    )
    p.add_argument(
        "-a",
        "--archive",
        type=Path,
        help="File used to skip already-downloaded posts (default: <output>/archive.txt)",
    )
    p.add_argument(
        "-m",
        "--metadata",
        action="store_true",
        help="Write a .json sidecar with post metadata (caption, likes, ...)",
    )
    p.add_argument(
        "-q",
        "--quiet",
        action="store_true",
        help="Suppress per-file progress output",
    )
    p.add_argument(
        "-V",
        "--no-video",
        dest="video",
        action="store_false",
        help="For reels, fetch only the cover image, not the video",
    )
    p.add_argument(
        "--version",
        action="version",
        version=f"instadown {__version__} (gallery-dl {gallery_dl.__version__})",
    )
    p.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Enable debug logging",
    )
    return p


def _cmd_setup(_args: argparse.Namespace) -> int:
    print(COOKIE_INSTRUCTIONS)
    return 0


def _build_serve_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="instadown serve",
        description=(
            "Start the local web server that the InstaDown PWA talks to. "
            "Run this on your PC, then open the printed URL in your "
            "phone's browser to install the PWA."
        ),
    )
    p.add_argument(
        "--host",
        default="0.0.0.0",
        help="Interface to bind (default: 0.0.0.0 — all interfaces, so your phone can reach it)",
    )
    p.add_argument(
        "--port",
        type=int,
        default=8000,
        help="Port to listen on (default: 8000)",
    )
    p.add_argument(
        "--cookies",
        type=Path,
        help="Path to cookies.txt (or set INSTADOWN_COOKIES env var)",
    )
    return p


def _cmd_serve(args: argparse.Namespace) -> int:
    import uvicorn

    settings = _web.Settings(
        cookies=args.cookies.expanduser().resolve() if args.cookies else None,
    )
    if not settings.cookies:
        print(
            "warning: no auth configured. The server will start but /api/download "
            "will return 401. Set --cookies or INSTADOWN_COOKIES.",
            file=sys.stderr,
        )

    app = _web.create_app(settings)
    print(f"InstaDown server listening on http://{args.host}:{args.port}")
    print("From your phone, open the URL printed above (use the LAN IP, not 0.0.0.0).")
    print("Then in your phone browser: ⋮ menu → 'Add to Home screen' to install the PWA.")
    print("After that, sharing a link from the Instagram app will offer 'InstaDown' as a target.")
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")
    return 0


def main(argv: list[str] | None = None) -> int:
    raw = sys.argv[1:] if argv is None else argv

    # `instadown setup` and `instadown serve` have their own arg shapes
    # (different flags, no URL). Detect them before running the main
    # parser so the main parser doesn't reject the subcommand's flags.
    if raw and raw[0] == "setup":
        return _cmd_setup(argparse.Namespace())
    if raw and raw[0] == "serve":
        return _cmd_serve(_build_serve_parser().parse_args(raw[1:]))

    parser = _build_parser()
    args = parser.parse_args(raw)

    if args.url == ["setup"]:
        return _cmd_setup(args)

    if not args.url:
        parser.error(
            "the following arguments are required: url "
            "(or run 'instadown setup' / 'instadown serve' for the PWA server)"
        )

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.WARNING,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )

    output_dir: Path = args.output.expanduser().resolve()
    cookies: Path | None = args.cookies.expanduser().resolve() if args.cookies else None
    archive: Path | None = (
        args.archive.expanduser().resolve()
        if args.archive
        else output_dir / "archive.txt"
    )

    if not cookies:
        print(
            "error: no authentication provided. Pass -c <cookies.txt> "
            "(run 'instadown setup' for details).",
            file=sys.stderr,
        )
        return 2

    if cookies and not cookies.exists():
        print(f"error: cookies file not found: {cookies}", file=sys.stderr)
        print("hint: run 'instadown setup' for help exporting cookies.", file=sys.stderr)
        return 2

    failures = 0
    for url in args.url:
        if not downloader.supports(url):
            print(f"skip  {url}  (not an Instagram URL)", file=sys.stderr)
            failures += 1
            continue

        print(f"-> {url}")
        result = downloader.download(
            url,
            output_dir,
            cookies=cookies,
            archive=archive,
            metadata=args.metadata,
            quiet=args.quiet,
        )

        if result.failed:
            failures += 1
            print(f"   FAIL: {result.error}", file=sys.stderr)
            if "login" in (result.error or "").lower():
                print(
                    "   hint: re-export cookies from a logged-in session "
                    "(run 'instadown setup').",
                    file=sys.stderr,
                )
            continue

        if not result.files and not result.skipped:
            print("   nothing downloaded (post may be private or unavailable)")
            failures += 1
            continue

        for f in result.files:
            size = f.stat().st_size if f.exists() else 0
            print(f"   saved  {f.relative_to(output_dir)}  ({fmt.human_size(size)})")

        if result.skipped:
            print(f"   ({result.skipped} file(s) already in archive, skipped)")

    return 0 if failures == 0 else 1
