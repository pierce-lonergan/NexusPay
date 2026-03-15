package io.nexuspay.workflow.config;

import io.nexuspay.workflow.application.PaymentActivities;
import io.nexuspay.workflow.application.PaymentWithRetryWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Temporal worker that executes payment workflows.
 *
 * <p>Creates the gRPC connection to Temporal Server, registers workflow
 * implementations and activity implementations with a single worker on
 * the configured task queue, then starts polling.</p>
 *
 * <p>Enabled only when {@code nexuspay.temporal.enabled=true} (default: false
 * for local dev without Temporal running).</p>
 *
 * @since 0.2.0 (Sprint 2.2)
 */
@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@ConditionalOnProperty(name = "nexuspay.temporal.enabled", havingValue = "true")
public class TemporalWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerConfig.class);

    private WorkflowServiceStubs serviceStubs;
    private WorkerFactory workerFactory;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs(TemporalProperties properties) {
        log.info("Connecting to Temporal server at {}", properties.target());
        serviceStubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(properties.target())
                        .build());
        return serviceStubs;
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs, TemporalProperties properties) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(properties.namespace())
                        .build());
    }

    /**
     * Creates and starts the worker factory with a single worker that handles
     * both workflow tasks and activity tasks on the same task queue.
     *
     * <p>Workflows are registered by <em>class</em> (Temporal creates a new instance
     * per workflow execution). Activities are registered by <em>instance</em> (Spring
     * bean), so they can inject ports and adapters.</p>
     */
    @Bean
    public WorkerFactory workerFactory(WorkflowClient client, TemporalProperties properties,
                                       PaymentActivities paymentActivities) {
        workerFactory = WorkerFactory.newInstance(client,
                WorkerFactoryOptions.newBuilder().build());

        Worker worker = workerFactory.newWorker(
                properties.taskQueue(),
                WorkerOptions.newBuilder()
                        .setMaxConcurrentWorkflowTaskExecutionSize(properties.workerThreads())
                        .build());

        // Workflows — registered by class (Temporal instantiates per execution)
        worker.registerWorkflowImplementationTypes(PaymentWithRetryWorkflowImpl.class);

        // Activities — registered by Spring-managed instance (can inject dependencies)
        worker.registerActivitiesImplementations(paymentActivities);

        // Start polling
        workerFactory.start();

        log.info("Temporal worker started: taskQueue={}, namespace={}, threads={}",
                properties.taskQueue(), properties.namespace(), properties.workerThreads());

        return workerFactory;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Temporal worker...");
        if (workerFactory != null) {
            workerFactory.shutdown();
        }
        if (serviceStubs != null) {
            serviceStubs.shutdown();
        }
        log.info("Temporal worker shutdown complete");
    }
}
