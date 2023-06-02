/*
 * Copyright 2013-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.kubernetes.client.discovery.it;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1Service;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.k3s.K3sContainer;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import org.springframework.cloud.kubernetes.commons.discovery.DefaultKubernetesServiceInstance;
import org.springframework.cloud.kubernetes.integration.tests.commons.Commons;
import org.springframework.cloud.kubernetes.integration.tests.commons.Phase;
import org.springframework.cloud.kubernetes.integration.tests.commons.native_client.Util;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * @author wind57
 */
class KubernetesClientDiscoverySingleSelectiveNamespaceIT {

	private static final String BLOCKING_PUBLISH = "Will publish InstanceRegisteredEvent from blocking implementation";

	private static final String REACTIVE_PUBLISH = "Will publish InstanceRegisteredEvent from reactive implementation";

	private static final String NAMESPACE = "default";

	private static final String NAMESPACE_A = "a";

	private static final String NAMESPACE_B = "b";

	private static final String IMAGE_NAME = "spring-cloud-kubernetes-client-discovery-it";

	private static Util util;

	private static final K3sContainer K3S = Commons.container();

	@BeforeAll
	static void beforeAll() throws Exception {
		K3S.start();
		Commons.validateImage(IMAGE_NAME, K3S);
		Commons.loadSpringCloudKubernetesImage(IMAGE_NAME, K3S);

		util = new Util(K3S);
		Commons.systemPrune();

		util.createNamespace(NAMESPACE_A);
		util.createNamespace(NAMESPACE_B);
		util.setUpClusterWide(NAMESPACE, Set.of(NAMESPACE, NAMESPACE_A, NAMESPACE_B));
		util.wiremock(NAMESPACE, "/wiremock", Phase.CREATE);
		util.wiremock(NAMESPACE_A, "/wiremock", Phase.CREATE);
		util.wiremock(NAMESPACE_B, "/wiremock", Phase.CREATE);
	}

	@AfterAll
	static void after() throws Exception {
		Commons.cleanUp(IMAGE_NAME, K3S);

		util.wiremock(NAMESPACE, "/wiremock", Phase.DELETE);
		util.wiremock(NAMESPACE_A, "/wiremock", Phase.DELETE);
		util.wiremock(NAMESPACE_B, "/wiremock", Phase.DELETE);
		util.deleteClusterWide(NAMESPACE, Set.of(NAMESPACE, NAMESPACE_A, NAMESPACE_B));
		util.deleteNamespace(NAMESPACE_A);
		util.deleteNamespace(NAMESPACE_B);
	}

	/**
	 * Deploy wiremock in 3 namespaces: default, a, b. Search only in selective namespace
	 * 'a' with blocking enabled and reactive disabled, as such find a single service and
	 * its service instance.
	 */
	@Test
	void testOneNamespaceBlockingOnly() {

		manifests(Phase.CREATE, false, true);

		String logs = logs();
		Assertions.assertTrue(logs.contains("using selective namespaces : [a]"));
		Assertions.assertTrue(
				logs.contains("ConditionalOnSelectiveNamespacesMissing : found selective namespaces : [a]"));
		Assertions.assertTrue(
				logs.contains("ConditionalOnSelectiveNamespacesPresent : found selective namespaces : [a]"));
		Assertions.assertTrue(logs.contains("registering lister (for services) in namespace : a"));
		Assertions.assertTrue(logs.contains("registering lister (for endpoints) in namespace : a"));

		// this tiny checks makes sure that blocking is enabled and reactive is disabled.
		Assertions.assertTrue(logs.contains(BLOCKING_PUBLISH));
		Assertions.assertFalse(logs.contains(REACTIVE_PUBLISH));

		blockingCheck();

		manifests(Phase.DELETE, false, true);

	}

	/**
	 * Deploy wiremock in 3 namespaces: default, a, b. Search only in selective namespace
	 * 'a' with blocking disabled and reactive enabled, as such find a single service and
	 * its service instance.
	 */
	@Test
	void testOneNamespaceReactiveOnly() {

		manifests(Phase.CREATE, true, false);

		String logs = logs();
		Assertions.assertTrue(logs.contains("using selective namespaces : [a]"));
		Assertions.assertTrue(
				logs.contains("ConditionalOnSelectiveNamespacesMissing : found selective namespaces : [a]"));
		Assertions.assertTrue(
				logs.contains("ConditionalOnSelectiveNamespacesPresent : found selective namespaces : [a]"));
		Assertions.assertTrue(logs.contains("registering lister (for services) in namespace : a"));
		Assertions.assertTrue(logs.contains("registering lister (for endpoints) in namespace : a"));

		// this tiny checks makes sure that reactive is enabled and blocking is disabled.
		Assertions.assertFalse(logs.contains(BLOCKING_PUBLISH));
		Assertions.assertTrue(logs.contains(REACTIVE_PUBLISH));

		reactiveCheck();

		manifests(Phase.DELETE, true, false);

	}

