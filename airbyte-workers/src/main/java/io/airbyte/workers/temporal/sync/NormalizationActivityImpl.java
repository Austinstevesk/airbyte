/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.sync;

import io.airbyte.commons.functional.CheckedSupplier;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.AirbyteConfigValidator;
import io.airbyte.config.ConfigSchema;
import io.airbyte.config.Configs.WorkerEnvironment;
import io.airbyte.config.NormalizationInput;
import io.airbyte.config.helpers.LogConfigs;
import io.airbyte.config.persistence.split_secrets.SecretsHydrator;
import io.airbyte.scheduler.models.IntegrationLauncherConfig;
import io.airbyte.scheduler.models.JobRunConfig;
import io.airbyte.workers.DefaultNormalizationWorker;
import io.airbyte.workers.Worker;
import io.airbyte.workers.WorkerConfigs;
import io.airbyte.workers.normalization.NormalizationRunnerFactory;
import io.airbyte.workers.process.ProcessFactory;
import io.airbyte.workers.temporal.CancellationHandler;
import io.airbyte.workers.temporal.TemporalAttemptExecution;
import java.nio.file.Path;
import java.util.function.Supplier;

public class NormalizationActivityImpl implements NormalizationActivity {

  private final boolean containerOrchestratorEnabled;
  private final WorkerConfigs workerConfigs;
  private final ProcessFactory jobProcessFactory;
  private final ProcessFactory orchestratorProcessFactory;
  private final SecretsHydrator secretsHydrator;
  private final Path workspaceRoot;
  private final AirbyteConfigValidator validator;
  private final WorkerEnvironment workerEnvironment;
  private final LogConfigs logConfigs;
  private final String databaseUser;
  private final String databasePassword;
  private final String databaseUrl;
  private final String airbyteVersion;

  public NormalizationActivityImpl(final boolean containerOrchestratorEnabled,
                                   final WorkerConfigs workerConfigs,
                                   final ProcessFactory jobProcessFactory,
                                   final ProcessFactory orchestratorProcessFactory,
                                   final SecretsHydrator secretsHydrator,
                                   final Path workspaceRoot,
                                   final WorkerEnvironment workerEnvironment,
                                   final LogConfigs logConfigs,
                                   final String databaseUser,
                                   final String databasePassword,
                                   final String databaseUrl,
                                   final String airbyteVersion) {
    this.containerOrchestratorEnabled = containerOrchestratorEnabled;
    this.workerConfigs = workerConfigs;
    this.jobProcessFactory = jobProcessFactory;
    this.orchestratorProcessFactory = orchestratorProcessFactory;
    this.secretsHydrator = secretsHydrator;
    this.workspaceRoot = workspaceRoot;
    this.validator = new AirbyteConfigValidator();
    this.workerEnvironment = workerEnvironment;
    this.logConfigs = logConfigs;
    this.databaseUser = databaseUser;
    this.databasePassword = databasePassword;
    this.databaseUrl = databaseUrl;
    this.airbyteVersion = airbyteVersion;
  }

  @Override
  public Void normalize(final JobRunConfig jobRunConfig,
                        final IntegrationLauncherConfig destinationLauncherConfig,
                        final NormalizationInput input) {

    final var fullDestinationConfig = secretsHydrator.hydrate(input.getDestinationConfiguration());
    final var fullInput = Jsons.clone(input).withDestinationConfiguration(fullDestinationConfig);

    final Supplier<NormalizationInput> inputSupplier = () -> {
      validator.ensureAsRuntime(ConfigSchema.NORMALIZATION_INPUT, Jsons.jsonNode(fullInput));
      return fullInput;
    };

    final CheckedSupplier<Worker<NormalizationInput, Void>, Exception> workerFactory;

    if (containerOrchestratorEnabled) {
      workerFactory = getContainerLauncherWorkerFactory(workerConfigs, destinationLauncherConfig, jobRunConfig);
    } else {
      workerFactory = getLegacyWorkerFactory(workerConfigs, destinationLauncherConfig, jobRunConfig);
    }

    final TemporalAttemptExecution<NormalizationInput, Void> temporalAttemptExecution = new TemporalAttemptExecution<>(
        workspaceRoot, workerEnvironment, logConfigs,
        jobRunConfig,
        workerFactory,
        inputSupplier,
        new CancellationHandler.TemporalCancellationHandler(), databaseUser, databasePassword, databaseUrl, airbyteVersion);

    return temporalAttemptExecution.get();
  }

  private CheckedSupplier<Worker<NormalizationInput, Void>, Exception> getLegacyWorkerFactory(
                                                                                              final WorkerConfigs workerConfigs,
                                                                                              final IntegrationLauncherConfig destinationLauncherConfig,
                                                                                              final JobRunConfig jobRunConfig) {
    return () -> new DefaultNormalizationWorker(
        jobRunConfig.getJobId(),
        Math.toIntExact(jobRunConfig.getAttemptId()),
        NormalizationRunnerFactory.create(
            workerConfigs,
            destinationLauncherConfig.getDockerImage(),
            jobProcessFactory,
            NormalizationRunnerFactory.NORMALIZATION_VERSION),
        workerEnvironment);
  }

  private CheckedSupplier<Worker<NormalizationInput, Void>, Exception> getContainerLauncherWorkerFactory(
                                                                                                         final WorkerConfigs workerConfigs,
                                                                                                         final IntegrationLauncherConfig destinationLauncherConfig,
                                                                                                         final JobRunConfig jobRunConfig) {
    return () -> new NormalizationLauncherWorker(
        workspaceRoot,
        destinationLauncherConfig,
        jobRunConfig,
        workerConfigs,
        orchestratorProcessFactory,
        airbyteVersion);
  }

}
