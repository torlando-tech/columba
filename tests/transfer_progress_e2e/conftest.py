"""Keep the standalone E2E helpers importable in isolated CI virtual environments."""

from __future__ import annotations

import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))