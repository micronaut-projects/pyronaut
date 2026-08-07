from logback.config import dictConfig

LOGGING = {
    "version": 1,
    "disable_existing_loggers": False,

    "formatters": {
        "standard": {
            "format": "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
        }
    },

    "handlers": {
        "console": {
            "class": "logging.StreamHandler",
            "level": "INFO",
            "formatter": "standard",
            "stream": "ext://sys.stdout"
        },
        "file": {
            "class": "logging.handlers.RotatingFileHandler",
            "level": "INFO",
            "formatter": "standard",
            "filename": "logs/app.log",
            "maxBytes": 10_000_000,
            "backupCount": 5,
            "encoding": "utf-8"
        }
    },

    "root": {
        "level": "INFO",
        "handlers": ["console", "file"]
    },
    "loggers": {
        "io.micronaut.data.query": {
            "level": "DEBUG"
        }
    }
}

dictConfig(LOGGING)
