from micronaut.http.annotation import Controller, Get

@Controller
class MyController:
    @Get(value="/", produces="text/plain")
    def index(self) -> str:
        return "Hello, world 2!"

    @Get(value="/hello")
    def hello(self) -> dict:
        return { "Hello": "Pyronaut!" }
