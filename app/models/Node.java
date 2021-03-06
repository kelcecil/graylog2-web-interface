/*
 * Copyright 2013 TORCH UG
 *
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 */
package models;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import lib.APIException;
import lib.ApiClient;
import lib.ExclusiveInputException;
import lib.metrics.Metric;
import models.api.requests.InputLaunchRequest;
import models.api.responses.BuffersResponse;
import models.api.responses.cluster.NodeSummaryResponse;
import models.api.responses.SystemOverviewResponse;
import models.api.responses.metrics.MetricsListResponse;
import models.api.responses.system.*;
import models.api.responses.system.loggers.LoggerSummary;
import models.api.responses.system.loggers.LoggersResponse;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.LoggerFactory;
import play.Logger;
import play.mvc.Http;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Lennart Koopmann <lennart@torch.sh>
 */
public class Node extends ClusterEntity {

    public interface Factory {
        Node fromSummaryResponse(NodeSummaryResponse r);
        Node fromTransportAddress(URI transportAddress);
    }
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(Node.class);
    private final ApiClient api;

    private final Input.Factory inputFactory;

    private final URI transportAddress;
    private DateTime lastSeen;
    private DateTime lastContact;
    private String nodeId;
    private boolean isMaster;
    private String shortNodeId;
    private AtomicBoolean active = new AtomicBoolean();

    private final boolean fromConfiguration;
    private SystemOverviewResponse systemInfo;

    private AtomicInteger failureCount = new AtomicInteger(0);

    /* for initial set up in test */
    public Node(NodeSummaryResponse r) {
        this(null, null, r);
    }

    @AssistedInject
    public Node(ApiClient api, Input.Factory inputFactory, @Assisted NodeSummaryResponse r) {
        this.api = api;
        this.inputFactory = inputFactory;

        transportAddress = normalizeUriPath(r.transportAddress);
        lastSeen = new DateTime(r.lastSeen);
        nodeId = r.id;
        shortNodeId = r.shortNodeId;
        isMaster = r.isMaster;
        fromConfiguration = false;
    }

    @AssistedInject
    public Node(ApiClient api, Input.Factory inputFactory, @Assisted URI transportAddress) {
        this.api = api;
        this.inputFactory = inputFactory;

        this.transportAddress = normalizeUriPath(transportAddress);
        lastSeen = null;
        nodeId = null;
        shortNodeId = "unresolved";
        isMaster = false;
        fromConfiguration = true;
    }

    public BufferInfo getBufferInfo() {
        try {
            return new BufferInfo(
                    api.get(BuffersResponse.class)
                    .node(this)
                    .path("/system/buffers")
                    .execute());
        } catch (APIException e) {
            log.error("Unable to read buffer info from node " + this, e);
        } catch (IOException e) {
            log.error("Unexpected exception", e);
        }
        return null;
    }

    public List<InternalLogger> allLoggers() {
        List<InternalLogger> loggers = Lists.newArrayList();
        try {
            LoggersResponse response = api.get(LoggersResponse.class)
                    .node(this)
                    .path("/system/loggers")
                    .execute();

            for (Map.Entry<String, LoggerSummary> logger : response.loggers.entrySet()) {
                loggers.add(new InternalLogger(logger.getKey(), logger.getValue().level, logger.getValue().syslogLevel));
            }
        } catch (APIException e) {
            log.error("Unable to load loggers for node " + this, e);
        } catch (IOException e) {
            log.error("Unable to load loggers for node " + this, e);
        }
        return loggers;
    }

    public String getThreadDump() throws IOException, APIException {
        return api.get(String.class).node(this).path("/system/threaddump").execute();
    }

    public List<Input> getInputs() {
        List<Input> inputs = Lists.newArrayList();

        for (InputSummaryResponse input : inputs().inputs) {
            inputs.add(inputFactory.fromSummaryResponse(input, this));
        }

        return inputs;
    }

    public Input getInput(String inputId) throws IOException, APIException {
        final InputSummaryResponse inputSummaryResponse = api.get(InputSummaryResponse.class).node(this).path("/system/inputs/{0}", inputId).execute();
        return inputFactory.fromSummaryResponse(inputSummaryResponse, this);
    }

    public int numberOfInputs() {
        return inputs().total;
    }

    public boolean launchInput(String title, String type, Map<String, Object> configuration, User creator, boolean isExclusive) throws ExclusiveInputException {
        if (isExclusive) {
            for (Input input : getInputs()) {
                if (input.getType().equals(type)) {
                    throw new ExclusiveInputException();
                }
            }
        }

        InputLaunchRequest request = new InputLaunchRequest();
        request.title = title;
        request.type = type;
        request.configuration = configuration;
        request.creatorUserId = creator.getId();

        try {
            api.post()
                    .path("/system/inputs")
                    .node(this)
                    .body(request)
                    .expect(Http.Status.ACCEPTED)
                    .execute();
            return true;
        } catch (APIException e) {
            log.error("Could not launch input " + title, e);
        } catch (IOException e) {
            log.error("Could not launch input " + title, e);
        }
        return false;
    }

    @Override
    public boolean terminateInput(String inputId) {
        try {
            api.delete().path("/system/inputs/{0}", inputId)
                    .node(this)
                    .expect(Http.Status.ACCEPTED)
                    .execute();
            return true;
        } catch (APIException e) {
            log.error("Could not terminate input " + inputId, e);
        } catch (IOException e) {
            log.error("Could not terminate input " + inputId, e);
        }

        return false;
    }

