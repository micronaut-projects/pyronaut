"""Python spellings of Micronaut annotations.

DI and validation are compiled into Micronaut metadata. These decorators do
not validate ordinary Python calls; validation runs on Micronaut-managed beans.
"""

__all__ = ["singleton", "not_blank", "valid"]


def singleton(target=None):
    """Declare a Micronaut singleton bean, with ``@singleton`` or ``@singleton()``."""
    return target if target is not None else lambda target: target


def not_blank(**members):
    """Use in ``Annotated[str, not_blank]``; accepts Jakarta constraint members."""
    return lambda target: target


valid = singleton
