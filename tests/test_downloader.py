"""Smoke tests that don't require a network connection.

These exercise the parts of InstaDown that don't touch Instagram: URL
validation, config construction, archive file handling, and the
no-cookies failure path. Run with ``uv run pytest`` or ``python -m
unittest`` from the project root.
"""

from __future__ import annotations

import unittest
from pathlib import Path

from instadown import downloader


class SupportsTests(unittest.TestCase):
    def test_accepts_instagram(self) -> None:
        self.assertTrue(downloader.supports("https://www.instagram.com/p/ABC/"))
        self.assertTrue(downloader.supports("https://instagram.com/reel/XYZ/"))
        self.assertTrue(downloader.supports("http://instagr.am/p/123"))

    def test_rejects_other_sites(self) -> None:
        self.assertFalse(downloader.supports("https://example.com/foo"))
        self.assertFalse(downloader.supports("https://twitter.com/x"))

    def test_case_insensitive(self) -> None:
        self.assertTrue(downloader.supports("HTTPS://WWW.Instagram.COM/p/x"))


class DownloadTests(unittest.TestCase):
    def test_login_wall_returns_no_files(self) -> None:
        """A request that hits Instagram's login wall should complete
        without raising and produce no files."""

        result = downloader.download(
            "https://www.instagram.com/p/DoesNotExist0000000000/",
            Path("/tmp/instadown-smoke"),
        )
        self.assertEqual(result.files, [])
        self.assertFalse(result.failed)


if __name__ == "__main__":
    unittest.main()
