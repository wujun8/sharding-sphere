/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.process.subscriber;

import org.apache.shardingsphere.infra.config.mode.ModeConfiguration;
import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.database.type.dialect.MySQLDatabaseType;
import org.apache.shardingsphere.infra.executor.sql.process.Process;
import org.apache.shardingsphere.infra.executor.sql.process.ProcessRegistry;
import org.apache.shardingsphere.infra.executor.sql.process.lock.ProcessOperationLock;
import org.apache.shardingsphere.infra.executor.sql.process.lock.ProcessOperationLockRegistry;
import org.apache.shardingsphere.infra.instance.metadata.InstanceMetaData;
import org.apache.shardingsphere.infra.instance.metadata.proxy.ProxyInstanceMetaData;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.schema.model.ShardingSphereSchema;
import org.apache.shardingsphere.infra.rule.identifier.type.ResourceHeldRule;
import org.apache.shardingsphere.infra.util.eventbus.EventBusContext;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.mode.manager.ContextManagerBuilderParameter;
import org.apache.shardingsphere.mode.manager.cluster.ClusterContextManagerBuilder;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.RegistryCenter;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.compute.event.KillProcessEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.compute.event.KillProcessInstanceCompleteEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.compute.event.ReportLocalProcessesCompletedEvent;
import org.apache.shardingsphere.mode.manager.cluster.coordinator.registry.status.compute.event.ShowProcessListTriggerEvent;
import org.apache.shardingsphere.mode.metadata.MetaDataContexts;
import org.apache.shardingsphere.mode.repository.cluster.ClusterPersistRepository;
import org.apache.shardingsphere.mode.repository.cluster.ClusterPersistRepositoryConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.internal.configuration.plugins.Plugins;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessListChangedSubscriberTest {
    
    private ProcessListChangedSubscriber subscriber;
    
    private ContextManager contextManager;
    
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ShardingSphereDatabase database;
    
    @BeforeEach
    void setUp() throws SQLException {
        contextManager = new ClusterContextManagerBuilder().build(createContextManagerBuilderParameter());
        contextManager.renewMetaDataContexts(new MetaDataContexts(contextManager.getMetaDataContexts().getPersistService(), new ShardingSphereMetaData(createDatabases(),
                contextManager.getMetaDataContexts().getMetaData().getGlobalRuleMetaData(), new ConfigurationProperties(new Properties()))));
        subscriber = new ProcessListChangedSubscriber(new RegistryCenter(mock(ClusterPersistRepository.class), new EventBusContext(), mock(ProxyInstanceMetaData.class), null), contextManager);
    }
    
    private ContextManagerBuilderParameter createContextManagerBuilderParameter() {
        ModeConfiguration modeConfig = new ModeConfiguration("Cluster", new ClusterPersistRepositoryConfiguration("FIXTURE", "", "", new Properties()));
        InstanceMetaData instanceMetaData = new ProxyInstanceMetaData("foo_instance_id", 3307);
        return new ContextManagerBuilderParameter(modeConfig, Collections.emptyMap(), Collections.emptyList(), new Properties(), Collections.emptyList(), instanceMetaData, false);
    }
    
    private Map<String, ShardingSphereDatabase> createDatabases() {
        when(database.getResourceMetaData().getDataSources()).thenReturn(new LinkedHashMap<>());
        when(database.getResourceMetaData().getStorageTypes()).thenReturn(Collections.singletonMap("ds_0", new MySQLDatabaseType()));
        when(database.getSchemas()).thenReturn(Collections.singletonMap("foo_schema", new ShardingSphereSchema()));
        when(database.getProtocolType()).thenReturn(new MySQLDatabaseType());
        when(database.getSchema("foo_schema")).thenReturn(mock(ShardingSphereSchema.class));
        when(database.getRuleMetaData().getRules()).thenReturn(new LinkedList<>());
        when(database.getRuleMetaData().getConfigurations()).thenReturn(Collections.emptyList());
        when(database.getRuleMetaData().findRules(ResourceHeldRule.class)).thenReturn(Collections.emptyList());
        return Collections.singletonMap("db", database);
    }
    
    @Test
    void assertReportLocalProcesses() throws ReflectiveOperationException {
        String instanceId = contextManager.getInstanceContext().getInstance().getMetaData().getId();
        Process process = mock(Process.class);
        String processId = "foo_id";
        when(process.getId()).thenReturn(processId);
        ProcessRegistry.getInstance().add(process);
        subscriber.reportLocalProcesses(new ShowProcessListTriggerEvent(instanceId, processId));
        ClusterPersistRepository repository = ((RegistryCenter) Plugins.getMemberAccessor().get(ProcessListChangedSubscriber.class.getDeclaredField("registryCenter"), subscriber)).getRepository();
        verify(repository).persist("/execution_nodes/foo_id/" + instanceId,
                "processes:" + System.lineSeparator() + "- completedUnitCount: 0\n  id: foo_id\n  idle: false\n  startMillis: 0\n  totalUnitCount: 0" + System.lineSeparator());
        verify(repository).delete("/nodes/compute_nodes/process_trigger/" + instanceId + ":foo_id");
    }
    
    @Test
    void assertCompleteToReportLocalProcesses() {
        String taskId = "foo_id";
        ProcessOperationLock lock = new ProcessOperationLock();
        ProcessOperationLockRegistry.getInstance().getLocks().put(taskId, lock);
        long startMillis = System.currentTimeMillis();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.submit(() -> {
            try {
                Thread.sleep(50L);
            } catch (final InterruptedException ignored) {
            }
            subscriber.completeToReportLocalProcesses(new ReportLocalProcessesCompletedEvent(taskId));
        });
        lockAndAwaitDefaultTime(lock);
        long currentTime = System.currentTimeMillis();
        assertTrue(currentTime >= startMillis + 50L);
        assertTrue(currentTime <= startMillis + 5000L);
        ProcessOperationLockRegistry.getInstance().getLocks().remove(taskId);
    }
    
    @Test
    void assertKillProcess() throws SQLException, ReflectiveOperationException {
        String instanceId = contextManager.getInstanceContext().getInstance().getMetaData().getId();
        String processId = "foo_id";
        subscriber.killProcess(new KillProcessEvent(instanceId, processId));
        ClusterPersistRepository repository = ((RegistryCenter) Plugins.getMemberAccessor().get(ProcessListChangedSubscriber.class.getDeclaredField("registryCenter"), subscriber)).getRepository();
        verify(repository).delete("/nodes/compute_nodes/process_kill/" + instanceId + ":foo_id");
    }
    
    @Test
    void assertCompleteToKillProcessInstance() {
        String processId = "foo_id";
        ProcessOperationLock lock = new ProcessOperationLock();
        ProcessOperationLockRegistry.getInstance().getLocks().put(processId, lock);
        long startMillis = System.currentTimeMillis();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.submit(() -> {
            try {
                Thread.sleep(50L);
            } catch (final InterruptedException ignored) {
            }
            subscriber.completeToKillProcessInstance(new KillProcessInstanceCompleteEvent(processId));
        });
        lockAndAwaitDefaultTime(lock);
        long currentTime = System.currentTimeMillis();
        assertTrue(currentTime >= startMillis + 50L);
        assertTrue(currentTime <= startMillis + 5000L);
        ProcessOperationLockRegistry.getInstance().getLocks().remove(processId);
    }
    
    private void lockAndAwaitDefaultTime(final ProcessOperationLock lock) {
        lock.lock();
        try {
            lock.awaitDefaultTime();
        } finally {
            lock.unlock();
        }
    }
}
