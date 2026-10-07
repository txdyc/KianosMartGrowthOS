package com.kiano.commerce.sync;

import com.kiano.commerce.CommerceException;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import org.springframework.stereotype.Component;

/**
 * Queue handler for WOO_PRODUCT_SYNC: runs the read-only sync and maps
 * non-retryable CommerceExceptions to NonRetryableTaskException so the task
 * fails immediately with the original message.
 */
@Component
public class ProductSyncTaskHandler implements TaskHandler {

    public static final String TYPE = "WOO_PRODUCT_SYNC";

    private final ProductSyncService syncService;

    public ProductSyncTaskHandler(ProductSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object handle(TaskContext ctx) {
        try {
            return syncService.syncAll(ctx.tenantId());
        } catch (CommerceException ex) {
            if (!ex.isRetryable()) {
                throw new NonRetryableTaskException(ex.getMessage());
            }
            throw ex;
        }
    }
}
