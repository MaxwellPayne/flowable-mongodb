/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.flowable.common.engine.impl.interceptor.Command;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.history.HistoricActivityInstance;
//import org.flowable.engine.impl.EventSubscriptionQueryImpl;
import org.flowable.engine.impl.test.JobTestHelper;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.engine.repository.ProcessDefinition;
//import org.flowable.engine.runtime.EventSubscription;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.eventsubscription.api.EventSubscription;
import org.flowable.eventsubscription.service.impl.EventSubscriptionQueryImpl;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.Test;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

/**
 * @author Joram Barrez
 */
public class ParallelGatewayTest extends AbstractMongoDbTest {

    /**
     * Case where there is a parallel gateway that splits into 3 paths of execution, that are immediately joined, without any wait states in between. In the end, no executions should be in the
     * database.
     */
    @Test
    public void testSplitMergeNoWaitstates() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testSplitMergeNoWaitstates.bpmn20.xml").deploy();
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("forkJoinNoWaitStates");
        assertTrue(processInstance.isEnded());
    }

    @Test
    public void testUnstructuredConcurrencyTwoForks() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testUnstructuredConcurrencyTwoForks.bpmn20.xml").deploy();
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("unstructuredConcurrencyTwoForks");
        assertTrue(processInstance.isEnded());
    }

    @Test
    public void testUnstructuredConcurrencyTwoJoins() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testUnstructuredConcurrencyTwoJoins.bpmn20.xml").deploy();
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("unstructuredConcurrencyTwoJoins");
        assertTrue(processInstance.isEnded());
    }

    @Test
    public void testForkFollowedByOnlyEndEvents() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testForkFollowedByOnlyEndEvents.bpmn20.xml").deploy();
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("forkFollowedByEndEvents");
        assertTrue(processInstance.isEnded());
    }

    @Test
    public void testNestedForksFollowedByEndEvents() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testNestedForksFollowedByEndEvents.bpmn20.xml").deploy();
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("nestedForksFollowedByEndEvents");
        assertTrue(processInstance.isEnded());
    }

    // ACT-482
    @Test
    public void testNestedForkJoin() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testNestedForkJoin.bpmn20.xml").deploy();
        runtimeService.startProcessInstanceByKey("nestedForkJoin");

        // After process starts, only task 0 should be active
        TaskQuery query = taskService.createTaskQuery().orderByTaskName().asc();
        List<org.flowable.task.api.Task> tasks = query.list();
        assertEquals(1, tasks.size());
        assertEquals("Task 0", tasks.get(0).getName());

        // Completing task 0 will create org.flowable.task.service.Task A and B
        taskService.complete(tasks.get(0).getId());
        tasks = query.list();
        assertEquals(2, tasks.size());
        assertEquals("Task A", tasks.get(0).getName());
        assertEquals("Task B", tasks.get(1).getName());

        // Completing task A should not trigger any new tasks
        taskService.complete(tasks.get(0).getId());
        tasks = query.list();
        assertEquals(1, tasks.size());
        assertEquals("Task B", tasks.get(0).getName());

        // Completing task B creates tasks B1 and B2
        taskService.complete(tasks.get(0).getId());
        tasks = query.list();
        assertEquals(2, tasks.size());
        assertEquals("Task B1", tasks.get(0).getName());
        assertEquals("Task B2", tasks.get(1).getName());

        // Completing B1 and B2 will activate both joins, and process reaches
        // task C
        taskService.complete(tasks.get(0).getId());
        taskService.complete(tasks.get(1).getId());
        tasks = query.list();
        assertEquals(1, tasks.size());
        assertEquals("Task C", tasks.get(0).getName());
    }

    /**
     * https://activiti.atlassian.net/browse/ACT-1222
     */
    @Test
    public void testRecyclingExecutionWithCallActivity() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testRecyclingExecutionWithCallActivity.bpmn20.xml").deploy();
        runtimeService.startProcessInstanceByKey("parent-process");

        // After process start we have two tasks, one from the parent and one
        // from the sub process
        TaskQuery query = taskService.createTaskQuery().orderByTaskName().asc();
        List<org.flowable.task.api.Task> tasks = query.list();
        assertEquals(2, tasks.size());
        assertEquals("Another task", tasks.get(0).getName());
        assertEquals("Some Task", tasks.get(1).getName());

        // we complete the task from the parent process, the root execution is
        // recycled, the task in the sub process is still there
        taskService.complete(tasks.get(1).getId());
        tasks = query.list();
        assertEquals(1, tasks.size());
        assertEquals("Another task", tasks.get(0).getName());

        // we end the task in the sub process and the sub process instance end
        // is propagated to the parent process
        taskService.complete(tasks.get(0).getId());
        assertEquals(0, taskService.createTaskQuery().count());
    }

    // Test to verify ACT-1755
    @Test
    public void testHistoryTables() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testHistoryTables.bpmn20.xml").deploy();
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("testHistoryRecords");
        List<HistoricActivityInstance> history = historyService.createHistoricActivityInstanceQuery().processInstanceId(pi.getId()).list();
        for (HistoricActivityInstance h : history) {
            if (h.getActivityId().equals("parallelgateway2")) {
                assertNotNull(h.getEndTime());
            }
        }
    }

    @Test
    public void testAsyncBehavior() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testAsyncBehavior.bpmn20.xml").deploy();
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey("async");
        JobTestHelper.waitForJobExecutorToProcessAllJobsAndTimerJobs(processEngineConfiguration, managementService, 10000L, 100L);
        assertEquals(0, runtimeService.createProcessInstanceQuery().processInstanceId(processInstance.getId()).count());
    }

    @Test
    public void testHistoricActivityInstanceEndTimes() {
        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testHistoricActivityInstanceEndTimes.bpmn20.xml").deploy();
        runtimeService.startProcessInstanceByKey("nestedForkJoin");

        List<HistoricActivityInstance> historicActivityInstances = historyService.createHistoricActivityInstanceQuery().list();
        assertEquals(21, historicActivityInstances.size());
        for (HistoricActivityInstance historicActivityInstance : historicActivityInstances) {
            assertNotNull(historicActivityInstance.getStartTime());
            assertNotNull(historicActivityInstance.getEndTime());
        }

    }

