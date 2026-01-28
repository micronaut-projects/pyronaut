class RequestException(Exception):
    pass


class HTTPError(RequestException):
    def __init__(self, message: str = "", response=None):
        super().__init__(message)
        self.response = response


class ConnectionError(RequestException):
    pass


class Timeout(RequestException):
    pass


class TooManyRedirects(RequestException):
    pass
