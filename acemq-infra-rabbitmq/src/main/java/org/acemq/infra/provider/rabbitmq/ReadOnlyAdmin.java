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
package org.acemq.infra.provider.rabbitmq;

import java.util.List;

import org.acemq.infra.provider.ClusterAccess;
import org.acemq.rabbitmq.admin.ChannelInfo;
import org.acemq.rabbitmq.admin.ConnectionInfo;
import org.acemq.rabbitmq.admin.ConsumerInfo;
import org.acemq.rabbitmq.admin.CurrentUser;
import org.acemq.rabbitmq.admin.FeatureFlagInfo;
import org.acemq.rabbitmq.admin.PermissionInfo;
import org.acemq.rabbitmq.admin.PolicyInfo;
import org.acemq.rabbitmq.admin.QueueInfo;
import org.acemq.rabbitmq.admin.RabbitAdmin;

/**
 * A management client with the writing taken off it.
 *
 * <p>This class is how "plan writes nothing to either broker" is made structural inside the one
 * module where a broker is reachable at all. {@link RabbitAdmin} has forty-odd methods and about
 * half of them write: {@code declareShovel}, {@code putPolicy}, {@code importDefinitions},
 * {@code closeConnection}, {@code deleteQueue}. The probe does not want any of them, and "the
 * probe does not call them" is a property of today's code rather than of tomorrow's. So the probe
 * never holds a {@code RabbitAdmin}. It holds one of these, and the writing methods are not on it
 * — a future change that wanted to declare a shovel during a plan would have to add the method
 * here first, in a class whose name says why it should not.
 *
 * <p>Every method below is a GET against the management API. The three that can answer "no" for a
 * reason that is not a failure — the shovel and federation endpoints, and a definitions export the
 * credentials are not allowed to read — are handled by the caller, because the distinction between
 * "absent" and "not permitted" is the interesting half of a probe.
 */
final class ReadOnlyAdmin implements AutoCloseable {

    private final RabbitAdmin admin;

    private ReadOnlyAdmin(RabbitAdmin admin) {
        this.admin = admin;
    }

    /**
     * Connects, scoped to the virtual host the deployment file named.
     *
     * @param access where the cluster is and who to be
     * @return a client that can only read
     */
    static ReadOnlyAdmin open(ClusterAccess access) {
        RabbitAdmin connected = RabbitAdmin.connect(access.management(), access.username(),
                access.password(), access.timeout());
        // Scoped straight away rather than later. Almost everything a probe counts is per-vhost,
        // and a client left on `/` would count a different estate from the one the file names --
        // silently, and plausibly, which is the worst way for a count to be wrong.
        return new ReadOnlyAdmin(access.vhost().isBlank() ? connected
                : connected.forVhost(access.vhost()));
    }

    /** The broker's version, and the first call that proves the credentials work. */
    String version() {
        return admin.version();
    }

    /** Who the management user turns out to be, and which tags it carries. */
    CurrentUser whoami() {
        return admin.whoami();
    }

    /** The queues in this virtual host, with their depths, their types and their consumers. */
    List<QueueInfo> queues() {
        return admin.queues();
    }

    /** How many exchanges are in this virtual host. */
    int exchanges() {
        return admin.exchanges().size();
    }

    /** How many bindings. */
    int bindings() {
        return admin.bindings().size();
    }

    /** The cluster's users, which are not scoped to a virtual host. */
    int users() {
        return admin.users().size();
    }

    /** The permission entries. */
    List<PermissionInfo> permissions() {
        return admin.permissions();
    }

    /** The policies, which are the ones that must land after the drain rather than before it. */
    List<PolicyInfo> policies() {
        return admin.policies();
    }

    /** The operator policies, which an application cannot override. */
    List<PolicyInfo> operatorPolicies() {
        return admin.operatorPolicies();
    }

    /** The runtime parameters of one component: the federation upstreams, or the shovels. */
    int parameters(String component) {
        return admin.parameters(component).size();
    }

    /** Every client connection to the cluster. */
    List<ConnectionInfo> connections() {
        return admin.connections();
    }

    /** Every channel, which is how a connection is told it is consuming. */
    List<ChannelInfo> channels() {
        return admin.channels();
    }

    /** Every consumer. */
    List<ConsumerInfo> consumers() {
        return admin.consumers();
    }

    /** The feature flags, which is where a broker says whether it knows what a stream is. */
    List<FeatureFlagInfo> featureFlags() {
        return admin.featureFlags();
    }

    /**
     * Reads the definitions document and throws it away.
     *
     * <p>The only thing a probe wants from it is whether it could be read at all, because that is
     * the observation behind {@code TOPOLOGY_EXPORT}. The document itself is a credential —
     * docs/message-state.md — and a probe result ends up in a plan, so it is not kept, not
     * returned and not logged.
     *
     * @return the number of bytes the broker sent, which is a fact about the read rather than
     *     about its contents
     */
    int definitionsSize() {
        return admin.exportVhostDefinitions().json().length();
    }

    @Override
    public void close() {
        admin.close();
    }
}
