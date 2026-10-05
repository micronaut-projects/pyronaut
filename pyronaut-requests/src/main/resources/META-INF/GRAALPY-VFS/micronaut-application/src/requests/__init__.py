# Prefer installed Requests without masking its transport and submodules.
# Keep the Micronaut-backed compatibility API when Requests is not installed.
import pyronaut.requests as _impl


def _load_installed_requests():
    import os
    import sys
    from importlib.machinery import PathFinder

    # The SDK can be imported before pytest initializes host site-packages.
    # Append them so Requests can also import its dependencies, without
    # changing precedence for existing framework/application paths.
    virtualenv = os.environ.get("VIRTUAL_ENV")
    if virtualenv:
        for path in (
            os.path.join(virtualenv, "lib", f"python{sys.version_info.major}.{sys.version_info.minor}", "site-packages"),
            os.path.join(virtualenv, "Lib", "site-packages"),
        ):
            if path not in sys.path:
                sys.path.append(path)

    shim_root = os.path.dirname(os.path.dirname(__file__))
    search_paths = [path for path in sys.path if os.path.abspath(path) != os.path.abspath(shim_root)]
    spec = PathFinder.find_spec(__name__, search_paths)
    if spec is None or spec.loader is None or spec.origin == __file__:
        return False

    module = sys.modules[__name__]
    module.__spec__ = spec
    module.__loader__ = spec.loader
    module.__file__ = spec.origin
    module.__path__ = list(spec.submodule_search_locations or ())
    spec.loader.exec_module(module)
    return True


def _bridge_exception_types():
    # Context-bound responses must remain catchable through either public API,
    # including SDK exception classes imported before standard Requests loads.
    namespace = _impl.exceptions
    base = namespace.RequestException
    if not issubclass(base, exceptions.RequestException):
        base = type("RequestException", (base, exceptions.RequestException), {"__module__": base.__module__})
        namespace.RequestException = base
    for name in ("HTTPError", "ConnectionError", "Timeout", "TooManyRedirects"):
        original = getattr(namespace, name)
        standard = getattr(exceptions, name)
        if not issubclass(original, standard):
            setattr(namespace, name, type(name, (original, standard, base), {"__module__": original.__module__}))


if not _load_installed_requests():
    request = _impl.request
    get = _impl.get
    post = _impl.post
    put = _impl.put
    delete = _impl.delete
    patch = _impl.patch
    head = _impl.head
    options = _impl.options
    Session = _impl.Session
    exceptions = _impl.exceptions
else:
    _bridge_exception_types()

with_context = _impl.with_context
