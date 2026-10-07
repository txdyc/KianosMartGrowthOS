package com.kiano.platform.queue;

/**
 * Unit of background work processed by the PostgreSQL task queue.
 */
public interface TaskHandler {

    /** Queue type this handler processes, e.g. "product.sync". */
    String type();

    /** Executes the task; the return value is serialized into platform_task.result. */
    Object handle(TaskContext ctx) throws Exception;
}
