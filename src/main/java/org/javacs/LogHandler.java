package org.javacs;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.javacs.lsp.LogMessageParams;

public class LogHandler extends Handler {
    public interface ClientNotifier {
        void notifyClient(String method, Object params);
    }

    private final ClientNotifier clientNotifier;

    public LogHandler(ClientNotifier clientNotifier) {
        this.clientNotifier = clientNotifier;
    }

    @Override
    public void publish(LogRecord record) {
        var message = new LogMessageParams();
        message.message = record.getMessage();
        clientNotifier.notifyClient("window/logMessage", message);
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
