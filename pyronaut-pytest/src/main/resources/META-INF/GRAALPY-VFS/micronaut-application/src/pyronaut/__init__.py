"""Pyronaut Python package.

Keep the pytest integration lazy.  Importing modules such as
``pyronaut.build`` must not import ``pyronaut.test`` (which imports pytest),
because JUnit-only modules use the build API during compilation and execution.
The ``pyronaut.test`` submodule remains available through a normal explicit
import.
"""

__all__ = []
