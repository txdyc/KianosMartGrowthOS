package com.kiano.commerce.sync;

import com.kiano.commerce.woo.CommercePortFactory;
import com.kiano.platform.integration.IntegrationStore;
import com.kiano.platform.integration.StoredIntegration;
import com.kiano.platform.queue.QueueProperties;
import com.kiano.platform.queue.TaskQueue;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-driven sync enqueue for every tenant with an active WooCommerce
 * integration. Skipped when the task queue is disabled (tests, dev).
 */
@Component
public class ProductSyncScheduler {

    public static final String DEDUPE_KEY = "woo-product-sync";

    private final IntegrationStore integrationStore;
    private final TaskQueue taskQueue;
    private final QueueProperties queueProperties;

    public ProductSyncScheduler(IntegrationStore integrationStore, TaskQueue taskQueue,
            QueueProperties queueProperties) {
        this.integrationStore = integrationStore;
        this.taskQueue = taskQueue;
        this.queueProperties = queueProperties;
    }

    @Scheduled(cron = "${kiano.commerce.sync-cron}")
    public void scheduledSync() {
        if (!queueProperties.isEnabled()) {
            return;
        }
        for (StoredIntegration integration
                : integrationStore.findAllActive(CommercePortFactory.PROVIDER)) {
            taskQueue.enqueue(integration.tenantId(), ProductSyncTaskHandler.TYPE, Map.of(),
                    DEDUPE_KEY);
        }
    }
}
