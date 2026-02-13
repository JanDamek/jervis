"""Logging utilities with local timezone support."""

import logging
import time


class LocalTimeFormatter(logging.Formatter):
    """Formatter that uses local time instead of UTC."""

    def formatTime(self, record, datefmt=None):
        """Override to use local time."""
        ct = self.converter(record.created)
        if datefmt:
            s = time.strftime(datefmt, ct)
        else:
            s = time.strftime("%Y-%m-%d %H:%M:%S", ct)
            s = f"{s},{int(record.msecs):03d}"
        return s

    converter = time.localtime  # Use local time instead of gmtime
