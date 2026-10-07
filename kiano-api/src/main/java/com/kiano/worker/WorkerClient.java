package com.kiano.worker;

import com.kiano.workerprotocol.CompleteRequest;
import com.kiano.workerprotocol.FailRequest;
import com.kiano.workerprotocol.LeaseRequest;
import com.kiano.workerprotocol.LeaseResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The worker's view of the api's /api/v1/worker endpoints plus the presigned
 * object transfers. lease returns empty on 204; heartbeat/complete/fail
 * return false when the api reports the lease lost (409).
 */
public interface WorkerClient {

    /** Leases one job; empty when the api has nothing to offer. */
    Optional<LeaseResponse> lease(LeaseRequest request);

    /** Keeps the lease alive; false once the lease is lost. */
    boolean heartbeat(long jobId);

    /** Reports success; false once the lease is lost. */
    boolean complete(long jobId, CompleteRequest request);

    /** Reports failure; false once the lease is lost. */
    boolean fail(long jobId, FailRequest request);

    /** Downloads a presigned object to target (its parent directory exists). */
    void download(URI url, Path target) throws IOException;

    /** Uploads a file to a presigned PUT URL with the signed content type. */
    void upload(URI url, Path file, String contentType);
}