	/**
	 * Deploy wiremock in 3 namespaces: default, a, b. Search only in selective namespace
	 * 'a' with blocking enabled and reactive enabled, as such find a single service and
	 * its service instance.
	 */
	@Test
	void testOneNamespaceBothBlockingAndReactive() {

		manifests(Phase.CREATE, false, false);

		String logs = logs();
		Assertions.assertTrue(logs.contains("using selective namespaces : [a]"));
		Assertions.assertTrue(
				logs.contains("ConditionalOnSelectiveNamespacesMissing : found selective namespaces : [a]"));
		Assertions.assertTrue(
				logs.contains("ConditionalOnSelectiveNamespacesPresent : found selective namespaces : [a]"));
		Assertions.assertTrue(logs.contains("registering lister (for services) in namespace : a"));
		Assertions.assertTrue(logs.contains("registering lister (for endpoints) in namespace : a"));

		// this tiny checks makes sure that blocking and reactive is enabled.
		Assertions.assertTrue(logs.contains(BLOCKING_PUBLISH));
		Assertions.assertTrue(logs.contains(REACTIVE_PUBLISH));

		blockingCheck();
		reactiveCheck();

		manifests(Phase.DELETE, false, false);

	}

	private static void manifests(Phase phase, boolean disableBlocking, boolean disableReactive) {
		V1Deployment deployment = (V1Deployment) util.yaml("kubernetes-discovery-deployment.yaml");
		V1Service service = (V1Service) util.yaml("kubernetes-discovery-service.yaml");
		V1Ingress ingress = (V1Ingress) util.yaml("kubernetes-discovery-ingress.yaml");

		List<V1EnvVar> envVars = new ArrayList<>(
				Optional.ofNullable(deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv())
						.orElse(List.of()));
		V1EnvVar debugLevel = new V1EnvVar().name("LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_CLOUD_KUBERNETES_CLIENT_DISCOVERY")
				.value("DEBUG");
		V1EnvVar selectiveNamespaceA = new V1EnvVar().name("SPRING_CLOUD_KUBERNETES_DISCOVERY_NAMESPACES_0")
				.value(NAMESPACE_A);
		if (disableReactive) {
			V1EnvVar disableReactiveEnvVar = new V1EnvVar().name("SPRING_CLOUD_DISCOVERY_REACTIVE_ENABLED")
					.value("FALSE");
			envVars.add(disableReactiveEnvVar);
		}

		if (disableBlocking) {
			V1EnvVar disableBlockingEnvVar = new V1EnvVar().name("SPRING_CLOUD_DISCOVERY_BLOCKING_ENABLED")
					.value("FALSE");
			envVars.add(disableBlockingEnvVar);
		}

		envVars.add(debugLevel);
		envVars.add(selectiveNamespaceA);
		deployment.getSpec().getTemplate().getSpec().getContainers().get(0).setEnv(envVars);

		if (phase.equals(Phase.CREATE)) {
			util.createAndWait(NAMESPACE, null, deployment, service, ingress, true);
		}
		else if (phase.equals(Phase.DELETE)) {
			util.deleteAndWait(NAMESPACE, deployment, service, ingress);
		}
	}

	private void reactiveCheck() {
		WebClient servicesClient = builder().baseUrl("http://localhost/reactive/services").build();

		List<String> servicesResult = servicesClient.method(HttpMethod.GET).retrieve()
				.bodyToMono(new ParameterizedTypeReference<List<String>>() {

				}).retryWhen(retrySpec()).block();

		Assertions.assertEquals(servicesResult.size(), 1);
		Assertions.assertTrue(servicesResult.contains("service-wiremock"));

		WebClient ourServiceClient = builder().baseUrl("http://localhost/reactive/service-instances/service-wiremock")
				.build();

		List<DefaultKubernetesServiceInstance> ourServiceInstances = ourServiceClient.method(HttpMethod.GET).retrieve()
				.bodyToMono(new ParameterizedTypeReference<List<DefaultKubernetesServiceInstance>>() {

				}).retryWhen(retrySpec()).block();

		Assertions.assertEquals(ourServiceInstances.size(), 1);

		DefaultKubernetesServiceInstance serviceInstance = ourServiceInstances.get(0);
		// we only care about namespace here, as all other fields are tested in various
		// other tests.
		Assertions.assertEquals(serviceInstance.getNamespace(), "a");
	}

	private void blockingCheck() {
		WebClient servicesClient = builder().baseUrl("http://localhost/services").build();

		List<String> servicesResult = servicesClient.method(HttpMethod.GET).retrieve()
				.bodyToMono(new ParameterizedTypeReference<List<String>>() {

				}).retryWhen(retrySpec()).block();

		Assertions.assertEquals(servicesResult.size(), 1);
		Assertions.assertTrue(servicesResult.contains("service-wiremock"));

		WebClient ourServiceClient = builder().baseUrl("http://localhost/service-instances/service-wiremock").build();

		List<DefaultKubernetesServiceInstance> ourServiceInstances = ourServiceClient.method(HttpMethod.GET).retrieve()
				.bodyToMono(new ParameterizedTypeReference<List<DefaultKubernetesServiceInstance>>() {

				}).retryWhen(retrySpec()).block();

		Assertions.assertEquals(ourServiceInstances.size(), 1);

		DefaultKubernetesServiceInstance serviceInstance = ourServiceInstances.get(0);
		// we only care about namespace here, as all other fields are tested in various
		// other tests.
		Assertions.assertEquals(serviceInstance.getNamespace(), "a");
	}

	private String logs() {
		try {
			String appPodName = K3S.execInContainer("sh", "-c",
					"kubectl get pods -l app=" + IMAGE_NAME + " -o=name --no-headers | tr -d '\n'").getStdout();

			Container.ExecResult execResult = K3S.execInContainer("sh", "-c", "kubectl logs " + appPodName.trim());
			return execResult.getStdout();
		}
		catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private WebClient.Builder builder() {
		return WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()));
	}

	private RetryBackoffSpec retrySpec() {
		return Retry.fixedDelay(15, Duration.ofSeconds(1)).filter(Objects::nonNull);
	}

}