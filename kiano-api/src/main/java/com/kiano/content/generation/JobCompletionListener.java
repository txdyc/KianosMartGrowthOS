package com.kiano.content.generation;

/**
 * Called after a job's completion is committed; the pipeline implementation
 * (C2 Task 10) creates downstream jobs and assets here.
 */
public interface JobCompletionListener {

    void onSucceeded(GenerationJob job);
}
