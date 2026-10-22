from jakarta.inject import Singleton
from java.util import TimerTask


@Singleton
class CountingTask(TimerTask):
    """A bean extending a Java class: the compiler generates its Java subclass."""

    def __init__(self):
        super().__init__()
        self.count = 0

    def run(self):
        self.count += 1
