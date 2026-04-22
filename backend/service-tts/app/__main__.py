"""Package entrypoint — `python -m app`.

Lives here (instead of `xtts_server.py:if __name__ == "__main__"`) so that
both this module and any `from app import xtts_server` import see the SAME
module instance. Previously running `python -m app.xtts_server` made
Python register the script as `__main__` while grpc_server imported
`app.xtts_server` separately — two copies of every global (`_tts`,
`_speaker_embedding`, …) meant the first SpeakStream request re-loaded
the XTTS model from scratch (3+ min latency on P40).
"""

from __future__ import annotations

import asyncio
import logging

from app.xtts_server import _main


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
    )
    asyncio.run(_main())
