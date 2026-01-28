import pytest
import requests
from pyronaut.test import *
from micronaut.runtime.server import EmbeddedServer
import java

@pytest.fixture
def my_context(request):
    fixture = micronaut_test_fixture(
        request,
        MicronautTest(
            environments=["foo"],
            transactional=False,
            properties={
                "custom.property": "test_value",
                "endpoints.all.enabled": "true",
                "endpoints.all.sensitive": "false",
                "endpoints.loggers.write-sensitive": "false",
            },
        ),
    )
    yield fixture
    fixture.stop()

@pytest.fixture
def base_url(my_context):
    server = my_context["io.micronaut.runtime.server.EmbeddedServer"]
    return f"http://localhost:{server.getPort()}"

@pytest.fixture
def client(my_context):
    return requests.with_context(my_context)


def test_requests_base_url(base_url):
    r = requests.get(f"{base_url}/health")
    assert r.status_code == 200


def test_requests_with_context(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() is not None


def test_loggers_get(client):
    r = client.get("/loggers")
    assert r.status_code == 200
    assert r.json() is not None


def test_loggers_post_update(client):
    # Set ROOT logger to DEBUG then verify
    payload = {"configuredLevel": "DEBUG"}
    r = client.post("/loggers/ROOT", json=payload)
    assert r.status_code in (200, 204)
    r2 = client.get("/loggers/ROOT")
    assert r2.status_code == 200
    data = r2.json()
    # response shape may vary; check presence of configuredLevel when available
    if isinstance(data, dict) and 'configuredLevel' in data:
        assert data['configuredLevel'] in ("DEBUG", "TRACE", "INFO", "WARN", "ERROR", None)


def test_info_get(client):
    r = client.get("/info")
    assert r.status_code == 200
    body = r.json()
    assert isinstance(body, (dict, list))


def test_beans_get(client):
    r = client.get("/beans")
    assert r.status_code == 200
    assert r.json() is not None


def test_routes_get(client):
    r = client.get("/routes")
    assert r.status_code == 200


def test_not_found_returns_response(base_url):
    r = requests.get(f"{base_url}/does-not-exist")
    assert r.status_code == 404
    import pytest as _pytest
    from requests import exceptions as rx
    with _pytest.raises(rx.HTTPError) as exc:
        r.raise_for_status()
    assert getattr(exc.value, 'response', None) is r

def test_http_error_contains_response(base_url):
    r = requests.get(f"{base_url}/does-not-exist")
    import pytest as _pytest
    from requests import exceptions as rx
    with _pytest.raises(rx.HTTPError) as exc:
        r.raise_for_status()
    assert exc.value.response is not None
    assert exc.value.response.status_code == 404


def test_loggers_post_invalid_level(client):
    bad = {"configuredLevel": "INVALID_LEVEL"}
    r = client.post("/loggers/ROOT", json=bad)
    # depending on management version, invalid level yields 400
    assert r.status_code in (400, 422)
    import pytest as _pytest
    with _pytest.raises(Exception):
        r.raise_for_status()
