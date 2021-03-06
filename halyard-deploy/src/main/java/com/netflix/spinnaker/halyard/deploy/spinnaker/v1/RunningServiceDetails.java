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

package com.netflix.spinnaker.halyard.deploy.spinnaker.v1;

import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class RunningServiceDetails {
  int healthy;
  LoadBalancer loadBalancer;
  Integer latestEnabledVersion;
  String artifactId;
  String internalEndpoint;
  String externalEndpoint;
  // Map of server group version (N) -> instance set
  Map<Integer, List<Instance>> instances = new HashMap<>();

  @Data
  public static class Instance {
    String id;
    String location;
    boolean running;
    boolean healthy;
  }

  @Data
  public static class LoadBalancer {
    boolean exists;
  }
}