    public Map<String, String> getInputTypes() throws IOException, APIException {
        return api.get(InputTypesResponse.class).node(this).path("/system/inputs/types").execute().types;
    }

    public InputTypeSummaryResponse getInputTypeInformation(String type) throws IOException, APIException {
        return api.get(InputTypeSummaryResponse.class).node(this).path("/system/inputs/types/{0}", type).execute();
    }

    public Map<String, InputTypeSummaryResponse> getAllInputTypeInformation() throws IOException, APIException {
        Map<String, InputTypeSummaryResponse> types = Maps.newHashMap();

        for (String type : getInputTypes().keySet()) {
            InputTypeSummaryResponse itr = getInputTypeInformation(type);
            types.put(itr.type, itr);
        }

        return types;
    }

    // TODO nodes should not have state beyond their activity status
    public synchronized void loadSystemInformation() {
        try {
            systemInfo = api.get(SystemOverviewResponse.class).path("/system").node(this).execute();
        } catch (APIException e) {
            log.error("Unable to load system information for node " + this, e);
        } catch (IOException e) {
            log.error("Unable to load system information for node " + this, e);
        }
    }

    @Override
    public String getTransportAddress() {
        return transportAddress.toASCIIString();
    }

    public URI getTransportAddressUri() {
        return transportAddress;
    }

    public DateTime getLastSeen() {
        return lastSeen;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getShortNodeId() {
        return shortNodeId;
    }

    public String getHostname() {
        if (systemInfo == null) {
            loadSystemInformation();
        }
        return systemInfo.hostname;
    }

    public boolean isMaster() {
        return isMaster;
    }

    public boolean isProcessing() {
        if (systemInfo == null) {
            loadSystemInformation();
        }
        return systemInfo.isProcessing;
    }

    public Map<String, Metric> getMetrics(String namespace) throws APIException, IOException {
        MetricsListResponse response = api.get(MetricsListResponse.class)
                .node(this)
                .path("/system/metrics/namespace/{0}", namespace)
                .expect(200, 404)
                .execute();

        return response.getMetrics();
    }

    public void pause() throws IOException, APIException {
        api.put()
            .path("/system/processing/pause")
            .node(this)
            .execute();
    }

    public void resume() throws IOException, APIException {
        api.put()
            .path("/system/processing/resume")
            .node(this)
            .execute();
    }

    public int getThroughput() {
        try {
            return api.get(ServerThroughputResponse.class).node(this).path("/system/throughput").execute().throughput;
        } catch (APIException e) {
            log.error("Could not load throughput for node " + this, e);
        } catch (IOException e) {
            log.error("Could not load throughput for node " + this, e);
        }
        return 0;
    }

    /**
     * This swallows all exceptions to allow easy lazy-loading in views without exception handling.
     *
     * @return List of running inputs o this node.
     */
    private InputsResponse inputs() {
        try {
            return api.get(InputsResponse.class).node(this).path("/system/inputs").execute();
        } catch (Exception e) {
            Logger.error("Could not get inputs.", e);
            throw new RuntimeException("Could not get inputs.", e);
        }
    }
    public boolean isFromConfiguration() {
        return fromConfiguration;
    }

    @Override
    public void markFailure() {
        failureCount.incrementAndGet();
        setActive(false);
        log.info("{} failed, marking as inactive.", this);
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public DateTime getLastContact() {
        return lastContact;
    }

    public void merge(Node updatedNode) {
        log.debug("Merging node {} in this node {}", updatedNode, this);
        this.lastSeen = updatedNode.lastSeen;
        this.isMaster = updatedNode.isMaster;
        this.nodeId = updatedNode.nodeId;
        this.shortNodeId = updatedNode.shortNodeId;
        this.setActive(updatedNode.isActive());
    }

    @Override
    public void touch() {
        this.lastContact = DateTime.now(DateTimeZone.UTC);
        setActive(true);
    }

    public boolean isActive() {
        return active.get();
    }

    public void setActive(boolean active) {
        this.active.set(active);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Node node = (Node) o;

        // if both have a node id, and they are the same, the nodes are the same.
        if (nodeId != null && node.nodeId != null) {
            if (nodeId.equals(node.nodeId)) {
                return true;
            }
        }
        // otherwise if the transport addresses are the same, we consider the nodes to be the same.
        if (transportAddress.equals(node.transportAddress)) return true;

        // otherwise the nodes aren't the same
        return false;
    }

    @Override
    public int hashCode() {
        int result = transportAddress.hashCode();
        result = 31 * result + (nodeId != null ? nodeId.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder b = new StringBuilder();
        if (nodeId == null) {
            b.append("UnresolvedNode {'").append(transportAddress).append("'}");
            return b.toString();
        }

        b.append("Node {");
        b.append("'").append(nodeId).append("'");
        b.append(", ").append(transportAddress);
        if (isMaster) {
            b.append(", master");
        }
        if (isActive()) {
            b.append(", active");
        } else {
            b.append(", inactive");
        }
        final int failures = getFailureCount();
        if (failures > 0) {
            b.append(", failed: ").append(failures).append(" times");
        }
        b.append("}");
        return b.toString();
    }
}
