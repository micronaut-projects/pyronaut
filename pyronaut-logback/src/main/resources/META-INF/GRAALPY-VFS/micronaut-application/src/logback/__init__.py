"""
Logback - Python logging configuration for logback.

This module provides a Python-friendly API to configure logging
that delegates to logback instead of Python's built-in logging.

Usage:
    from logback.config import dictConfig
"""

from .config import capture_logs, dictConfig

__all__ = ['capture_logs', 'dictConfig']
