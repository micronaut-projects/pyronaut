from micronaut.http.annotation import Get


@Get("/")
def read_root() -> dict:
    return {"Hello": "World"}
