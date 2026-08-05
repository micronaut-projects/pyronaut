"""
Configuration module for pyronaut logback.
"""

import logging
import logging.config

# Import Java classes using GraalPy java.type
try:
    import java
    LogbackConfigurer = java.type("io.micronaut.pyronaut.logback.LogbackConfigurer")
except (ImportError, KeyError):
    # Fallback for environments where java is unavailable or host symbol lookup is denied.
    LogbackConfigurer = None


def _level_to_python(level):
    if isinstance(level, str) and level.upper() == "TRACE":
        return logging.DEBUG
    return getattr(logging, level) if isinstance(level, str) else level


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
            if self.name in (None, ""):
                # Map Python levels to logback levels
                if level >= logging.CRITICAL:
                    level_str = "ERROR"
                elif level >= logging.ERROR:
                    level_str = "ERROR"
                elif level >= logging.WARNING:
                    level_str = "WARN"
                elif level >= logging.INFO:
                    level_str = "INFO"
                elif level >= logging.DEBUG:
                    level_str = "DEBUG"
                else:
                    level_str = "TRACE"
                LogbackConfigurer.log("", level_str, msg)
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
    # This ensures all loggers created go through SLF4J/logback
    logging.setLoggerClass(LogbackLogger)


    # Configure Python logger levels and propagation to match logback
    # This ensures Python-level filtering is consistent
    root_config = config.get('root', {})
    root_logger = logging.getLogger()
    if root_config:
        level = root_config.get('level')
        if level:
            root_logger.setLevel(_level_to_python(level))

    # 2️⃣ Replace the root logger instance
    level = root_config.get('level')
    if level:
        root = LogbackLogger("root", _level_to_python(level))
    else:
        root = LogbackLogger("root", logging.WARNING)

    logging.root = root
    logging.Logger.root = root

    # 3️⃣ Update logging manager references
    logging.Logger.manager.root = root

    
    loggers = config.get('loggers', {})
    for logger_name, logger_config in loggers.items():
        logger = logging.getLogger(logger_name)
        level = logger_config.get('level')
        if level:
            logger.setLevel(_level_to_python(level))
        
        propagate = logger_config.get('propagate', True)
        logger.propagate = propagate
