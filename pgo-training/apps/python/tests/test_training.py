import pytest
import requests

from micronaut.runtime.server import EmbeddedServer
from pyronaut.test import MicronautTest, micronaut_test_fixture

from training.model import Pet


@pytest.fixture(scope="module")
def context(request):
    fixture = micronaut_test_fixture(request, MicronautTest(environments=["test"], transactional=False))
    yield fixture
    fixture.stop()


@pytest.fixture(scope="module")
def base_url(context):
    return f"http://localhost:{context[EmbeddedServer].getPort()}"


def test_store_crud(context):
    store = context["training.PetStore"]
    saved = store.save(Pet(None, "Store Rex", "dog", 3, True))
    assert saved.id > 0
    assert store.find(saved.id).name == "Store Rex"
    assert store.count_species("dog") >= 1
    store.delete(saved.id)
    assert not store.exists(saved.id)


def test_http_crud(base_url):
    created = requests.post(f"{base_url}/pets", json={"name": "Http Tom", "species": "cat", "age": 2})
    assert created.status_code == 201, created.text
    pet_id = created.json()["id"]
    assert requests.get(f"{base_url}/pets/{pet_id}").json()["name"] == "Http Tom"
    assert any(pet["id"] == pet_id for pet in requests.get(f"{base_url}/pets/species/cat").json())
    updated = requests.put(f"{base_url}/pets/{pet_id}", json={"name": "Http Thomas", "species": "cat", "age": 3})
    assert updated.json()["name"] == "Http Thomas"
    assert requests.delete(f"{base_url}/pets/{pet_id}").status_code == 204
    assert requests.get(f"{base_url}/pets/{pet_id}").status_code == 404


def test_validation(base_url):
    response = requests.post(f"{base_url}/pets", json={"name": "", "species": "cat", "age": -1})
    assert response.status_code == 400
    assert len(response.json()["errors"]) == 2


def test_summary(base_url):
    body = '{"notes": "fed the cat, fed the dog", "visits": [{"pet": "a", "when": "2026-01-02T10:00:00", "cost": 12.5}]}'
    response = requests.post(f"{base_url}/pets/summary", data=body, headers={"Content-Type": "text/plain"})
    assert response.status_code == 200, response.text
    assert response.json()["topWords"][0] == "fed"
