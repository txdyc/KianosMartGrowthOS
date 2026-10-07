/**
 * The kiano-worker process: leases generation jobs from the api, runs them
 * on executors and uploads the results. Only depends on the shared worker
 * protocol and the imaging helpers.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"workerprotocol", "imaging"})
package com.kiano.worker;
