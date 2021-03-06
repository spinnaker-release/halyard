/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.google;

import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.*;
import com.netflix.frigga.Names;
import com.netflix.spinnaker.halyard.config.model.v1.node.DeploymentConfiguration;
import com.netflix.spinnaker.halyard.config.model.v1.node.Provider;
import com.netflix.spinnaker.halyard.config.model.v1.providers.google.GoogleAccount;
import com.netflix.spinnaker.halyard.core.error.v1.HalException;
import com.netflix.spinnaker.halyard.core.tasks.v1.DaemonTaskHandler;
import com.netflix.spinnaker.halyard.deploy.deployment.v1.AccountDeploymentDetails;
import com.netflix.spinnaker.halyard.deploy.services.v1.ArtifactService;
import com.netflix.spinnaker.halyard.deploy.services.v1.GenerateService;
import com.netflix.spinnaker.halyard.deploy.services.v1.VaultService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.RunningServiceDetails;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerRuntimeSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.profile.Profile;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.*;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.DistributedService;
import org.apache.commons.lang.RandomStringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static com.netflix.spinnaker.halyard.core.problem.v1.Problem.Severity.FATAL;

public interface GoogleDistributedService<T> extends DistributedService<T, GoogleAccount> {
  VaultService getVaultService();
  ArtifactService getArtifactService();
  ServiceInterfaceFactory getServiceInterfaceFactory();
  String getGoogleImageProject();
  String getStartupScriptPath();

  default List<String> getScopes() {
    List<String> result = new ArrayList<>();
    result.add("https://www.googleapis.com/auth/devstorage.read_only");
    result.add("https://www.googleapis.com/auth/logging.write");
    result.add("https://www.googleapis.com/auth/monitoring.write");
    result.add("https://www.googleapis.com/auth/servicecontrol");
    result.add("https://www.googleapis.com/auth/service.management.readonly");
    result.add("https://www.googleapis.com/auth/trace.append");

    return result;
  }

  @Override
  default Provider.ProviderType getProviderType() {
    return Provider.ProviderType.GOOGLE;
  }

  default String getStartupScript() {
    return String.join("\n", "#!/usr/bin/env bash",
        "",
        "# AUTO-GENERATED BY HALYARD",
        "",
        getStartupScriptPath() + "startup.sh google");
  }

  default String getArtifactId(String deploymentName) {
    String artifactName = getArtifact().getName();
    String version = getArtifactService().getArtifactVersion(deploymentName, getArtifact());
    return String.format("projects/%s/global/images/%s",
        getGoogleImageProject(),
        String.join("-", "spinnaker", artifactName, version.replace(".", "-")));
  }

  @Override
  default List<ConfigSource> stageProfiles(AccountDeploymentDetails<GoogleAccount> details,
      GenerateService.ResolvedConfiguration resolvedConfiguration) {

    SpinnakerService thisService = getService();
    ServiceSettings thisServiceSettings = resolvedConfiguration.getServiceSettings(thisService);
    Integer version = getLatestEnabledServiceVersion(details, thisServiceSettings);
    if (version == null) {
      version = 0;
    } else {
      version++;
    }

    SpinnakerMonitoringDaemonService monitoringService = getMonitoringDaemonService();
    String name = getServiceName();
    List<ConfigSource> configSources = new ArrayList<>();
    ServiceSettings monitoringSettings = resolvedConfiguration.getServiceSettings(monitoringService);
    String stagingPath = getSpinnakerStagingPath();
    VaultService vaultService = getVaultService();
    DeploymentConfiguration deploymentConfiguration = details.getDeploymentConfiguration();

    if (thisServiceSettings.isMonitored() && monitoringSettings.isEnabled()) {
      Map<String, Profile> monitoringProfiles = resolvedConfiguration.getProfilesForService(monitoringService.getType());

      Profile profile = monitoringProfiles.get(SpinnakerMonitoringDaemonService.serviceRegistryProfileName(name));
      if (profile == null) {
        throw new RuntimeException("Assertion violated: service monitoring enabled but no registry entry generated.");
      }

      String secretName = secretName(profile.getName(), version);
      String mountPoint = Paths.get(profile.getOutputFile()).toString();
      Path stagedFile = Paths.get(profile.getStagedFile(stagingPath));
      vaultService.publishSecret(deploymentConfiguration, secretName, stagedFile);

      configSources.add(new ConfigSource().setId(secretName).setMountPath(mountPoint));

      profile = monitoringProfiles.get("monitoring.yml");
      if (profile == null) {
        throw new RuntimeException("Assertion violated: service monitoring enabled but no monitoring profile was generated.");
      }

      secretName = secretName(profile.getName(), version);
      mountPoint = Paths.get(profile.getOutputFile()).toString();
      stagedFile = Paths.get(profile.getStagedFile(stagingPath));
      vaultService.publishSecret(deploymentConfiguration, secretName, stagedFile);

      configSources.add(new ConfigSource().setId(secretName).setMountPath(mountPoint));
    }

    Map<String, Profile> serviceProfiles = resolvedConfiguration.getProfilesForService(thisService.getType());
    Set<String> requiredFiles = new HashSet<>();

    for (Map.Entry<String, Profile> entry : serviceProfiles.entrySet()) {
      Profile profile = entry.getValue();
      requiredFiles.addAll(profile.getRequiredFiles());

      String mountPoint = profile.getOutputFile();
      String secretName = secretName("profile-" + profile.getName(), version);
      Path stagedFile = Paths.get(profile.getStagedFile(stagingPath));
      vaultService.publishSecret(deploymentConfiguration, secretName, stagedFile);

      configSources.add(new ConfigSource().setId(secretName).setMountPath(mountPoint));
    }

    for (String file : requiredFiles) {
      String mountPoint = Paths.get(file).toString();
      String secretName = secretName("dependencies-" + file, version);
      vaultService.publishSecret(deploymentConfiguration, secretName, Paths.get(file));

      configSources.add(new ConfigSource().setId(secretName).setMountPath(mountPoint));
    }

    return configSources;
  }

