package io.micronaut.python.cli.protocol;

/**
 * Constants for the Pyronaut CLI protocol.
 */
public final class ProtocolConstants {
    private ProtocolConstants() {}

    /** Current protocol version. */
    public static final byte PROTOCOL_VERSION = 1;

    /** Event type codes. */
    public static final byte APP_STARTED = 0x01;
    public static final byte APP_STOPPED = 0x02;
    public static final byte SERVER_URI = 0x03;
    public static final byte ENDPOINT_LIST = 0x04;
    public static final byte TEST_RUN_STARTED = 0x05;
    public static final byte TEST_RESULT = 0x06;
    public static final byte NOTIFICATION = 0x07;
    public static final byte APP_LOG = 0x08;
    public static final byte TEST_RUN_FINISHED = 0x09;
    public static final byte TEST_NODE = 0x0A;
    public static final byte TEST_NODE_STARTED = 0x0B;
    public static final byte TEST_NODE_FINISHED = 0x0C;
    public static final byte TEST_LOG = 0x0D;
    public static final byte APP_START_FAILED = 0x0E;

    /** Log level mappings. */
    public static final byte LOG_TRACE = 0;
    public static final byte LOG_DEBUG = 1;
    public static final byte LOG_INFO = 2;
    public static final byte LOG_WARN = 3;
    public static final byte LOG_ERROR = 4;
    public static final byte LOG_FATAL = 5;

    /** Test status mappings. */
    public static final byte TEST_PENDING = 0;
    public static final byte TEST_RUNNING = 1;
    public static final byte TEST_PASSED = 2;
    public static final byte TEST_FAILED = 3;
    public static final byte TEST_SKIPPED = 4;

    /** Notification levels. */
    public static final byte NOTIFY_INFO = 0;
    public static final byte NOTIFY_SUCCESS = 1;
    public static final byte NOTIFY_WARNING = 2;
    public static final byte NOTIFY_ERROR = 3;
}
