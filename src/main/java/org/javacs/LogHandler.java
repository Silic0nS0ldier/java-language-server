package org.javacs;

import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.javacs.lsp.LogMessageParams;

public class LogHandler extends Handler {
    public interface ClientNotifier {
        void notifyClient(String method, Object params);
    }

    // Client may be disconnected (e.g. in tests)
    // For now errors are caught and the reference dropped
    // TODO Improve this once I'm more familiar with Java
    private Optional<ClientNotifier> clientNotifier;

    public LogHandler(ClientNotifier clientNotifier) {
        this.clientNotifier = Optional.of(clientNotifier);
    }

    @Override
    public void publish(LogRecord record) {
        try {
            clientNotifier.ifPresent((cn) -> {
                var message = new LogMessageParams();
                message.message = record.getMessage();
                cn.notifyClient("window/logMessage", message);
            });
        } catch (Exception ex) {
            clientNotifier = Optional.empty();
        }
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
