"""Run through native Pyronaut with and without installed PyPI requests.

The first two tests cover the SDK compatibility API in either environment.
The other tests require standard requests installed in the project venv.
Import at collection time intentionally reproduces third-party consumers.
"""

import requests
from pyronaut import requests as pyronaut_requests


def test_explicit_pyronaut_requests_remains_available():
    assert callable(pyronaut_requests.get)
    assert callable(pyronaut_requests.with_context)


def test_legacy_requests_with_context_remains_available():
    assert callable(requests.with_context)


def test_installed_requests_http_adapter_is_available():
    from requests.adapters import HTTPAdapter

    assert HTTPAdapter.__module__ == "requests.adapters"


def test_installed_requests_session_keeps_transport_and_headers():
    from requests.adapters import HTTPAdapter

    with requests.Session() as session:
        assert isinstance(session.get_adapter("http://localhost/"), HTTPAdapter)
        assert isinstance(session.get_adapter("https://localhost/"), HTTPAdapter)
        session.headers["X-Namespace-Regression"] = "preserved"
        prepared = session.prepare_request(requests.Request("GET", "http://localhost/"))
        assert prepared.headers["X-Namespace-Regression"] == "preserved"


def test_installed_requests_submodules_preserve_exception_identity():
    from requests.exceptions import HTTPError

    assert requests.exceptions.HTTPError is HTTPError