//    @Test
//    public void testNonTerminatingEndEventShouldNotRemoveSubscriptions() {
//        repositoryService.createDeployment().addClasspathResource("org/flowable/test/ParallelGatewayTest.testNonTerminatingEndEventShouldNotRemoveSubscriptions.bpmn20.xml").deploy();
//        ProcessDefinition processDefinition = repositoryService
//                .createProcessDefinitionQuery()
//                .processDefinitionKey("ec7ca606-a4f2-11e7-abc4-cec278b6b50a")
//                .singleResult();
//
//        final ProcessInstance processInstance = runtimeService.createProcessInstanceBuilder()
//                .processDefinitionId(processDefinition.getId())
//                .start();
//
//        List<Task> tasks = taskService.createTaskQuery()
//                .processInstanceId(processInstance.getProcessInstanceId())
//                .list();
//
//        assertEquals(1, tasks.size());
//
//        String executionId = processEngine.getManagementService().executeCommand(new Command<String>() {
//            @Override
//            public String execute(CommandContext commandContext) {
//                EventSubscriptionQueryImpl q = new EventSubscriptionQueryImpl(commandContext);
//                q.processInstanceId(processInstance.getProcessInstanceId());
//
//                List<EventSubscription> subs = CommandContextUtil
//                        .getEventSubscriptionEntityManager()
//                        .findEventSubscriptionsByQueryCriteria(q);
//
//                assertEquals(1, subs.size());
//                EventSubscription sub = subs.get(0);
//                assertEquals("testmessage", sub.getEventName());
//
//                return sub.getExecutionId();
//            }
//        });
//        taskService.complete(tasks.get(0).getId());
//
//        tasks = taskService.createTaskQuery()
//                .processInstanceId(processInstance.getProcessInstanceId())
//                .taskName("task 2")
//                .list();
//        assertEquals(1, tasks.size());
//
//        taskService.complete(tasks.get(0).getId());
//
//        tasks = taskService.createTaskQuery().processInstanceId(processInstance.getProcessInstanceId()).list();
//        assertEquals(1, tasks.size());
//
//        executionId = processEngine.getManagementService().executeCommand(new Command<String>() {
//            @Override
//            public String execute(CommandContext commandContext) {
//                EventSubscriptionQueryImpl q = new EventSubscriptionQueryImpl(commandContext);
//                q.processInstanceId(processInstance.getProcessInstanceId());
//
//                throw new NotImplementedException();
//                List<EventSubscription> subs = CommandContextUtil
//                        .getEventSubscriptionEntityManager()
//                        .findEventSubscriptionsByQueryCriteria(q);
//
//                assertEquals(1, subs.size());
//                EventSubscription sub = subs.get(0);
//                assertEquals("testmessage", sub.getEventName());
//
//                return sub.getExecutionId();
//            }
//        });
//
//    }

}