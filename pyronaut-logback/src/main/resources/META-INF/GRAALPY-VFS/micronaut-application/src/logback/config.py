"""
Configuration module for pyronaut logback.
"""

import logging
import logging.config
from contextlib import contextmanager

# Import Java classes using GraalPy java.type
try:
    import java
    LogbackConfigurer = java.type("io.micronaut.pyronaut.logback.LogbackConfigurer")
except (ImportError, KeyError):
    # Fallback for environments where java is unavailable or host symbol lookup is denied.
    LogbackConfigurer = None


@contextmanager
def capture_logs(logger=None, level=None):
    """Capture Logback events as Python LogRecords, populated when the scope exits.

    Accept a logging.Logger or logger name; default to the root logger. An optional
    level temporarily changes the Java logger level, not Python's filtering.
    Await event producers before exiting. Do not reconfigure logging in the scope
    or overlap level-changing scopes for the same logger across threads.
    """
    if logger is None:
        logger = logging.getLogger()
    if isinstance(logger, logging.Logger):
        logger = logger.name
    if not isinstance(logger, str):
        raise TypeError("logger must be a logging.Logger or logger name")
    if not LogbackConfigurer:
        raise RuntimeError("capture_logs requires the Java Logback backend")
    try:
        capture_level = LogbackConfigurer.toLogbackLevel(level)
    except java.type("java.lang.IllegalArgumentException") as exc:
        raise ValueError(str(exc)) from exc

    java_logger = LogbackConfigurer.getLogger(logger)
    previous_level = java_logger.getLevel()
    appender = java.type("ch.qos.logback.core.read.ListAppender")()
    appender.setContext(java_logger.getLoggerContext())
    # Java-only callbacks; a synchronized snapshot also tolerates other producers.
    appender.list = java.type("java.util.Collections").synchronizedList(java.type("java.util.ArrayList")())
    records = []
    try:
        if level is not None:
            java_logger.setLevel(capture_level)
        appender.start()
        java_logger.addAppender(appender)
        yield records
    finally:
        java_logger.detachAppender(appender)
        appender.stop()
        if level is not None:
            java_logger.setLevel(previous_level)
        for event in appender.list.toArray():
            level_name = str(event.getLevel())
            level_number = 5 if level_name == "TRACE" else getattr(logging, level_name)
            record = logging.LogRecord(str(event.getLoggerName()), level_number, "", 0,
                                       str(event.getFormattedMessage()), (), None)
            if level_name == "TRACE":
                record.levelname = "TRACE"
            record.created = event.getTimeStamp() / 1000
            record.msecs = event.getTimeStamp() % 1000
            # ListAppender does not eagerly preserve the emitter's thread/caller.
            record.thread = record.threadName = None
            records.append(record)


def _level_to_python(level):
    if isinstance(level, str) and level.upper() == "TRACE":
        return logging.DEBUG
    return getattr(logging, level) if isinstance(level, str) else level


def _logback_level_name(level):
    """Map a numeric Python level to the logback level name understood by LogbackConfigurer.log."""
    if level >= logging.ERROR:
        return "ERROR"
    if level >= logging.WARNING:
        return "WARN"
    if level >= logging.INFO:
        return "INFO"
    if level >= logging.DEBUG:
        return "DEBUG"
    return "TRACE"


class LogbackHandler(logging.Handler):
    """
    Handler installed on the real Python root logger so that records emitted by
    loggers created *before* dictConfig ran (the usual ``log = logging.getLogger(__name__)``
    at module import time) are forwarded to logback as well.
    """

    def __init__(self):
        super().__init__(logging.NOTSET)
        # Only the message (plus any exception text) is forwarded; logback applies the pattern.
        self.setFormatter(logging.Formatter('%(message)s'))

    def emit(self, record):
        if not LogbackConfigurer:
            return
        try:
            LogbackConfigurer.log(record.name or "", _logback_level_name(record.levelno), self.format(record))
        except Exception:
            self.handleError(record)


