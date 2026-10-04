import java


def test_runtime_loaded_code_reads_static_field_of_image_class():
    # crema-provided-access is not a provided artifact, so the native pyronaut-dev
    # interprets it. Its getstatic of ByteArrayBufferFactory.INSTANCE (Micronaut
    # Core, compiled into the image) used to abort the whole VM.
    access = java.type("io.micronaut.pyronaut.fixture.crema.ProvidedStaticFieldAccess")
    assert access.roundTrip("Hello Crema") == "Hello Crema"
