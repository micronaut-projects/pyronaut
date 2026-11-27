from micronaut.http.annotation import Get


def explode():
    raise ValueError("deliberate server-side error 4242")


@Get(value="/pytest-server-error", produces="text/plain")
def server_error() -> str:
    explode()
    return "unreachable"
