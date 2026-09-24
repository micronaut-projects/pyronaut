from micronaut.http.annotation import Get


@Get(value="/hello", produces="text/plain")
def hello() -> str:
    return "Hello PGO"
