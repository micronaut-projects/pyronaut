import java


def test_java_exception_message_is_reported():
    raise java.type("java.lang.RuntimeException")("java side detail")
