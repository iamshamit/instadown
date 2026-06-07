"""Small formatting helpers shared by cli.py and web.py."""

from __future__ import annotations


def human_size(n: int) -> str:
    """Format a byte count as a human-readable string (e.g. '4.3 MB')."""
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024  # type: ignore[assignment]
    return f"{n:.1f} TB"
