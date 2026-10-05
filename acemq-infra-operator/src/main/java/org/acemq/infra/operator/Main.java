/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.infra.operator;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfiguration;

/**
 * The process: one controller, elected.
 *
 * <p>Leader election is not optional here. Two replicas each reconciling the same resource would
 * be two processes each believing the other's {@code Applying} was its own; with a lease, a second
 * replica waits and only ever finds the resource as the first one left it.
 */
public final class Main {

    private Main() {
    }

    /**
     * Environment: {@code POD_NAMESPACE} for the lease (from the downward API), and
     * {@code WATCH_NAMESPACES}, comma-separated, to reconcile Cutovers — and so read Secrets — in
     * those namespaces only. Unset means all of them. {@code ACEMQ_INFRA_ALLOWED_URLS}, the hosts
     * credentials may be sent to; see {@link Allowlist}. {@code ACEMQ_INFRA_ALLOW_HOOKS=true} to run
     * hook endpoints, which are refused otherwise.
     *
     * @param arguments none
     */
    public static void main(String[] arguments) {
        KubernetesClient client = new KubernetesClientBuilder().build();
        String leaseNamespace = System.getenv().getOrDefault("POD_NAMESPACE",
                client.getNamespace());
        Operator operator = new Operator(o -> o
                .withKubernetesClient(client)
                .withLeaderElectionConfiguration(
                        new LeaderElectionConfiguration("acemq-infra-operator", leaseNamespace)));
        String watched = System.getenv("WATCH_NAMESPACES");
        CutoverReconciler reconciler = new CutoverReconciler(client, new Engine.Rabbit(),
                Allowlist.parse(System.getenv(Allowlist.ENV)),
                Boolean.parseBoolean(System.getenv(CutoverReconciler.ALLOW_HOOKS)));
        if (watched == null || watched.isBlank()) {
            operator.register(reconciler);
        } else {
            Set<String> namespaces = Arrays.stream(watched.split(",")).map(String::trim)
                    .filter(name -> !name.isEmpty()).collect(Collectors.toSet());
            operator.register(reconciler, o -> o.settingNamespaces(namespaces));
        }
        operator.installShutdownHook();
        operator.start();
    }
}
