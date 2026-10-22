from helloworld.tasks import CountingTask


def test_python_class_extending_java_class_runs_in_compiled_form():
    # The raw source keeps the Java base, which GraalPy only adapts on the JVM
    # ("Java Class can be extended only in JVM mode" in the native launcher);
    # the compiled module extends the stand-in of the generated Java subclass.
    java_base = CountingTask.__mro__[1]
    assert getattr(java_base, "__micronaut_java_base__", None) == "java.util.TimerTask"


def test_python_class_extending_java_class_calls_java_methods():
    task = CountingTask()
    task.run()
    assert task.count == 1
    assert task.cancel() is False