class LogbackLogger(logging.Logger):
    """
    Custom Logger class that delegates to SLF4J/logback loggers.
    """
    
    def __init__(self, name, level=logging.NOTSET):
        super().__init__(name, level)
        self._slf4j_logger = None
    
    def _get_slf4j_logger(self):
        if self._slf4j_logger is None:
            self._slf4j_logger = LogbackConfigurer.getLogger(self.name or "")
        return self._slf4j_logger
    
    def _log(self, level, msg, args, exc_info=None, extra=None, stack_info=False, stacklevel=1):
        """
        Override _log to delegate to logback.
        """
        if LogbackConfigurer:
            # Format the message
            if args:
                msg = msg % args

            # For root logger, use direct logback call to avoid SLF4J issues
            if self.name in (None, "", "root"):
                LogbackConfigurer.log("", _logback_level_name(level), msg)
            else:
                # For named loggers, use SLF4J
                slf4j_logger = self._get_slf4j_logger()
                if slf4j_logger:
                    # Map Python levels to SLF4J methods
                    if level >= logging.CRITICAL:
                        slf4j_logger.error(msg)
                    elif level >= logging.ERROR:
                        slf4j_logger.error(msg)
                    elif level >= logging.WARNING:
                        slf4j_logger.warn(msg)
                    elif level >= logging.INFO:
                        slf4j_logger.info(msg)
                    elif level >= logging.DEBUG:
                        slf4j_logger.debug(msg)
                    else:
                        slf4j_logger.trace(msg)
        else:
            # Fallback to default behavior if SLF4J is not available
            super()._log(level, msg, args, exc_info, extra, stack_info)


def dictConfig(config):
    """
    Configure logging with a dictionary, similar to logging.config.dictConfig
    but using logback as the backend.
    
    This sets up logback appenders and configures all Python loggers to delegate to SLF4J.
    
    Expected config structure:
    {
        'version': 1,
        'handlers': {
            'console': {
                'class': 'logging.StreamHandler',
                'formatter': 'simple'
            },
            'file': {
                'class': 'logging.FileHandler',
                'filename': 'app.log'
            }
        },
        'root': {
            'level': 'INFO',
            'handlers': ['console']
        },
        'loggers': {
            'my.logger': {
                'level': 'DEBUG',
                'handlers': ['file'],
                'propagate': False
            }
        }
    }
    """
    # Configure logback via Java with the full config
    if not LogbackConfigurer:
        # Native images may not expose the Java Logback bridge. Keep Python
        # logging fully functional instead of silently dropping INFO records.
        logging.config.dictConfig(config)
        return

    LogbackConfigurer.configure(config)
    
    # Set the logger class to use our custom LogbackLogger
    # This ensures all loggers created from now on go through SLF4J/logback directly
    logging.setLoggerClass(LogbackLogger)

    # Keep the real root logger (do not replace it): loggers created before dictConfig
    # ran still have it as their parent, so a forwarding handler installed here routes
    # their records to logback too.  Any handler from a previous dictConfig call is
    # replaced first so records are never forwarded twice.
    root_logger = logging.getLogger()
    for handler in list(root_logger.handlers):
        if isinstance(handler, LogbackHandler):
            root_logger.removeHandler(handler)
    root_logger.addHandler(LogbackHandler())

    # Configure Python logger levels and propagation to match logback
    # This ensures Python-level filtering is consistent
    root_config = config.get('root', {}) or {}
    level = root_config.get('level')
    if level:
        root_logger.setLevel(_level_to_python(level))

    loggers = config.get('loggers', {}) or {}
    for logger_name, logger_config in loggers.items():
        logger = logging.getLogger(logger_name)
        level = logger_config.get('level')
        if level:
            logger.setLevel(_level_to_python(level))
        
        propagate = logger_config.get('propagate', True)
        logger.propagate = propagate

    # Mirror logging.config.dictConfig's ``disable_existing_loggers`` when explicitly
    # requested.  Unlike the stdlib it defaults to False here, since routing records of
    # pre-existing loggers to logback is the whole point of this integration.
    if config.get('disable_existing_loggers', False):
        configured = set(loggers.keys())
        for name, existing in list(logging.Logger.manager.loggerDict.items()):
            if not isinstance(existing, logging.Logger):
                continue
            if name in configured or any(name.startswith(prefix + '.') for prefix in configured):
                continue
            existing.disabled = True