  default String secretName(String detail, int version) {
    return String.join("-",
        "hal",
        getService().getType().getCanonicalName(),
        detail,
        version + "",
        RandomStringUtils.random(5, true, true));
  }

  @Override
  default void ensureRunning(AccountDeploymentDetails<GoogleAccount> details,
      GenerateService.ResolvedConfiguration resolvedConfiguration,
      List<ConfigSource> configSources,
      boolean recreate) {
    int version = 0;
    ServiceSettings settings = resolvedConfiguration.getServiceSettings(getService());
    if (!recreate) {
      RunningServiceDetails runningServiceDetails = getRunningServiceDetails(details, settings);
      if (runningServiceDetails.getLatestEnabledVersion() != null) {
        DaemonTaskHandler.message("Service " + getServiceName() + " is already deployed and not safe to restart.");
        return;
      }
    }

    GoogleAccount account = details.getAccount();
    String project = account.getProject();

    Compute compute = GoogleProviderUtils.getCompute(details);

    InstanceGroupManager manager = new InstanceGroupManager();

    InstanceTemplate template = new InstanceTemplate()
        .setName(getServiceName() + "-hal-" + System.currentTimeMillis())
        .setDescription("Halyard-generated instance template for deploying Spinnaker");

    Metadata.Items items = new Metadata.Items()
        .setKey("startup-script")
        .setValue(getStartupScript());

    Metadata metadata = new Metadata().setItems(Collections.singletonList(items));

    AccessConfig accessConfig = new AccessConfig()
        .setName("External NAT")
        .setType("ONE_TO_ONE_NAT");

    NetworkInterface networkInterface = new NetworkInterface()
        .setNetwork(GoogleProviderUtils.ensureSpinnakerNetworkExists(details))
        .setAccessConfigs(Collections.singletonList(accessConfig));

    ServiceAccount sa = new ServiceAccount()
        .setEmail(GoogleProviderUtils.defaultServiceAccount(details))
        .setScopes(getScopes());

    InstanceProperties properties = new InstanceProperties()
        .setMachineType("n1-standard-1")
        .setMetadata(metadata)
        .setServiceAccounts(Collections.singletonList(sa))
        .setNetworkInterfaces(Collections.singletonList(networkInterface));

    AttachedDisk disk = new AttachedDisk()
        .setBoot(true);

    AttachedDiskInitializeParams diskParams = new AttachedDiskInitializeParams()
        .setSourceImage(getArtifactId(details.getDeploymentName()));

    disk.setInitializeParams(diskParams);
    List<AttachedDisk> disks = new ArrayList<>();

    disks.add(disk);
    properties.setDisks(disks);
    template.setProperties(properties);

    String instanceTemplateUrl;
    Operation operation;
    try {
      operation = compute.instanceTemplates().insert(project, template).execute();
      instanceTemplateUrl = operation.getTargetLink();
      GoogleProviderUtils.waitOnGlobalOperation(compute, project, operation);
    } catch (IOException e) {
      throw new HalException(FATAL, "Failed to create instance template for " + settings.getArtifactId() + ": " + e.getMessage(), e);
    }

    String migName = getVersionedName(version);
    manager.setInstanceTemplate(instanceTemplateUrl);
    manager.setBaseInstanceName(migName);
    manager.setTargetSize(settings.getTargetSize());
    manager.setName(migName);

    try {
      operation = compute.instanceGroupManagers().insert(project, settings.getLocation(), manager).execute();
      GoogleProviderUtils.waitOnZoneOperation(compute, project, settings.getLocation(), operation);
    } catch (IOException e) {
      throw new HalException(FATAL, "Failed to create instance group to run artifact " + settings.getArtifactId() + ": " + e.getMessage(), e);
    }

    long running = 0;
    while (running < 1) {
      running = getRunningServiceDetails(details, settings)
          .getInstances()
          .getOrDefault(version, new ArrayList<>())
          .stream()
          .filter(RunningServiceDetails.Instance::isRunning)
          .count();
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ignored) {
      }
    }
  }

  @Override
  default Map<String, Object> getLoadBalancerDescription(AccountDeploymentDetails<GoogleAccount> details, SpinnakerRuntimeSettings runtimeSettings) {
    return new HashMap<>();
  }

  @Override
  default Map<String, Object> getServerGroupDescription(AccountDeploymentDetails<GoogleAccount> details, SpinnakerRuntimeSettings runtimeSettings, List<ConfigSource> configSources) {
    return new HashMap<>();
  }

  @Override
  default List<String> getHealthProviders() {
    return Collections.singletonList("google");
  }

  @Override
  default Map<String, List<String>> getAvailabilityZones(ServiceSettings settings) {
    Map<String, List<String>> result = new HashMap<>();
    List<String> zones = Collections.singletonList(settings.getLocation());
    result.put(getRegion(settings), zones);
    return result;
  }

  @Override
  default String getRegion(ServiceSettings settings) {
    String zone = settings.getLocation();
    return zone.substring(0, zone.lastIndexOf("-"));
  }

  @Override
  default RunningServiceDetails getRunningServiceDetails(AccountDeploymentDetails<GoogleAccount> details, ServiceSettings settings) {
    RunningServiceDetails result = new RunningServiceDetails();
    Compute compute = GoogleProviderUtils.getCompute(details);
    GoogleAccount account = details.getAccount();
    List<InstanceGroupManager> migs;
    try {
      migs = compute.instanceGroupManagers().list(account.getProject(), settings.getLocation()).execute().getItems();
    } catch (IOException e) {
      throw new HalException(FATAL, "Failed to load MIGS: " + e.getMessage(), e);
    }

    String serviceName = getService().getServiceName();

    migs = migs.stream()
        .filter(ig -> ig.getName().startsWith(serviceName))
        .collect(Collectors.toList());

    Map<Integer, List<RunningServiceDetails.Instance>> instances = migs.stream().reduce(new HashMap<>(),
        (map, mig) -> {
          Names names = Names.parseName(mig.getName());
          Integer version = names.getSequence();
          List<RunningServiceDetails.Instance> computeInstances;
          try {
            computeInstances = compute
                .instanceGroupManagers()
                .listManagedInstances(account.getProject(), settings.getLocation(), mig.getName())
                .execute()
                .getManagedInstances()
                .stream()
                .map(i -> {
                      String instanceUrl = i.getInstance();
                      String instanceStatus = i.getInstanceStatus();
                      boolean running = instanceStatus != null && instanceStatus.equalsIgnoreCase("running");
                      String instanceName = instanceUrl.substring(instanceUrl.lastIndexOf('/') + 1, instanceUrl.length());
                      return new RunningServiceDetails.Instance()
                          .setId(instanceName)
                          .setLocation(settings.getLocation())
                          .setRunning(running);
                    }
                ).collect(Collectors.toList());
          } catch (IOException e) {
            throw new HalException(FATAL, "Failed to load target pools for " + serviceName, e);
          }
          map.put(version, computeInstances);
          return map;
        },
        (m1, m2) -> {
          m1.putAll(m2);
          return m1;
        });

    result.setInstances(instances);

    List<Integer> versions = new ArrayList<>(instances.keySet());
    if (!versions.isEmpty()) {
      versions.sort(Integer::compareTo);
      result.setLatestEnabledVersion(versions.get(versions.size() - 1));
    }

    return result;
  }

  @Override
  default T connect(AccountDeploymentDetails<GoogleAccount> details, SpinnakerRuntimeSettings runtimeSettings) {
    ServiceSettings settings = runtimeSettings.getServiceSettings(getService());
    RunningServiceDetails runningServiceDetails = getRunningServiceDetails(details, settings);

    Integer enabledVersion = runningServiceDetails.getLatestEnabledVersion();
    if (enabledVersion == null) {
      throw new HalException(FATAL, "Cannot connect to " + getServiceName() + " when no server groups have been deployed yet");
    }

    List<RunningServiceDetails.Instance> instances = runningServiceDetails.getInstances().get(enabledVersion);

    if (instances == null || instances.isEmpty()) {
      throw new HalException(FATAL, "Cannot connect to " + getServiceName() + " when no instances have been deployed yet");
    }

    URI uri = GoogleProviderUtils.openSshTunnel(details, instances.get(0).getId(), settings);
    return getServiceInterfaceFactory().createService(uri.toString(), getService());
  }

  @Override
  default String connectCommand(AccountDeploymentDetails<GoogleAccount> details, SpinnakerRuntimeSettings runtimeSettings) {
    return null;
  }

  @Override
  default void deleteVersion(AccountDeploymentDetails<GoogleAccount> details, ServiceSettings settings, Integer version) {

  }
}
